package at.aimon.core.shell;

import java.time.Duration;
import java.util.Objects;

/**
 * Represents the result of a shell command execution.
 *
 * <p>
 * This is an immutable value object that captures the complete outcome of a command execution including exit code,
 * standard output, standard error, and execution duration.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     ShellCommandResult result = shell.execute(command);
 *     if (result.isSuccess()) {
 *         System.out.println("Command succeeded: " + result.stdout());
 *     } else {
 *         System.err.println("Command failed with exit code: " + result.exitCode());
 *         System.err.println("Error output: " + result.stderr());
 *     }
 * }
 * </pre>
 */
public final class ShellCommandResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final Duration duration;
    private final boolean outputTruncated;

    /**
     * Creates a new shell command result whose captured output is complete.
     *
     * <p>
     * Equivalent to {@link #ShellCommandResult(int, String, String, Duration, boolean)} with
     * {@code outputTruncated = false}.
     *
     * @param exitCode
     *            the exit code of the command (0 typically indicates success)
     * @param stdout
     *            the standard output, null will be converted to empty string
     * @param stderr
     *            the standard error output, null will be converted to empty string
     * @param duration
     *            the execution duration, must not be null
     * @throws NullPointerException
     *             if duration is null
     */
    public ShellCommandResult(int exitCode, String stdout, String stderr, Duration duration) {
        this(exitCode, stdout, stderr, duration, false);
    }

    /**
     * Creates a new shell command result.
     *
     * @param exitCode
     *            the exit code of the command (0 typically indicates success)
     * @param stdout
     *            the standard output, null will be converted to empty string
     * @param stderr
     *            the standard error output, null will be converted to empty string
     * @param duration
     *            the execution duration, must not be null
     * @param outputTruncated
     *            true if the captured stdout/stderr may be incomplete because draining the process
     *            output did not reach EOF in time (e.g. a backgrounded child kept the pipe open); see
     *            {@link #outputTruncated()}
     * @throws NullPointerException
     *             if duration is null
     */
    public ShellCommandResult(int exitCode, String stdout, String stderr, Duration duration, boolean outputTruncated) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.duration = Objects.requireNonNull(duration, "duration");
        this.outputTruncated = outputTruncated;
    }

    /**
     * Returns the exit code of the command.
     *
     * @return the exit code (0 typically indicates success)
     */
    public int exitCode() {
        return exitCode;
    }

    /**
     * Returns the standard output of the command.
     *
     * @return the stdout content, never null (empty string if no output)
     */
    public String stdout() {
        return stdout;
    }

    /**
     * Returns the standard error output of the command.
     *
     * @return the stderr content, never null (empty string if no error output)
     */
    public String stderr() {
        return stderr;
    }

    /**
     * Returns the execution duration of the command.
     *
     * @return the duration, never null
     */
    public Duration duration() {
        return duration;
    }

    /**
     * Indicates whether the captured output may be incomplete.
     *
     * <p>
     * When {@code true}, draining the process's stdout/stderr did not reach EOF within the drain
     * timeout, so {@link #stdout()} / {@link #stderr()} hold only what had been read so far. This
     * typically happens when the command leaves a backgrounded child that inherited the write-end of
     * the pipe (e.g. {@code foo & echo done}): the parent exits and the exit code is valid, but EOF
     * on the pipe is delayed until the child dies. Callers can use this flag to distinguish "the
     * command produced no output" from "output was dropped".
     *
     * @return true if the captured stdout/stderr may be truncated, false if it is complete
     */
    public boolean outputTruncated() {
        return outputTruncated;
    }

    /**
     * Checks if the command execution was successful.
     *
     * @return true if exit code is 0, false otherwise
     */
    public boolean isSuccess() {
        return exitCode == 0;
    }

    /**
     * Checks if the command execution failed.
     *
     * @return true if exit code is non-zero, false otherwise
     */
    public boolean isFailure() {
        return !isSuccess();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ShellCommandResult that = (ShellCommandResult) o;
        return exitCode == that.exitCode && outputTruncated == that.outputTruncated
                && Objects.equals(stdout, that.stdout) && Objects.equals(stderr, that.stderr)
                && Objects.equals(duration, that.duration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exitCode, stdout, stderr, duration, outputTruncated);
    }

    @Override
    public String toString() {
        return "ShellCommandResult{" + "exitCode=" + exitCode + ", stdout.length=" + stdout.length()
                + ", stderr.length=" + stderr.length() + ", duration=" + duration + ", outputTruncated="
                + outputTruncated + '}';
    }
}
