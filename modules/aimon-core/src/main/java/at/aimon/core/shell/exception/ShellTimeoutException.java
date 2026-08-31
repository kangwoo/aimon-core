package at.aimon.core.shell.exception;

import java.io.Serial;
import java.time.Duration;

/**
 * Exception thrown when a shell command execution exceeds the configured timeout.
 *
 * <p>
 * This exception captures partial output (stdout and stderr) that was produced before the timeout occurred, allowing
 * callers to inspect what the command had produced before being terminated. Those accessors live on
 * {@link ShellExecutionException} — timeout is not the only termination path that can carry partial output.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * try {
 *     ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(5)).build();
 *     shell.execute(longRunningCommand, options);
 * } catch (ShellTimeoutException e) {
 *     System.err.println("Command timed out after " + e.timeout());
 *     System.err.println("Partial output: " + e.stdout());
 * }
 * }
 * </pre>
 */
public final class ShellTimeoutException extends ShellExecutionException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Duration timeout;

    /**
     * Creates a new shell timeout exception whose captured partial output is known to be complete up to the point of
     * termination.
     *
     * <p>
     * Equivalent to {@link #ShellTimeoutException(String, Duration, String, String, boolean)} with
     * {@code outputTruncated = false}.
     *
     * @param message
     *            the error message
     * @param timeout
     *            the timeout duration that was exceeded
     * @param stdout
     *            the partial standard output produced before timeout, null will be converted to empty string
     * @param stderr
     *            the partial standard error output produced before timeout, null will be converted to empty string
     */
    public ShellTimeoutException(String message, Duration timeout, String stdout, String stderr) {
        this(message, timeout, stdout, stderr, false);
    }

    /**
     * Creates a new shell timeout exception.
     *
     * @param message
     *            the error message
     * @param timeout
     *            the timeout duration that was exceeded
     * @param stdout
     *            the partial standard output produced before timeout, null will be converted to empty string
     * @param stderr
     *            the partial standard error output produced before timeout, null will be converted to empty string
     * @param outputTruncated
     *            true if even the captured partial output may itself be incomplete because it exceeded the capture
     *            limit
     *            or the captured output could not be fully read; see {@link #outputTruncated()}
     */
    public ShellTimeoutException(String message, Duration timeout, String stdout, String stderr,
            boolean outputTruncated) {
        super(message, stdout, stderr, outputTruncated);
        this.timeout = timeout;
    }

    /**
     * Returns the timeout duration that was exceeded.
     *
     * @return the timeout duration
     */
    public Duration timeout() {
        return timeout;
    }
}
