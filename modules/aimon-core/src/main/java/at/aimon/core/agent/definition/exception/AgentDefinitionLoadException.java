package at.aimon.core.agent.definition.exception;

import java.io.Serial;

import at.aimon.core.agent.exception.AgentException;

/**
 * Exception thrown when agent definition loading fails for reasons other than not being found.
 *
 * <p>
 * This exception is typically thrown when an I/O error occurs or the agent definition source is inaccessible.
 */
public final class AgentDefinitionLoadException extends AgentException {
    @Serial
    private static final long serialVersionUID = -8379409400058830706L;

    /**
     * Creates a new AgentDefinitionLoadException with the specified message.
     *
     * @param message
     *            The error message
     */
    public AgentDefinitionLoadException(String message) {
        super(message);
    }

    /**
     * Creates a new AgentDefinitionLoadException with the specified message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public AgentDefinitionLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
