package at.aimon.core.llm.exception;

import java.io.Serial;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown when the requested prompt exceeds the context window of the target model.
 *
 * <p>
 * Providers typically signal this with an HTTP 400 response carrying an error code such as
 * {@code context_length_exceeded}. The exception carries the token counts parsed from the provider error message when
 * available so callers can decide whether to compact the prompt or switch to a model with a larger context window.
 *
 * <p>
 * <strong>When to throw:</strong> LLM provider clients should throw this when the provider explicitly reports a
 * context-length violation.
 *
 * <p>
 * <strong>When to catch:</strong> Prompt-compaction / fallback layers should catch this to trigger conversation
 * summarisation or to escalate to a larger model. This is a <strong>non-transient</strong> failure: retrying the same
 * prompt will fail again.
 */
public class LlmPromptTooLongException extends LlmClientException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Integer requestedTokens;
    private final Integer modelLimitTokens;

    /**
     * Creates a new exception without token metadata and without a cause.
     *
     * @param message
     *            the detail message (must not be null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmPromptTooLongException(String message) {
        this(message, null, null, null);
    }

    /**
     * Creates a new exception without token metadata but with a cause.
     *
     * @param message
     *            the detail message (must not be null)
     * @param cause
     *            the underlying cause (may be null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmPromptTooLongException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    /**
     * Creates a new exception with token metadata but without a cause.
     *
     * @param message
     *            the detail message (must not be null)
     * @param requestedTokens
     *            the number of tokens in the rejected request (may be null when unknown)
     * @param modelLimitTokens
     *            the maximum context length supported by the target model (may be null when unknown)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmPromptTooLongException(String message, Integer requestedTokens, Integer modelLimitTokens) {
        this(message, requestedTokens, modelLimitTokens, null);
    }

    /**
     * Creates a new exception with token metadata and a cause.
     *
     * @param message
     *            the detail message (must not be null)
     * @param requestedTokens
     *            the number of tokens in the rejected request (may be null when unknown)
     * @param modelLimitTokens
     *            the maximum context length supported by the target model (may be null when unknown)
     * @param cause
     *            the underlying cause (may be null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public LlmPromptTooLongException(String message, Integer requestedTokens, Integer modelLimitTokens,
            Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.requestedTokens = requestedTokens;
        this.modelLimitTokens = modelLimitTokens;
    }

    /**
     * Returns the number of tokens the provider reported for the failed request, if known.
     *
     * @return an {@link Optional} containing the requested token count, or an empty {@link Optional} when unknown.
     *         Never {@code null}.
     */
    public Optional<Integer> getRequestedTokens() {
        return Optional.ofNullable(requestedTokens);
    }

    /**
     * Returns the model context-window limit reported by the provider, if known.
     *
     * @return an {@link Optional} containing the model token limit, or an empty {@link Optional} when unknown. Never
     *         {@code null}.
     */
    public Optional<Integer> getModelLimitTokens() {
        return Optional.ofNullable(modelLimitTokens);
    }
}
