package at.aimon.core.subagent.parser;

/**
 * Exception thrown when parsing a subagent file fails.
 *
 * <p>
 * This exception indicates errors during the parsing phase, such as:
 *
 * <ul>
 * <li>Invalid YAML frontmatter
 * <li>Missing required fields
 * <li>Invalid field values
 * <li>Malformed markdown content
 * </ul>
 */
public class SubagentParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new SubagentParseException with the given message.
     *
     * @param message
     *            The error message
     */
    public SubagentParseException(String message) {
        super(message);
    }

    /**
     * Creates a new SubagentParseException with the given message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public SubagentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
