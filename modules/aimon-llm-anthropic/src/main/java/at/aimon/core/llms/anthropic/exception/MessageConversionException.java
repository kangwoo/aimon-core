package at.aimon.core.llms.anthropic.exception;

import java.io.Serial;

/**
 * Exception thrown when message conversion between aimon and Anthropic formats fails.
 *
 * <p>
 * This typically occurs when:
 *
 * <ul>
 * <li>Tool use conversion fails due to invalid JSON
 * <li>Message format is incompatible with Anthropic API
 * <li>Role conversion encounters unsupported role types
 * </ul>
 *
 * <p>
 * Thread-safe and immutable.
 */
public class MessageConversionException extends AnthropicException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new MessageConversionException with a message.
     *
     * @param message
     *            The error message
     */
    public MessageConversionException(String message) {
        super(message);
    }

    /**
     * Creates a new MessageConversionException with a message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public MessageConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
