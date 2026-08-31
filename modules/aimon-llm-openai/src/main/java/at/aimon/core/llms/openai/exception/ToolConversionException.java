package at.aimon.core.llms.openai.exception;

/**
 * Exception thrown when tool definition conversion between aimon and OpenAI formats fails.
 *
 * <p>
 * This typically occurs when:
 *
 * <ul>
 * <li>Tool schema is invalid or incompatible
 * <li>JSON schema conversion fails
 * <li>Function parameter serialization fails
 * </ul>
 *
 * <p>
 * Thread-safe and immutable.
 */
public class ToolConversionException extends OpenAIException {
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
