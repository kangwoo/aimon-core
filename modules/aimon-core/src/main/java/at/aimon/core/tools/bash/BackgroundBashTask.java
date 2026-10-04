package at.aimon.core.tools.bash;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;

/**
 * Represents a background bash task.
 *
 * <p>
 * This class manages:
 *
 * <ul>
 * <li>Task execution state (running, completed, failed, killed)
 * <li>Read-once consumption of the output buffer
 * <li>Output filtering with regex patterns
 * <li>Exit code tracking
 * </ul>
 *
 * <p>
 * <b>Output arrives all at once, at completion.</b> The buffer is filled from the shell result in the completion
 * handler below, so a task polled while it is still running has nothing to give. {@link #readNewOutput()} is
 * read-<em>once</em> rather than incremental: it hands back what has not been consumed yet and advances the cursor,
 * which is what makes repeated polling safe, not a claim that output streams in. The shell that produces these results
 * captures to a file and reads it after the process exits, so there is no incremental source to expose.
 *
 * <p>
 * <b>Node-local.</b> A task is the handle of a command running in a process of this node: the future, the signal that
 * stops it, the output. What can be shared with other nodes is its {@link BackgroundBashRecord}. A task started by
 * {@link BackgroundBashManager#start} knows the runtime that owns it and, when the shell supports cancellation, how
 * to stop the command; one built from a bare future ({@link #BackgroundBashTask(String, String, CompletableFuture)})
 * has no owner and cannot be stopped.
 *
 * <p>
 * Thread-safe for concurrent access.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     BackgroundBashTask task = new BackgroundBashTask("task_123", "npm install", future);
 *
 *     // Consume output not yet read
 *     String newOutput = task.readNewOutput();
 *
 *     // Read with filter
 *     String filteredOutput = task.readNewOutput("error|warning");
 *
 *     // Check status
 *     BashTaskStatus status = task.getStatus();
 * }
 * </pre>
 */
public class BackgroundBashTask {

    /**
     * Exit code reported when the command never ran to completion — the shell threw instead of returning a result, so
     * there is no real exit status to report. Distinct from a command that genuinely exited 1, which arrives as a
     * normal result and keeps its own code.
     */
    private static final int ERROR_EXIT_CODE = 1;

    private final String taskId;
    private final String command;
    private final CompletableFuture<ShellCommandResult> future;
    /**
     * Completes after the completion handler has recorded the outcome. Waiters join this, not {@link #future}: a thread
     * joined on the raw future can be woken before the handler runs, and would then read a task with no exit code, no
     * output and no notices.
     */
    private final CompletableFuture<ShellCommandResult> settled;
    private final BackgroundBashOwner owner;
    private final ShellCancellationSource cancellation;
    private final Duration timeout;
    private final Instant startedAt;
    private final List<String> outputLines;
    private final AtomicInteger lastReadLine;
    private final Object stateLock = new Object();
    private boolean completed;
    private boolean failed;
    private boolean killed;
    private Instant finishedAt;
    private boolean outputTruncated;
    private List<String> notices = List.of();
    private Integer exitCode;
    private String errorMessage;

