package at.aimon.core.shell.exception;

import java.io.Serial;
import java.util.List;

/**
 * Exception thrown when a shell command was stopped through its
 * {@link at.aimon.core.shell.ExecutionOptions#getCancellation() cancellation signal}.
 *
 * <p>
 * Like a timeout, it carries whatever the command had printed before it was stopped — see {@link #stdout()} on
 * {@link ShellExecutionException}. A command that was cancelled before it started carries none.
 */
public final class ShellCancelledException extends ShellExecutionException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new shell cancelled exception for a command that produced no output.
     *
     * @param message
     *            the error message
     */
    public ShellCancelledException(String message) {
        this(message, "", "", false, null);
    }

    /**
     * Creates a new shell cancelled exception.
     *
     * @param message
     *            the error message
     * @param stdout
     *            the partial standard output produced before cancellation, null will be converted to empty string
     * @param stderr
     *            the partial standard error output produced before cancellation, null will be converted to empty
     *            string
     * @param outputTruncated
     *            true if even the captured partial output may itself be incomplete; see {@link #outputTruncated()}
     */
    public ShellCancelledException(String message, String stdout, String stderr, boolean outputTruncated) {
        this(message, stdout, stderr, outputTruncated, null);
    }

    /**
     * Creates a new shell cancelled exception that carries the environment's notices.
     *
     * @param message
     *            the error message
     * @param stdout
     *            the partial standard output produced before cancellation, null will be converted to empty string
     * @param stderr
     *            the partial standard error output produced before cancellation, null will be converted to empty
     *            string
     * @param outputTruncated
     *            true if even the captured partial output may itself be incomplete; see {@link #outputTruncated()}
     * @param notices
     *            facts the environment tells the model, see {@link #notices()} (null means none)
     */
    public ShellCancelledException(String message, String stdout, String stderr, boolean outputTruncated,
            List<String> notices) {
        super(message, stdout, stderr, outputTruncated, notices);
    }
}
