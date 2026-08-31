package at.aimon.core.subagent.exception;

/**
 * Exception thrown when a subagent repository operation fails.
 *
 * <p>
 * This exception indicates errors at the storage level, such as:
 *
 * <ul>
 * <li>File system I/O errors
 * <li>Network errors (for remote repositories)
 * <li>Database errors (for database repositories)
 * <li>Permission errors
 * </ul>
 */
public class SubagentRepositoryException extends SubagentException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new SubagentRepositoryException with the given message.
     *
     * @param message
     *            The error message
     */
    public SubagentRepositoryException(String message) {
        super(message);
    }

    /**
     * Creates a new SubagentRepositoryException with the given message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public SubagentRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
