package at.aimon.core.tools.bash;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;

/**
 * The list of background bash tasks: starts them, finds them again, stops them.
 *
 * <p>
 * This manager provides:
 *
 * <ul>
 * <li>Starting a command in the background and tracking it ({@link #start})
 * <li>Looking a task up for the owner that started it ({@link #find}), and stopping it ({@link #kill})
 * <li>Read-once output consumption
 * <li>Task status monitoring
 * <li>Output filtering with regex
 * </ul>
 *
 * <p>
 * <b>Lifetime.</b> A manager lives as long as whoever built it. The bootstrap stack builds one for the application and
 * hands it to every runtime's {@code Bash}, {@code BashOutput} and {@code KillShell}, so a task outlives the eviction
 * of the tenant runtime that started it and its rebuilt successor finds the same task id. A manager built with
 * {@link #BackgroundBashManager()} for one tool registry lives and dies with that registry, as it always did.
 *
 * <p>
 * <b>What is shared and what is not.</b> A task's metadata — owner, node, status — goes to the
 * {@link BackgroundBashStore}, which can be shared between nodes. The command itself is a process of this node, and
 * its handle ({@link BackgroundBashTask}: the future, the cancellation signal, the output) is kept in this manager's
 * memory. A record without a handle is therefore reported as running elsewhere, and cannot be read or stopped from
 * here.
 *
 * <p>
 * <b>Ownership.</b> One application-wide list is visible to every runtime, and a task id (32 random bits) is no
 * boundary. {@link #find} and {@link #kill} answer only the {@linkplain BackgroundBashOwner owner} the task was
 * started for — the runtime and, within it, the session (a session's turns and the forks spawned for it) or the
 * session-less execution; for any other caller the id does not exist. Two sessions of one runtime do not see each
 * other's tasks. The id-only
 * methods ({@link #getTask}, {@link #readNewOutput} …) are not scoped — they serve callers that hold the manager
 * directly, not the tools.
 *
 * <p>
 * <b>Retention.</b> A finished task is dropped, record and all, once it has been finished for longer than the
 * retention period (24 hours by default); the sweep runs whenever a task is started.
 *
 * <p>
 * Output is not streamed: a task's buffer is filled in one go when its command finishes, so polling a running task
 * returns nothing. See {@link BackgroundBashTask} for why.
 *
 * <p>
 * Thread-safe. The commands run on this manager's own daemon threads, so a manager nobody closes does not keep the JVM
 * alive.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     BackgroundBashManager manager = new BackgroundBashManager();
 *
 *     // Start a background task in the execution environment's shell
 *     BackgroundBashTask task = manager.start(runtimeId, "npm install", shell, options);
 *
 *     // Consume output not yet read
 *     Optional<String> output = manager.readNewOutput(task.getTaskId(), null);
 *
 *     // Stop it
 *     manager.kill(runtimeId, task.getTaskId());
 * }
 * </pre>
 */
public class BackgroundBashManager implements AutoCloseable {

    /** How long a finished task stays readable by default. */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(BackgroundBashManager.class);

    private static final String TASK_ID_PREFIX = "bash_";
    private static final int TASK_ID_LENGTH = 8;
    private static final int MAX_ID_ATTEMPTS = 16;
    private static final long CLOSE_WAIT_SECONDS = 5;

    private final Map<String, BackgroundBashTask> tasks;
    private final BackgroundBashStore store;
    private final String nodeId;
    private final Duration retention;
    private final Clock clock;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a manager with an in-memory store, a random node id, the default retention and the system clock.
     */
    public BackgroundBashManager() {
        this(builder());
    }

