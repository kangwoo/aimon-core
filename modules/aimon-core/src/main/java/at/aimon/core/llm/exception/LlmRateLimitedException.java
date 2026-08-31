package at.aimon.core.llm.exception;

import java.io.Serial;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown when the LLM provider rejects a request because the caller exceeded a rate limit quota (HTTP 429).
 *
 * <p>
 * This exception represents a <strong>transient</strong> failure: callers should back off and retry after the period
 * signalled by {@link #getRetryAfter()}. If the retry-after value is absent, the caller may apply its own backoff
 * policy (exponential backoff with jitter is recommended).
 *
 * <p>
 * <strong>When to throw:</strong> LLM provider clients should throw this when the remote API responds with HTTP 429
 * (Too Many Requests).
 *
 * <p>
 * <strong>When to catch:</strong> Retry / gateway layers should catch this to decide whether to retry. Upper-layer
 * business code typically lets it propagate as {@link LlmClientException}.
 */
public class LlmRateLimitedException extends LlmClientException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    /**
     * Creates a new exception without a {@code Retry-After} value and without a cause.
     *
     * @param message
     *            the detail message (must not be null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmRateLimitedException(String message) {
        this(message, null, null);
    }

    /**
     * Creates a new exception with a cause but without a {@code Retry-After} value.
     *
     * @param message
     *            the detail message (must not be null)
     * @param cause
     *            the underlying cause (may be null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmRateLimitedException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /**
     * Creates a new exception with the {@code Retry-After} duration parsed from the provider response.
     *
     * @param message
     *            the detail message (must not be null)
     * @param retryAfter
     *            the amount of time the caller should wait before retrying (may be null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmRateLimitedException(String message, Duration retryAfter) {
        this(message, retryAfter, null);
    }

    /**
     * Creates a new exception with retry information and an underlying cause.
     *
     * @param message
     *            the detail message (must not be null)
     * @param retryAfter
     *            the amount of time the caller should wait before retrying (may be null)
     * @param cause
     *            the underlying cause (may be null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmRateLimitedException(String message, Duration retryAfter, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the server-suggested wait time before the next retry attempt, if available.
     *
     * @return an {@link Optional} containing the retry-after duration, or an empty {@link Optional} when the provider
     *         did not supply one. Never {@code null}.
     */
    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
