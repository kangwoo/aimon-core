package at.aimon.core.subagent.exception;

/**
 * Exception thrown when spawning a subagent fails.
 *
 * <p>
 * This exception indicates errors during the subagent spawning phase, such as:
 *
 * <ul>
 * <li>Invalid execution context
 * <li>Resource unavailability
 * <li>Configuration errors
 * </ul>
 */
public class SubagentSpawnException extends SubagentException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new SubagentSpawnException with the given message.
     *
     * @param message
     *            The error message
     */
    public SubagentSpawnException(String message) {
        super(message);
    }

    /**
     * Creates a new SubagentSpawnException with the given message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public SubagentSpawnException(String message, Throwable cause) {
        super(message, cause);
    }
}