    private BackgroundBashManager(Builder builder) {
        tasks = new ConcurrentHashMap<>();
        this.store = builder.store != null ? builder.store : new InMemoryBackgroundBashStore();
        this.nodeId = builder.nodeId != null ? builder.nodeId : "node-" + UUID.randomUUID();
        this.retention = builder.retention;
        this.clock = builder.clock;
        // Daemon threads: a manager built for one tool registry is never closed, and non-daemon threads here would
        // keep the JVM alive for as long as a background command runs.
        executor = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "background-bash");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts a command in the background and tracks it.
     *
     * <p>
     * The shell is used for this one call: the running command keeps it, the task does not. When the shell declares
     * {@link ShellFeature#CANCELLATION} the command runs with a cancellation signal of its own and {@link #kill} can
     * stop it; otherwise the command ends only by itself or at the timeout in {@code options}.
     *
     * @param owner
     *            who the command is started for (must not be null) — only a caller with the same owner finds the task
     *            again
     * @param command
     *            the command (must not be null)
     * @param shell
     *            the shell to run it in (must not be null)
     * @param options
     *            the execution options; the timeout is the command's ceiling (must not be null)
     * @return the task
     * @throws IllegalStateException
     *             if this manager is closed
     * @throws RuntimeException
     *             if the store cannot record the task; the command is then not started
     */
    public BackgroundBashTask start(BackgroundBashOwner owner, String command, VirtualShell shell,
            ExecutionOptions options) {
        Objects.requireNonNull(owner, "Owner cannot be null");
        Objects.requireNonNull(command, "Command cannot be null");
        Objects.requireNonNull(shell, "Shell cannot be null");
        Objects.requireNonNull(options, "Options cannot be null");
        ensureOpen();
        sweepFinished();

        final ShellCancellationSource cancellation = shell.supports(ShellFeature.CANCELLATION)
                ? ShellCancellationSource.create()
                : null;
        final ExecutionOptions effective = cancellation != null
                ? options.toBuilder().cancellation(cancellation.token()).build()
                : options;
        final Duration timeout = options.getTimeout() != null && !options.getTimeout().isZero()
                && !options.getTimeout().isNegative() ? options.getTimeout() : null;

        // The record goes in before the command starts: a command the store could not record must not run, because
        // nothing but this node would ever know it exists.
        final String taskId = recordNewTask(owner, timeout);
        final CompletableFuture<ShellCommandResult> future = new CompletableFuture<>();
        final BackgroundBashTask task = new BackgroundBashTask(taskId, command, future, owner, cancellation, timeout,
                clock);
        tasks.put(taskId, task);
        task.onSettled(() -> settleRecord(task));
        try {
            executor.execute(() -> run(shell, command, effective, future));
        } catch (RejectedExecutionException e) {
            tasks.remove(taskId, task);
            removeRecordQuietly(taskId);
            throw new IllegalStateException(closedMessage(), e);
        }
        if (closed.get()) {
            // close() ran between ensureOpen() above and tasks.put(): its sweep of running tasks may have missed this
            // one, which would then run until close()'s thread interrupt, five seconds on. Stop it here instead; a
            // signal tripped before the shell starts the command means the command never starts.
            task.requestCancel();
        }
        return task;
    }

    private static void run(VirtualShell shell, String command, ExecutionOptions options,
            CompletableFuture<ShellCommandResult> future) {
        try {
            future.complete(shell.execute(() -> command, options));
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private String recordNewTask(BackgroundBashOwner owner, Duration timeout) {
        final Instant now = clock.instant();
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            final String taskId = TASK_ID_PREFIX + UUID.randomUUID().toString().substring(0, TASK_ID_LENGTH);
            if (tasks.containsKey(taskId)) {
                continue;
            }
            final BackgroundBashRecord record = BackgroundBashRecord.builder().taskId(taskId).owner(owner)
                    .nodeId(nodeId).startedAt(now).expiresAt(timeout != null ? now.plus(timeout) : null).build();
            if (store.putIfAbsent(record)) {
                return taskId;
            }
        }
        throw new IllegalStateException(
                "Could not draw an unused background task id in " + MAX_ID_ATTEMPTS + " attempts");
    }

    private void settleRecord(BackgroundBashTask task) {
        try {
            store.settle(task.getTaskId(), task.getStatus(), task.getExitCode(),
                    task.getFinishedAt().orElseGet(clock::instant));
        } catch (RuntimeException e) {
            // This node's handle is the authority for its own tasks, so BashOutput still answers correctly. Other
            // nodes keep seeing "running" until the record's expiry.
            log.warn("Could not record the end of background task {}: {}", task.getTaskId(), e.toString());
        }
    }

    private void removeRecordQuietly(String taskId) {
        try {
            store.remove(taskId);
        } catch (RuntimeException e) {
            log.warn("Could not remove the record of background task {}: {}", taskId, e.toString());
        }
    }

    /** Drops tasks that have been finished for longer than the retention period, with their records. */
    private void sweepFinished() {
        final Instant cutoff = clock.instant().minus(retention);
        for (BackgroundBashTask task : List.copyOf(tasks.values())) {
            if (task.getFinishedAt().filter(finished -> finished.isBefore(cutoff)).isPresent()
                    && tasks.remove(task.getTaskId(), task)) {
                removeRecordQuietly(task.getTaskId());
            }
        }
    }

    /**
     * Looks a task up on behalf of its owner.
     *
     * <p>
     * A task started with {@link BackgroundBashOwner#none()} is found by every caller that has no owner either — owner
     * matching is the only boundary. An assembly that shares one manager between runtimes must therefore put
     * {@code ToolContextKeys.AGENT_RUNTIME_ID} in every tool context, and the session or execution id with it, as the
     * Orca executors do; a context without them sees, and can stop, every other task that is as unscoped as it is.
     *
     * <p>
     * The owner is compared before anything else is decided, on the stored record as well as on this node's handle,
     * so the answer does not depend on the node that is asked: another owner's task is not-found everywhere, and
     * never "running on another node".
     *
     * @param owner
     *            the caller's owner (must not be null); a task is found only by the owner it was started with
     * @param taskId
     *            the task id (must not be null)
     * @return the task if this node has it, the record if only the store does, or not-found
     * @throws RuntimeException
     *             if this node does not have the task and the store cannot be read
     */
    public BackgroundBashLookup find(BackgroundBashOwner owner, String taskId) {
        Objects.requireNonNull(owner, "Owner cannot be null");
        Objects.requireNonNull(taskId, "Task ID cannot be null");

        final BackgroundBashTask task = tasks.get(taskId);
        if (task != null) {
            return owner.equals(task.getOwner()) ? BackgroundBashLookup.local(task) : BackgroundBashLookup.notFound();
        }
        final Optional<BackgroundBashRecord> found = store.find(taskId);
        if (found.isEmpty() || !owner.equals(found.get().owner())) {
            return BackgroundBashLookup.notFound();
        }
        final BackgroundBashRecord record = found.get();
        final Instant now = clock.instant();
        final boolean expired = record.getStatus() == BashTaskStatus.RUNNING
                && record.getExpiresAt().filter(now::isAfter).isPresent();
        if (expired) {
            // Its node never reported the end and the timeout must have ended the command by now. Nobody will, so
            // this is the last time the record is reported.
            removeRecordQuietly(taskId);
        }
        return BackgroundBashLookup.elsewhere(record, nodeId.equals(record.getNodeId()), expired);
    }

    /**
     * Stops a running task on behalf of its owner.
     *
     * <p>
     * Returns once the shell has been asked to stop the command, which for a local shell includes the grace period it
     * gives the process. The task reports {@link BashTaskStatus#KILLED} when the command's {@code execute} has
     * returned — wait on {@link BackgroundBashTask#awaitCompletion(Duration)} to see it.
     *
     * @param owner
     *            the caller's owner (must not be null; see {@link #find})
     * @param taskId
     *            the task id (must not be null)
     * @return what was done
     * @throws RuntimeException
     *             if this node does not have the task and the store cannot be read
     */
    public BackgroundBashKill kill(BackgroundBashOwner owner, String taskId) {
        final BackgroundBashLookup lookup = find(owner, taskId);
        switch (lookup.kind()) {
            case NOT_FOUND :
                return new BackgroundBashKill(BackgroundBashKill.Outcome.NOT_FOUND, lookup);
            case ELSEWHERE :
                return new BackgroundBashKill(BackgroundBashKill.Outcome.ELSEWHERE, lookup);
            default :
                break;
        }
        final BackgroundBashTask task = lookup.task().orElseThrow();
        if (task.getStatus() != BashTaskStatus.RUNNING) {
            return new BackgroundBashKill(BackgroundBashKill.Outcome.NOT_RUNNING, lookup);
        }
        if (!task.requestCancel()) {
            return new BackgroundBashKill(BackgroundBashKill.Outcome.UNSUPPORTED, lookup);
        }
        return new BackgroundBashKill(BackgroundBashKill.Outcome.REQUESTED, lookup);
    }

    /**
     * Registers a background bash task whose future the caller built. The task has no owner and cannot be stopped
     * through {@link #kill} — the manager did not start it and holds no signal for it. It is not written to the store.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @param command
     *            The bash command (must not be null)
     * @param future
     *            The future representing the command execution (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public void registerTask(String taskId, String command, CompletableFuture<ShellCommandResult> future) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(command, "Command cannot be null");
        Objects.requireNonNull(future, "Future cannot be null");

        final BackgroundBashTask task = new BackgroundBashTask(taskId, command, future, BackgroundBashOwner.none(),
                null, null, clock);
        tasks.put(taskId, task);
    }

    /**
     * Reads the output of a background task that has not been consumed yet.
     *
     * <p>
     * Each line is returned only once. A task that is still running has nothing to return — its buffer is filled at
     * completion, not as the command runs.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @param filterRegex
     *            Optional regex pattern to filter output lines, or null for no filtering
     * @return An Optional containing the new output if the task exists, empty otherwise
     * @throws NullPointerException
     *             if taskId is null
     */
    public Optional<String> readNewOutput(String taskId, String filterRegex) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");

        final BackgroundBashTask task = tasks.get(taskId);
        if (task == null) {
            return Optional.empty();
        }

        final String output = task.readNewOutput(filterRegex);
        return Optional.of(output);
    }

    /**
     * Gets the status of a background task.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @return The task status
     * @throws NullPointerException
     *             if taskId is null
     */
    public BashTaskStatus getTaskStatus(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");

        final BackgroundBashTask task = tasks.get(taskId);
        if (task == null) {
            return BashTaskStatus.NOT_FOUND;
        }

        return task.getStatus();
    }

    /**
     * Gets a background task of this node by id, whoever owns it.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @return An Optional containing the task if it exists, empty otherwise
     * @throws NullPointerException
     *             if taskId is null
     */
    public Optional<BackgroundBashTask> getTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * Waits for a task to complete.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @param timeoutSeconds
     *            Maximum time to wait in seconds (must be positive)
     * @return true if the task completed within the timeout, false otherwise
     * @throws NullPointerException
     *             if taskId is null
     * @throws IllegalArgumentException
     *             if timeoutSeconds is not positive
     */
    public boolean awaitCompletion(String taskId, int timeoutSeconds) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }

