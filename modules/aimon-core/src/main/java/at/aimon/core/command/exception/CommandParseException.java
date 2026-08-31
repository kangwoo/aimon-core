package at.aimon.core.command.exception;

/**
 * Exception thrown when failing to parse a command markdown file or frontmatter.
 *
 * <p>
 * This exception indicates errors during the parsing process, such as:
 *
 * <ul>
 * <li>Invalid YAML frontmatter syntax
 * <li>Malformed markdown structure
 * <li>Missing required frontmatter fields
 * <li>I/O errors while reading the command file
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * try {
 *     parseYaml(frontmatter);
 * } catch (YAMLException e) {
 *     throw new CommandParseException("Failed to parse YAML frontmatter", e);
 * }
 * }
 * </pre>
 */
public class CommandParseException extends CommandException {
    private static final long serialVersionUID = -3724968505707470046L;

    /**
     * Creates a new CommandParseException with the specified message.
     *
     * @param message
     *            The error message describing the parsing failure
     */
    public CommandParseException(String message) {
        super(message);
    }

    /**
     * Creates a new CommandParseException with the specified message and cause.
     *
     * @param message
     *            The error message describing the parsing failure
     * @param cause
     *            The underlying cause of the parsing error
     */
    public CommandParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
