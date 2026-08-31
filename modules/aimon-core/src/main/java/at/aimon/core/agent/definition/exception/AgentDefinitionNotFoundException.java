package at.aimon.core.agent.definition.exception;

import java.io.Serial;

import at.aimon.core.agent.exception.AgentException;

/**
 * Exception thrown when an agent definition cannot be found.
 *
 * <p>
 * This exception is typically thrown when the requested agent definition does not exist in the configured source.
 */
public final class AgentDefinitionNotFoundException extends AgentException {
    @Serial
    private static final long serialVersionUID = -7345187857985239190L;

    /**
     * Creates a new AgentDefinitionNotFoundException with the specified message.
     *
     * @param message
     *            The error message
     */
    public AgentDefinitionNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a new AgentDefinitionNotFoundException with the specified message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public AgentDefinitionNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
