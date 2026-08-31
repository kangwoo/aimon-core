package at.aimon.core.llm.exception;

import java.io.Serial;
import java.util.Objects;

/**
 * Thrown when the LLM provider reports that it is temporarily unable to serve the request because the backend is
 * overloaded or experiencing an outage.
 *
 * <p>
 * Typical triggers include HTTP {@code 500}, {@code 502}, {@code 503}, {@code 504} responses or an explicit
 * {@code overloaded} marker in the provider response body.
 *
 * <p>
 * <strong>When to throw:</strong> LLM provider clients should throw this for transient server-side failures.
 *
 * <p>
 * <strong>When to catch:</strong> Retry / gateway layers should catch this and apply a backoff policy. This is a
 * <strong>transient</strong> failure; the request may succeed on a subsequent attempt.
 */
public class LlmOverloadedException extends LlmClientException {
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
    public LlmOverloadedException(String message) {
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
    public LlmOverloadedException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
    }
}
