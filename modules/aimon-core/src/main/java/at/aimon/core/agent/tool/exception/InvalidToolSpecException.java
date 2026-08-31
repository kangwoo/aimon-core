package at.aimon.core.agent.tool.exception;

import java.io.Serial;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.ToolPattern;
import at.aimon.core.base.exception.AimonException;

/**
 * Exception thrown when a tool specification string is malformed or invalid.
 *
 * <p>
 * This exception indicates that an attempt was made to parse a tool specification string that does not conform to the
 * expected format. Tool specifications define allowed tools with optional patterns for permission validation.
 *
 * <p>
 * <b>Common Invalid Specification Scenarios:</b>
 *
 * <ul>
 * <li><b>Missing Closing Parenthesis:</b> {@code "Bash(git add:*"} - pattern not properly closed
 * <li><b>Empty Pattern:</b> {@code "Bash()"} - pattern is empty
 * <li><b>Invalid Format:</b> Malformed specification that cannot be parsed
 * </ul>
 *
 * <p>
 * <b>Valid Specification Examples:</b>
 *
 * <ul>
 * <li>{@code "Read"} - Simple tool without pattern
 * <li>{@code "Bash(git add:*)"} - Tool with wildcard pattern
 * <li>{@code "Bash(npm install)"} - Tool with exact pattern
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         AllowedTool tool = AllowedTool.parse("Bash(git:*");
 *     } catch (InvalidToolSpecException e) {
 *         logger.error("Invalid tool spec: {}", e.getMessage());
 *         // Error: "Malformed tool spec: missing closing parenthesis: Bash(git:*"
 *     }
 * }
 * </pre>
 *
 * <p>
 * <b>Design Notes:</b>
 *
 * <ul>
 * <li>Extends {@link AimonException} for consistency with exception hierarchy
 * <li>Provides detailed error messages for debugging
 * <li>Used by {@link AllowedTool#parse(String)}
 * </ul>
 *
 * @see AllowedTool
 * @see ToolPattern
 */
public class InvalidToolSpecException extends AimonException {

    @Serial
    private static final long serialVersionUID = 8838215409061029896L;

    /**
     * Creates a new InvalidToolSpecException with the specified detail message.
     *
     * @param message
     *            The detail message explaining why the tool specification is invalid
     */
    public InvalidToolSpecException(String message) {
        super(message);
    }

    /**
     * Creates a new InvalidToolSpecException with the specified detail message and cause.
     *
     * @param message
     *            The detail message explaining why the tool specification is invalid
     * @param cause
     *            The underlying cause of the exception
     */
    public InvalidToolSpecException(String message, Throwable cause) {
        super(message, cause);
    }
}
