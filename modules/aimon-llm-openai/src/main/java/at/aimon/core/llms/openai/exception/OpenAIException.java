package at.aimon.core.llms.openai.exception;

/**
 * Base exception for all OpenAI-related errors in the aimon-llm-openai module.
 *
 * <p>
 * This is an unchecked exception that serves as the parent for all module-specific exceptions.
 *
 * <p>
 * Thread-safe and immutable.
 */
public class OpenAIException extends RuntimeException {
    /**
     * Creates a new OpenAIException with a message.
     *
     * @param message
     *            The error message
     */
    public OpenAIException(String message) {
        super(message);
    }

    /**
     * Creates a new OpenAIException with a message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public OpenAIException(String message, Throwable cause) {
        super(message, cause);
    }
}