    /**
     * Creates a new BackgroundBashTask.
     *
     * @param taskId
     *            The unique task ID (must not be null)
     * @param command
     *            The bash command being executed (must not be null)
     * @param future
     *            The future representing the command execution (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public BackgroundBashTask(String taskId, String command, CompletableFuture<ShellCommandResult> future) {
        this(taskId, command, future, BackgroundBashOwner.none(), null, null, Clock.systemUTC());
    }

    /**
     * Creates a task for a command the manager started.
     *
     * @param owner
     *            who the command was started for (must not be null; {@link BackgroundBashOwner#none()} for nobody)
     * @param cancellation
     *            the source whose signal the command runs with, or null when the shell cannot stop it
     * @param timeout
     *            the timeout the command runs with, or null for none
     * @param clock
     *            the clock start and end are stamped with
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    BackgroundBashTask(String taskId, String command, CompletableFuture<ShellCommandResult> future,
            BackgroundBashOwner owner, ShellCancellationSource cancellation, Duration timeout, Clock clock) {
        Objects.requireNonNull(clock, "Clock cannot be null");
        this.owner = Objects.requireNonNull(owner, "Owner cannot be null");
        this.cancellation = cancellation;
        this.timeout = timeout;
        this.startedAt = clock.instant();
        this.taskId = Objects.requireNonNull(taskId, "Task ID cannot be null");
        this.command = Objects.requireNonNull(command, "Command cannot be null");
        this.future = Objects.requireNonNull(future, "Future cannot be null");
        outputLines = Collections.synchronizedList(new ArrayList<>());
        lastReadLine = new AtomicInteger(0);
        completed = false;
        failed = false;

        // Set up completion handler
        settled = future.whenComplete((result, error) -> {
            synchronized (stateLock) {
                if (error != null) {
                    failed = true;
                    final Throwable cause = unwrap(error);
                    errorMessage = cause.getMessage();
                    // A command stopped on request did not fail on its own account and has no exit status worth
                    // reporting: it is KILLED, with the output it had printed.
                    killed = cause instanceof ShellCancelledException;
                    exitCode = killed ? null : ERROR_EXIT_CODE;

                    // A shell failure still carries whatever the process managed to print — a command killed at its
                    // timeout usually explains itself in that partial output. Dropping it would make the new
                    // shell-enforced timeout strictly less informative than the old unbounded path.
                    if (cause instanceof ShellExecutionException shellFailure) {
                        appendLines(shellFailure.stdout());
                        appendLines(shellFailure.stderr());
                        outputTruncated = shellFailure.outputTruncated();
                        notices = shellFailure.notices();
                    }
                } else if (result != null) {
                    appendLines(result.stdout());
                    appendLines(result.stderr());

                    // Set the flag from the result rather than leaving it false. A non-zero exit is a *completed*
                    // future, so deriving failure from the future alone would report a broken build as
                    // "Status: Completed / Exit Code: 127" — the model reads that as success.
                    failed = result.isFailure();
                    exitCode = result.exitCode();
                    outputTruncated = result.outputTruncated();
                    notices = result.notices();
                }
                finishedAt = clock.instant();
                completed = true;
            }
        });
    }

    /** Splits shell output into lines and appends them to the buffer, skipping null and empty payloads. */
    private void appendLines(String output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        final String[] lines = output.split("\n", -1);
        synchronized (outputLines) {
            Collections.addAll(outputLines, lines);
        }
    }

    /** Peels the {@link CompletionException} wrapper that {@code supplyAsync} adds around a thrown failure. */
    private static Throwable unwrap(Throwable error) {
        return (error instanceof CompletionException && error.getCause() != null) ? error.getCause() : error;
    }

    /**
     * Reads the output that has not been consumed yet.
     *
     * <p>
     * Each line is returned only once. Until the command finishes there is nothing to return — see the class javadoc
     * on why output arrives in one batch rather than incrementally.
     *
     * @return The unread output, or empty string if there is none
     */
    public String readNewOutput() {
        return readNewOutput(null);
    }

    /**
     * Reads the output that has not been consumed yet, optionally filtered by regex.
     *
     * <p>
     * If a filter is provided, only lines matching the pattern are returned. Non-matching lines are discarded and
     * cannot be read later.
     *
     * @param filterRegex
     *            The regex pattern to filter lines, or null for no filtering
     * @return The new filtered output, or empty string if no matching output
     */
    public String readNewOutput(String filterRegex) {
        final Pattern pattern = (filterRegex != null && !filterRegex.isEmpty()) ? Pattern.compile(filterRegex) : null;

        final StringBuilder result = new StringBuilder();

        synchronized (outputLines) {
            final int currentLine = lastReadLine.get();
            final int totalLines = outputLines.size();

            for (int i = currentLine; i < totalLines; i++) {
                final String line = outputLines.get(i);

                // Apply filter if provided
                if (pattern == null || pattern.matcher(line).find()) {
                    if (result.length() > 0) {
                        result.append('\n');
                    }
                    result.append(line);
                }
            }

            // Update last read position
            lastReadLine.set(totalLines);
        }

        return result.toString();
    }

    /**
     * Gets the current status of the task.
     *
     * <p>
     * Derived from the recorded outcome only, never from the future: the future is done a moment before the completion
     * handler records the exit code, output and notices, and a status read in that window would report a finished
     * task with none of them.
     *
     * @return The task status
     */
    public BashTaskStatus getStatus() {
        synchronized (stateLock) {
            if (!completed) {
                return BashTaskStatus.RUNNING;
            }
            if (killed) {
                return BashTaskStatus.KILLED;
            }
            return failed ? BashTaskStatus.FAILED : BashTaskStatus.COMPLETED;
        }
    }

    /**
     * Waits for the task to complete and for its outcome to be recorded.
     *
     * @return true if completed successfully, false if failed
     */
    public boolean awaitCompletion() {
        try {
            settled.handle((result, error) -> null).join();
        } catch (Exception e) {
            return false;
        }
        synchronized (stateLock) {
            return !failed;
        }
    }

