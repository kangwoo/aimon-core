package at.aimon.core.shell.exception;

import java.io.Serial;
import java.util.List;

import at.aimon.core.shell.ShellCommandResult;

/**
 * Base exception for shell command execution failures.
 *
 * <p>
 * This exception and its subclasses are thrown when shell command execution encounters errors such as process startup
 * failures, timeouts, or other execution issues.
 *
 * <p>
 * Note that this exception is NOT thrown for commands that execute successfully but return non-zero exit codes. Use
 * {@link ShellCommandResult#isFailure()} to check for command failures.
 *
 * <p>
 * <b>Partial output:</b> a termination path that kills a process which had already written something carries that
 * output on the exception rather than dropping it — see {@link #stdout()}. Paths that fail before any output exists
 * (process startup failure, I/O redirection failure) simply leave it empty.
 */
public class ShellExecutionException extends Exception {
    @Serial
    private static final long serialVersionUID = 913379336136242601L;

    private final String stdout;
    private final String stderr;
    private final boolean outputTruncated;
    private final List<String> notices;

    /**
     * Creates a new shell execution exception with the specified message and no captured output.
     *
     * @param message
     *            the error message
     */
    public ShellExecutionException(String message) {
        super(message);
        this.stdout = "";
        this.stderr = "";
        this.outputTruncated = false;
        this.notices = List.of();
    }

    /**
     * Creates a new shell execution exception with the specified message and cause, and no captured output.
     *
     * @param message
     *            the error message
     * @param cause
     *            the underlying cause
     */
    public ShellExecutionException(String message, Throwable cause) {
        super(message, cause);
        this.stdout = "";
        this.stderr = "";
        this.outputTruncated = false;
        this.notices = List.of();
    }

    /**
     * Creates a new shell execution exception carrying the partial output the process had produced before it was
     * terminated.
     *
     * @param message
     *            the error message
     * @param cause
     *            the underlying cause, or null
     * @param stdout
     *            the partial standard output, null will be converted to empty string
     * @param stderr
     *            the partial standard error output, null will be converted to empty string
     * @param outputTruncated
     *            true if the captured partial output may itself be incomplete; see {@link #outputTruncated()}
     */
    public ShellExecutionException(String message, Throwable cause, String stdout, String stderr,
            boolean outputTruncated) {
        this(message, cause, stdout, stderr, outputTruncated, null);
    }

    /**
     * Creates a new shell execution exception carrying partial output and the environment's notices.
     *
     * @param message
     *            the error message
     * @param cause
     *            the underlying cause, or null
     * @param stdout
     *            the partial standard output, null will be converted to empty string
     * @param stderr
     *            the partial standard error output, null will be converted to empty string
     * @param outputTruncated
     *            true if the captured partial output may itself be incomplete; see {@link #outputTruncated()}
     * @param notices
     *            facts the environment tells the model, see {@link #notices()} (null means none)
     */
    public ShellExecutionException(String message, Throwable cause, String stdout, String stderr,
            boolean outputTruncated, List<String> notices) {
        super(message, cause);
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.outputTruncated = outputTruncated;
        this.notices = notices == null ? List.of() : List.copyOf(notices);
    }

    /**
     * Creates a new shell execution exception carrying partial output but no cause.
     *
     * @param message
     *            the error message
     * @param stdout
     *            the partial standard output, null will be converted to empty string
     * @param stderr
     *            the partial standard error output, null will be converted to empty string
     * @param outputTruncated
     *            true if the captured partial output may itself be incomplete; see {@link #outputTruncated()}
     */
    protected ShellExecutionException(String message, String stdout, String stderr, boolean outputTruncated) {
        this(message, stdout, stderr, outputTruncated, null);
    }

    /**
     * Creates a new shell execution exception carrying partial output and the environment's notices, but no cause.
     *
     * @param message
     *            the error message
     * @param stdout
     *            the partial standard output, null will be converted to empty string
     * @param stderr
     *            the partial standard error output, null will be converted to empty string
     * @param outputTruncated
     *            true if the captured partial output may itself be incomplete; see {@link #outputTruncated()}
     * @param notices
     *            facts the environment tells the model, see {@link #notices()} (null means none)
     */
    protected ShellExecutionException(String message, String stdout, String stderr, boolean outputTruncated,
            List<String> notices) {
        super(message);
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.outputTruncated = outputTruncated;
        this.notices = notices == null ? List.of() : List.copyOf(notices);
    }

    /**
     * Returns the partial standard output produced before the process was terminated.
     *
     * @return the stdout content, never null (empty string if no output was captured)
     */
    public String stdout() {
        return stdout;
    }

    /**
     * Returns the partial standard error output produced before the process was terminated.
     *
     * @return the stderr content, never null (empty string if no output was captured)
     */
    public String stderr() {
        return stderr;
    }

    /**
     * Indicates whether the captured partial output may itself be incomplete.
     *
     * <p>
     * When {@code true}, the captured (already partial) stdout/stderr may itself be incomplete — the output flushed
     * before termination exceeded the capture limit, or the capture file could not be fully read. A caller can use this
     * to distinguish "the command produced no output before it was killed" from "output was dropped".
     *
     * @return true if the partial stdout/stderr may be truncated, false otherwise
     */
    public boolean outputTruncated() {
        return outputTruncated;
    }

    /**
     * Returns the facts the environment tells the model about this call, the same as
     * {@link ShellCommandResult#notices()} on a result — for instance that the shell session was
     * recreated before the command ran, so the working directory is back at its start. A command that failed or timed
     * out may still have run in a changed environment, and the model needs to know that as much as after a success.
     *
     * @return the notices (never null; empty by default)
     */
    public List<String> notices() {
        return notices;
    }
}
