package at.aimon.core.llms.anthropic.exception;

import java.io.Serial;

import at.aimon.core.llm.exception.LlmClientException;

/**
 * Base exception for all Anthropic-related errors in the aimon-llm-anthropic module.
 *
 * <p>
 * This exception extends {@link LlmClientException} to integrate with the unified LLM exception hierarchy.
 *
 * <p>
 * Thread-safe and immutable.
 */
public class AnthropicException extends LlmClientException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new AnthropicException with a message.
     *
     * @param message
     *            The error message
     */
    public AnthropicException(String message) {
        super(message);
    }

    /**
     * Creates a new AnthropicException with a message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public AnthropicException(String message, Throwable cause) {
        super(message, cause);
    }
}
