package at.aimon.core.subagent.task;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory, node-local {@link BackgroundTaskStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p>
 * The default implementation and reference for the multi-instance seam. It is thread-safe and correct for a single
 * instance; a scale-out deployment replaces it with a shared backend so that task listing spans nodes. State
 * transitions use {@link ConcurrentMap#compute(Object, java.util.function.BiFunction)} so the terminal-state guard is
 * atomic under concurrent completion and stop requests.
 *
 * <p>
 * Terminal tasks are retained (not removed on completion) so {@code status()}/{@code list()} can report finished
 * tasks, but retention is bounded: once the number of terminal tasks exceeds {@link #maxTerminalTasks}, the oldest
 * (by end time) are evicted. This caps memory for a long-lived agent that fans out many background tasks. In-flight
 * (non-terminal) tasks are never evicted.
 */
public final class InMemoryBackgroundTaskStore implements BackgroundTaskStore {

    /** Default cap on retained terminal tasks. */
    public static final int DEFAULT_MAX_TERMINAL_TASKS = 1000;

    private final ConcurrentMap<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final int maxTerminalTasks;

    /** Creates a store retaining up to {@link #DEFAULT_MAX_TERMINAL_TASKS} terminal tasks. */
    public InMemoryBackgroundTaskStore() {
        this(DEFAULT_MAX_TERMINAL_TASKS);
    }

    /**
     * Creates a store retaining up to {@code maxTerminalTasks} terminal tasks.
     *
     * @param maxTerminalTasks
     *            the maximum number of terminal tasks to retain (must be >= 1)
     * @throws IllegalArgumentException
     *             if {@code maxTerminalTasks < 1}
     */
    public InMemoryBackgroundTaskStore(int maxTerminalTasks) {
        if (maxTerminalTasks < 1) {
            throw new IllegalArgumentException("maxTerminalTasks must be >= 1, got: " + maxTerminalTasks);
        }
        this.maxTerminalTasks = maxTerminalTasks;
    }

    @Override
    public void put(BackgroundTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        tasks.put(task.getTaskId(), task);
        if (task.getState().isTerminal()) {
            evictTerminalOverflow();
        }
    }

    @Override
    public Optional<BackgroundTask> find(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<BackgroundTask> list(TaskQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        final List<BackgroundTask> result = new ArrayList<>();
        for (final BackgroundTask task : tasks.values()) {
            if (query.matches(task)) {
                result.add(task);
            }
        }
        return result;
    }

    @Override
    public Optional<BackgroundTask> transition(String taskId, BackgroundTaskState to) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(to, "target state cannot be null");
        final boolean[] applied = {false};
        final BackgroundTask updated = tasks.computeIfPresent(taskId, (id, current) -> {
            if (current.getState().isTerminal()) {
                // Idempotent: never transition out of a terminal state.
                return current;
            }
            applied[0] = true;
            final BackgroundTask.Builder builder = current.toBuilder().state(to);
            if (to.isTerminal()) {
                builder.endTime(Instant.now());
            }
            return builder.build();
        });
        if (applied[0] && to.isTerminal()) {
            evictTerminalOverflow();
        }
        return applied[0] ? Optional.of(updated) : Optional.empty();
    }

    @Override
    public Optional<BackgroundTask> heartbeat(String taskId, Instant at) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(at, "heartbeat instant cannot be null");
        final boolean[] applied = {false};
        final BackgroundTask updated = tasks.computeIfPresent(taskId, (id, current) -> {
            if (current.getState().isTerminal()) {
                // Idempotent: never renew a terminal task's lease.
                return current;
            }
            applied[0] = true;
            return current.toBuilder().lastHeartbeat(at).build();
        });
        return applied[0] ? Optional.of(updated) : Optional.empty();
    }

    @Override
    public void remove(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        tasks.remove(taskId);
    }

    /**
     * Evicts the oldest terminal tasks (by end time, falling back to start time) once the number of retained terminal
     * tasks exceeds the cap. In-flight (non-terminal) tasks are never evicted. Concurrent eviction is harmless — at
     * worst it removes a few extra already-terminal entries.
     */
    private void evictTerminalOverflow() {
        // Cheap guard: nothing to do until the whole map is larger than the cap.
        if (tasks.size() <= maxTerminalTasks) {
            return;
        }
        final List<BackgroundTask> terminal = new ArrayList<>();
        for (final BackgroundTask task : tasks.values()) {
            if (task.getState().isTerminal()) {
                terminal.add(task);
            }
        }
        final int overflow = terminal.size() - maxTerminalTasks;
        if (overflow <= 0) {
            return;
        }
        terminal.sort(Comparator.comparing(task -> task.getEndTime().orElseGet(task::getStartTime)));
        for (int i = 0; i < overflow; i++) {
            tasks.remove(terminal.get(i).getTaskId());
        }
    }
}
