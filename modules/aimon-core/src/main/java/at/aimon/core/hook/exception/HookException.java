package at.aimon.core.hook.exception;

import at.aimon.core.agent.exception.AgentException;

/**
 * Base exception for hook-related errors.
 *
 * <p>
 * All hook-specific exceptions should extend this class.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * throw new HookException("Failed to execute hook");
 * }
 * </pre>
 */
public class HookException extends AgentException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new HookException with the specified message.
     *
     * @param message
     *            The error message
     */
    public HookException(String message) {
        super(message);
    }

    /**
     * Creates a new HookException with the specified message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public HookException(String message, Throwable cause) {
        super(message, cause);
    }
}
