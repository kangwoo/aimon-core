package at.aimon.core.llm.exception;

import java.io.Serial;
import java.util.Objects;

/**
 * Thrown when the LLM provider rejects the request with an HTTP {@code 400 Bad Request} response for a reason other
 * than context-length (see {@link LlmPromptTooLongException}).
 *
 * <p>
 * Typical causes include malformed parameters, unsupported tool schemas, or provider-side validation failures on the
 * request body.
 *
 * <p>
 * <strong>When to throw:</strong> LLM provider clients should throw this for generic client-side (400) errors that are
 * not more specifically represented by another subtype.
 *
 * <p>
 * <strong>When to catch:</strong> Request builders / fallback layers may catch this to surface the validation error to
 * the caller. This is a <strong>non-transient</strong> failure: retrying the same request will fail again.
 */
public class LlmInvalidRequestException extends LlmClientException {
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
    public LlmInvalidRequestException(String message) {
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
    public LlmInvalidRequestException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
    }
}
