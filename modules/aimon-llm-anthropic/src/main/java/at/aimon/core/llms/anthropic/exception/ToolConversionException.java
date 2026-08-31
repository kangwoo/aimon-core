package at.aimon.core.llms.anthropic.exception;

import java.io.Serial;

/**
 * Exception thrown when tool definition conversion between aimon and Anthropic formats fails.
 *
 * <p>
 * This typically occurs when:
 *
 * <ul>
 * <li>Tool schema is invalid or incompatible
 * <li>JSON schema conversion fails
 * <li>Input schema serialization fails
 * </ul>
 *
 * <p>
 * Thread-safe and immutable.
 */
public class ToolConversionException extends AnthropicException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new ToolConversionException with a message.
     *
     * @param message
     *            The error message
     */
    public ToolConversionException(String message) {
        super(message);
    }

    /**
     * Creates a new ToolConversionException with a message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public ToolConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
