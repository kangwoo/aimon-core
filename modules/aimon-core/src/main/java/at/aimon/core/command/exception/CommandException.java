package at.aimon.core.command.exception;

import at.aimon.core.base.exception.AimonException;

/**
 * Base exception for all command-related errors.
 *
 * <p>
 * This exception extends {@link AimonException} and serves as the root of the command exception hierarchy. All
 * command-specific exceptions should extend this class.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * throw new CommandException("Failed to execute command");
 * }
 * </pre>
 *
 * @see AimonException
 */
public class CommandException extends AimonException {
    private static final long serialVersionUID = 89153044057702179L;

    /**
     * Creates a new CommandException with the specified message.
     *
     * @param message
     *            The error message
     */
    public CommandException(String message) {
        super(message);
    }

    /**
     * Creates a new CommandException with the specified message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause of the exception
     */
    public CommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
