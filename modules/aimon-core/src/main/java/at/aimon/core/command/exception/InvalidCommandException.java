package at.aimon.core.command.exception;

/**
 * Exception thrown when command validation fails.
 *
 * <p>
 * This exception indicates that a command violates validation rules, such as:
 *
 * <ul>
 * <li>Invalid command name format (must be lowercase, numbers, hyphens)
 * <li>Malformed allowed-tools specification
 * <li>Invalid tools pattern syntax
 * <li>Missing required command fields
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * if (!commandName.matches("[a-z0-9-]+")) {
 *     throw new InvalidCommandException("Command name must contain only lowercase, numbers, hyphens: " + commandName);
 * }
 * }
 * </pre>
 */
public class InvalidCommandException extends CommandException {
    private static final long serialVersionUID = -3114923892015064674L;

    /**
     * Creates a new InvalidCommandException with the specified message.
     *
     * @param message
     *            The error message describing the validation failure
     */
    public InvalidCommandException(String message) {
        super(message);
    }
}
