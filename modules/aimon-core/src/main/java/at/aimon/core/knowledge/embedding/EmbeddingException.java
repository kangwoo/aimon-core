package at.aimon.core.knowledge.embedding;

import at.aimon.core.base.exception.AimonException;

/**
 * Exception thrown when an embedding operation fails.
 *
 * <p>
 * This exception wraps provider-specific errors (e.g., API failures, rate limits, invalid input) into a framework
 * exception, preventing provider SDK types from leaking across module boundaries.
 *
 * @see EmbeddingClient
 */
public class EmbeddingException extends AimonException {

    /**
     * Creates an embedding exception with a message.
     *
     * @param message
     *            the error message
     */
    public EmbeddingException(String message) {
        super(message);
    }

    /**
     * Creates an embedding exception with a message and cause.
     *
     * @param message
     *            the error message
     * @param cause
     *            the underlying cause
     */
    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
