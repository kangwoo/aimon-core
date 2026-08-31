package at.aimon.core.workflow.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.RunQuery;
import at.aimon.core.workflow.RunStore;
import at.aimon.core.workflow.WorkflowRun;
import at.aimon.core.workflow.WorkflowRunState;

/**
 * In-memory, node-local {@link RunStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p>
 * The default implementation and reference for the multi-instance seam (run-scoped analog of
 * {@code InMemoryBackgroundTaskStore}). It is thread-safe and correct for a single instance; a scale-out deployment
 * replaces it with a shared backend so that run listing spans nodes. {@link #putIfAbsentOrTerminal} and
 * {@link #transition} use {@link ConcurrentMap#compute(Object, java.util.function.BiFunction)} so the idempotency and
 * terminal-state guards are atomic under concurrent submission, completion, and stop requests.
 *
 * <p>
 * Terminal runs are retained (not removed on completion) so {@code status()}/{@code list()} can report finished runs,
 * but retention is bounded: once the number of terminal runs exceeds {@link #maxTerminalRuns}, the oldest (by end time)
 * are evicted. In-flight (non-terminal) runs are never evicted.
 */
public final class InMemoryRunStore implements RunStore {

    /** Default cap on retained terminal runs. */
    public static final int DEFAULT_MAX_TERMINAL_RUNS = 1000;

    private final ConcurrentMap<RunId, WorkflowRun> runs = new ConcurrentHashMap<>();
    private final int maxTerminalRuns;

    /** Creates a store retaining up to {@link #DEFAULT_MAX_TERMINAL_RUNS} terminal runs. */
    public InMemoryRunStore() {
        this(DEFAULT_MAX_TERMINAL_RUNS);
    }

    /**
     * Creates a store retaining up to {@code maxTerminalRuns} terminal runs.
     *
     * @param maxTerminalRuns
     *            the maximum number of terminal runs to retain (must be >= 1)
     * @throws IllegalArgumentException
     *             if {@code maxTerminalRuns < 1}
     */
    public InMemoryRunStore(int maxTerminalRuns) {
        if (maxTerminalRuns < 1) {
            throw new IllegalArgumentException("maxTerminalRuns must be >= 1, got: " + maxTerminalRuns);
        }
        this.maxTerminalRuns = maxTerminalRuns;
    }

    @Override
    public void put(WorkflowRun run) {
        Objects.requireNonNull(run, "run cannot be null");
        runs.put(run.getRunId(), run);
        if (run.getState().isTerminal()) {
            evictTerminalOverflow();
        }
    }

    @Override
    public boolean putIfAbsentOrTerminal(WorkflowRun run) {
        Objects.requireNonNull(run, "run cannot be null");
        final boolean[] inserted = {false};
        runs.compute(run.getRunId(), (id, current) -> {
            if (current == null || current.getState().isTerminal()) {
                // Absent or terminal → (re)submit. A non-terminal existing run is kept, so submission is refused.
                inserted[0] = true;
                return run;
            }
            return current;
        });
        if (inserted[0] && run.getState().isTerminal()) {
            evictTerminalOverflow();
        }
        return inserted[0];
    }

    @Override
    public Optional<WorkflowRun> find(RunId runId) {
        Objects.requireNonNull(runId, "runId cannot be null");
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<WorkflowRun> list(RunQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        final List<WorkflowRun> result = new ArrayList<>();
        for (final WorkflowRun run : runs.values()) {
            if (query.matches(run)) {
                result.add(run);
            }
        }
        return result;
    }

    @Override
    public Optional<WorkflowRun> transition(RunId runId, WorkflowRunState to) {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(to, "target state cannot be null");
        final boolean[] applied = {false};
        final WorkflowRun updated = runs.computeIfPresent(runId, (id, current) -> {
            if (current.getState().isTerminal()) {
                // Idempotent: never transition out of a terminal state.
                return current;
            }
            applied[0] = true;
            final WorkflowRun.Builder builder = current.toBuilder().state(to);
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
    public Optional<WorkflowRun> heartbeat(RunId runId, Instant at) {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(at, "heartbeat instant cannot be null");
        final boolean[] applied = {false};
        final WorkflowRun updated = runs.computeIfPresent(runId, (id, current) -> {
            if (current.getState().isTerminal()) {
                // Idempotent: never renew a terminal run's lease.
                return current;
            }
            applied[0] = true;
            return current.toBuilder().lastHeartbeat(at).build();
        });
        return applied[0] ? Optional.of(updated) : Optional.empty();
    }

    @Override
    public void remove(RunId runId) {
        Objects.requireNonNull(runId, "runId cannot be null");
        runs.remove(runId);
    }

    /**
     * Evicts the oldest terminal runs (by end time, falling back to start time) once the number of retained terminal
     * runs exceeds the cap. In-flight (non-terminal) runs are never evicted. Concurrent eviction is harmless — at worst
     * it removes a few extra already-terminal entries.
     */
    private void evictTerminalOverflow() {
        // Cheap guard: nothing to do until the whole map is larger than the cap.
        if (runs.size() <= maxTerminalRuns) {
            return;
        }
        final List<WorkflowRun> terminal = new ArrayList<>();
        for (final WorkflowRun run : runs.values()) {
            if (run.getState().isTerminal()) {
                terminal.add(run);
            }
        }
        final int overflow = terminal.size() - maxTerminalRuns;
        if (overflow <= 0) {
            return;
        }
        terminal.sort(Comparator.comparing(run -> run.getEndTime().orElseGet(run::getStartTime)));
        for (int i = 0; i < overflow; i++) {
            runs.remove(terminal.get(i).getRunId());
        }
    }
}