        final BackgroundBashTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        return task.awaitCompletion(Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * Removes a task from the manager, and its record from the store. A command still running is not stopped.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @throws NullPointerException
     *             if taskId is null
     */
    public void removeTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        if (tasks.remove(taskId) != null) {
            removeRecordQuietly(taskId);
        }
    }

    /**
     * Checks if a task exists on this node, whoever owns it.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @return true if the task exists, false otherwise
     * @throws NullPointerException
     *             if taskId is null
     */
    public boolean hasTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return tasks.containsKey(taskId);
    }

    /**
     * Gets the number of tasks this node tracks, running or finished.
     *
     * @return The number of tracked tasks
     */
    public int getActiveTaskCount() {
        return tasks.size();
    }

    /** Clears all tasks, and their records. Commands still running are not stopped. */
    public void clear() {
        for (String taskId : List.copyOf(tasks.keySet())) {
            removeTask(taskId);
        }
    }

    /**
     * Returns the id this manager writes into the records of the tasks it starts.
     *
     * @return the node id
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Closes the task list: refuses new tasks, stops every running command this node can stop, and retires the
     * threads the commands ran on. Tasks stay readable. Idempotent.
     *
     * <p>
     * A command whose shell cannot cancel it is left to the thread interrupt that ends the wait below — which a
     * local shell answers by killing the process, and a shell that ignores interrupts does not answer at all.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Signalled side by side, not one after the other: each signal can take a shell's whole grace period, and a
        // node shutting down with many commands running would otherwise wait for all of them in turn.
        for (BackgroundBashTask task : tasks.values()) {
            if (task.getStatus() == BashTaskStatus.RUNNING && task.isCancellable()) {
                try {
                    executor.execute(task::requestCancel);
                } catch (RejectedExecutionException e) {
                    task.requestCancel();
                }
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException(closedMessage());
        }
    }

    private static String closedMessage() {
        return "The background task list is closed (the application is shutting down); no background command can be"
                + " started";
    }

    /** Builder for {@link BackgroundBashManager}. */
    public static final class Builder {
        private BackgroundBashStore store;
        private String nodeId;
        private Duration retention = DEFAULT_RETENTION;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * Sets where task records are kept (default: a new {@link InMemoryBackgroundBashStore}).
         *
         * @param store
         *            the store, or null for the default
         * @return this builder
         */
        public Builder store(BackgroundBashStore store) {
            this.store = store;
            return this;
        }

        /**
         * Sets the id of this node as written into task records (default: a random id). With a shared store it tells
         * "another node's task" from "this node's task from before a restart", so give it the node's stable id.
         *
         * @param nodeId
         *            the node id, or null for a random one
         * @return this builder
         */
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /**
         * Sets how long a finished task stays readable (default {@link #DEFAULT_RETENTION}).
         *
         * @param retention
         *            the retention period (must not be null or negative)
         * @return this builder
         */
        public Builder retention(Duration retention) {
            Objects.requireNonNull(retention, "retention must not be null");
            if (retention.isNegative()) {
                throw new IllegalArgumentException("retention must not be negative, got: " + retention);
            }
            this.retention = retention;
            return this;
        }

        /**
         * Sets the clock tasks are stamped and aged with.
         *
         * @param clock
         *            the clock (must not be null)
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        /**
         * Builds the manager.
         *
         * @return the manager
         */
        public BackgroundBashManager build() {
            return new BackgroundBashManager(this);
        }
    }
}
