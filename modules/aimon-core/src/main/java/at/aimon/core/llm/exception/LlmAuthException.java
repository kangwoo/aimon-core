package at.aimon.core.llm.exception;

import java.io.Serial;
import java.util.Objects;

/**
 * Thrown when the LLM provider rejects the request due to missing, invalid, or insufficient credentials.
 *
 * <p>
 * Covers HTTP {@code 401 Unauthorized} and {@code 403 Forbidden} responses.
 *
 * <p>
 * <strong>When to throw:</strong> LLM provider clients should throw this when the remote API rejects the API key or
 * reports insufficient permissions.
 *
 * <p>
 * <strong>When to catch:</strong> Configuration / startup validators should catch this to fail fast with an actionable
 * error. This is a <strong>non-transient</strong> failure: retrying with the same credentials will fail again.
 */
public class LlmAuthException extends LlmClientException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception without a cause.
     *
     * @param message
     *            the detail message (must not be null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmAuthException(String message) {
        this(message, null);
    }

    /**
     * Creates a new exception with an underlying cause.
     *
     * @param message
     *            the detail message (must not be null)
     * @param cause
     *            the underlying cause (may be null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmAuthException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
    }
}
