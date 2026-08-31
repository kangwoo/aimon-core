package at.aimon.core.agent.exception;

import at.aimon.core.base.exception.AimonException;

/**
 * Base exception for agent-related errors.
 *
 * <p>
 * All agent-specific exceptions should extend this class.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * throw new AgentException("Failed to execute agent");
 * }
 * </pre>
 */
public class AgentException extends AimonException {

    /**
     * Creates a new AgentException with the specified message.
     *
     * @param message
     *            The error message
     */
    public AgentException(String message) {
        super(message);
    }

    /**
     * Creates a new AgentException with the specified message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