    /**
     * Waits up to {@code wait} for the task to complete and for its outcome to be recorded.
     *
     * @param wait
     *            how long to wait (must not be null)
     * @return true if the outcome is recorded, false if the command is still running after the wait
     */
    public boolean awaitCompletion(Duration wait) {
        Objects.requireNonNull(wait, "Wait cannot be null");
        try {
            settled.handle((result, error) -> null).get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /** Runs {@code action} once the outcome is recorded — at once if it already is. Failures are the caller's. */
    void onSettled(Runnable action) {
        settled.handle((result, error) -> null).thenRun(action);
    }

    /**
     * Trips the command's cancellation signal. Returns once the shell has asked the command to stop, which is before
     * this task reports {@link BashTaskStatus#KILLED}.
     *
     * @return true if a signal was sent by this call or an earlier one, false if this task cannot be stopped
     */
    boolean requestCancel() {
        if (cancellation == null) {
            return false;
        }
        cancellation.cancel();
        return true;
    }

    /**
     * Returns who the command was started for. Only a caller with the same owner finds the task.
     *
     * @return the owner (never null; {@link BackgroundBashOwner#none()} for a task started outside any runtime)
     */
    public BackgroundBashOwner getOwner() {
        return owner;
    }

    /**
     * Whether the command can be stopped: its shell declared {@code ShellFeature.CANCELLATION} when the task started.
     *
     * @return true if the command can be stopped
     */
    public boolean isCancellable() {
        return cancellation != null;
    }

    /**
     * Returns the timeout the command runs with — the ceiling that ends it if nothing else does.
     *
     * @return the timeout, or empty if the task was not started by the manager
     */
    public Optional<Duration> getTimeout() {
        return Optional.ofNullable(timeout);
    }

    /**
     * Returns when the task was created.
     *
     * @return the start time
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * Returns when the outcome was recorded.
     *
     * @return the end time, or empty while the command runs
     */
    public Optional<Instant> getFinishedAt() {
        synchronized (stateLock) {
            return Optional.ofNullable(finishedAt);
        }
    }

    /**
     * Gets the task ID.
     *
     * @return The task ID
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Gets the command being executed.
     *
     * @return The command
     */
    public String getCommand() {
        return command;
    }

    /**
     * Gets the exit code if the task has completed.
     *
     * @return The exit code, or null if not yet completed or if the command was killed
     */
    public Integer getExitCode() {
        synchronized (stateLock) {
            return exitCode;
        }
    }

    /**
     * Gets the error message if the task failed.
     *
     * @return The error message, or null if no error
     */
    public String getErrorMessage() {
        synchronized (stateLock) {
            return errorMessage;
        }
    }

    /**
     * Checks if the task has completed.
     *
     * @return true if completed, false otherwise
     */
    public boolean isCompleted() {
        synchronized (stateLock) {
            return completed;
        }
    }

    /**
     * Checks if the task has failed.
     *
     * @return true if failed, false otherwise
     */
    public boolean isFailed() {
        synchronized (stateLock) {
            return failed;
        }
    }

    /**
     * Indicates whether the shell hit its capture cap and dropped output for this task.
     *
     * <p>
     * Reported as a flag rather than as a notice line in the buffer on purpose. {@link #readNewOutput(String)} applies
     * the caller's regex to every buffered line, so a synthetic "[truncated]" line would be matched, filtered, and
     * counted like real program output — a `filter` of {@code "error"} would silently swallow the very notice that
     * explains why the match set looks short. The renderer appends it outside the filtered region instead.
     *
     * @return true if output was truncated, false otherwise
     */
    public boolean isOutputTruncated() {
        synchronized (stateLock) {
            return outputTruncated;
        }
    }

    /**
     * Returns the environment's notices for this task and clears them, so each is reported once — the same read-once
     * contract as {@link #readNewOutput(String)}. A notice such as "the shell session was recreated" describes the
     * run, not the present, and repeating it on every later poll would read as a new event each time.
     *
     * <p>
     * Kept apart from the output buffer for the reason {@link #isOutputTruncated()} gives: the caller's {@code filter}
     * regex applies to buffered lines and would drop a notice that does not match it.
     *
     * @return the notices not yet taken (never null; empty until the task completes)
     */
    public List<String> takeNotices() {
        synchronized (stateLock) {
            final List<String> taken = notices;
            notices = List.of();
            return taken;
        }
    }
}
