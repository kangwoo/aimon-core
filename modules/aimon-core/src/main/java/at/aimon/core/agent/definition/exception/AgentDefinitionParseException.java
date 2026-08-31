package at.aimon.core.agent.definition.exception;

import java.io.Serial;

import at.aimon.core.agent.exception.AgentException;

/**
 * Exception thrown when an agent definition file cannot be parsed.
 *
 * <p>
 * This exception is thrown when parsing fails due to:
 * <ul>
 * <li>Invalid file format</li>
 * <li>Missing or malformed frontmatter</li>
 * <li>Invalid YAML syntax</li>
 * </ul>
 */
public final class AgentDefinitionParseException extends AgentException {
    @Serial
    private static final long serialVersionUID = -6412803883195891648L;

    /**
     * Constructs a new agent definition parse exception with the specified detail message.
     *
     * @param message
     *            the detail message explaining the parsing error
     */
    public AgentDefinitionParseException(String message) {
        super(message);
    }

    /**
     * Constructs a new agent definition parse exception with the specified detail message and cause.
     *
     * @param message
     *            the detail message explaining the parsing error
     * @param cause
     *            the cause of the parsing error (which is saved for later retrieval by the {@link #getCause()} method)
     */
    public AgentDefinitionParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
