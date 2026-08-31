package at.aimon.core.llm.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Exception thrown when LLM API communication fails.
 *
 * <p>
 * This exception indicates errors during communication with the LLM provider, such as:
 *
 * <ul>
 * <li>Network connectivity issues
 * <li>API authentication failures
 * <li>Invalid API responses
 * <li>Rate limiting or quota exceeded
 * <li>LLM API errors (4xx, 5xx status codes)
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * try {
 *     response = client.sendMessage(systemPrompt, messages, tools);
 * } catch (Exception e) {
 *     throw new LlmClientException("Failed to communicate with LLM API", e);
 * }
 * }
 * </pre>
 */
public class LlmClientException extends AimonException {
    @Serial
    private static final long serialVersionUID = 6857605397238573021L;

    /**
     * Creates a new LlmClientException with the specified message.
     *
     * @param message
     *            The error message describing the API communication failure
     */
    public LlmClientException(String message) {
        super(message);
    }

    /**
     * Creates a new LlmClientException with the specified message and cause.
     *
     * <p>
     * The {@code cause} may be {@code null} when the failure is detected without a wrapped exception (e.g. a synthetic
     * error created from an HTTP response body).
     *
     * @param message
     *            The error message describing the API communication failure (must not be null)
     * @param cause
     *            The underlying cause of the API error (may be null)
     */
    public LlmClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
