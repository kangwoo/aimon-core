package at.aimon.core.llm.invoke;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.exception.LlmPromptTooLongException;

/**
 * Immutable event describing a {@link LlmPromptTooLongException} captured by {@link LlmCallGateway} and handed off to a
 * {@link PromptTooLongHandler}.
 *
 * <p>
 * The event carries everything a handler needs to decide whether (and how) to compact the conversation before asking
 * the gateway to retry:
 * <ul>
 * <li>The triggering {@link LlmPromptTooLongException} (including any token metadata the provider supplied).
 * <li>A snapshot reference to the caller's transcript buffer. The type is a generic parameter {@code M} so that the
 * LLM core package does not need to depend on the higher-level transcript package. Typical callers bind {@code M} to
 * {@code TranscriptBuffer} or an equivalent aggregate, but {@code M} may also be {@link Void} when no memory is
 * relevant.
 * <li>The {@link LlmModel} configuration that produced the failure — useful when the handler's compaction strategy
 * depends on the target model's context window.
 * <li>The 1-based attempt number of the failing call within the current gateway invocation.
 * </ul>
 *
 * <p>
 * This class is immutable and thread-safe.
 *
 * @param <M>
 *            the transcript-buffer type the handler operates on. May be any reference type, or {@link Void} when no
 *            memory is used.
 */
public final class PromptTooLongEvent<M> {

    private final LlmPromptTooLongException exception;
    private final M memorySnapshot;
    private final LlmModel model;
    private final int attempt;

    private PromptTooLongEvent(Builder<M> builder) {
        this.exception = Objects.requireNonNull(builder.exception, "exception must not be null");
        this.model = Objects.requireNonNull(builder.model, "model must not be null");
        this.memorySnapshot = builder.memorySnapshot;
        if (builder.attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, got: " + builder.attempt);
        }
        this.attempt = builder.attempt;
    }

    /**
     * Creates a new builder.
     *
     * @param <M>
     *            the transcript-buffer type
     * @return a new {@link Builder} (never {@code null})
     */
    public static <M> Builder<M> builder() {
        return new Builder<>();
    }

    /**
     * Returns the triggering exception.
     *
     * @return the captured {@link LlmPromptTooLongException} (never {@code null})
     */
    public LlmPromptTooLongException getException() {
        return exception;
    }

    /**
     * Returns the transcript-buffer snapshot associated with the failing call, if any was supplied.
     *
     * @return an {@link Optional} carrying the memory reference, or {@link Optional#empty()} when no memory was
     *         associated with the gateway call. Never {@code null}.
     */
    public Optional<M> getMemorySnapshot() {
        return Optional.ofNullable(memorySnapshot);
    }

    /**
     * Returns the {@link LlmModel} configuration that produced the failure.
     *
     * @return the model configuration (never {@code null})
     */
    public LlmModel getModel() {
        return model;
    }

    /**
     * Returns the 1-based attempt number of the failing call within the current gateway invocation.
     *
     * <p>
     * Attempts are counted per-model: switching models via fallback resets the counter to {@code 1}. A handler-driven
     * retry does <strong>not</strong> advance this counter, because the handler is expected to change the request
     * semantics (typically by shrinking memory) rather than to retry the unchanged request.
     *
     * @return a positive integer
     */
    public int getAttempt() {
        return attempt;
    }

    @Override
    public String toString() {
        return "PromptTooLongEvent{" + "exception=" + exception.getClass().getSimpleName() + ", model=" + model
                + ", attempt=" + attempt + ", memoryPresent=" + (memorySnapshot != null) + '}';
    }

    /**
     * Mutable builder for {@link PromptTooLongEvent}. Not thread-safe.
     *
     * @param <M>
     *            the transcript-buffer type
     */
    public static final class Builder<M> {
        private LlmPromptTooLongException exception;
        private M memorySnapshot;
        private LlmModel model;
        private int attempt = 1;

        private Builder() {
        }

        /**
         * Sets the triggering exception.
         *
         * @param exception
         *            the captured exception (must not be {@code null})
         * @return this builder
         */
        public Builder<M> exception(LlmPromptTooLongException exception) {
            this.exception = Objects.requireNonNull(exception, "exception must not be null");
            return this;
        }

        /**
         * Sets the transcript-buffer snapshot associated with the failing call.
         *
         * @param memorySnapshot
         *            the memory snapshot (may be {@code null})
         * @return this builder
         */
        public Builder<M> memorySnapshot(M memorySnapshot) {
            this.memorySnapshot = memorySnapshot;
            return this;
        }

        /**
         * Sets the model configuration that produced the failure.
         *
         * @param model
         *            the model configuration (must not be {@code null})
         * @return this builder
         */
        public Builder<M> model(LlmModel model) {
            this.model = Objects.requireNonNull(model, "model must not be null");
            return this;
        }

        /**
         * Sets the 1-based attempt number.
         *
         * @param attempt
         *            the attempt number (must be {@code >= 1})
         * @return this builder
         */
        public Builder<M> attempt(int attempt) {
            this.attempt = attempt;
            return this;
        }

        /**
         * Builds the {@link PromptTooLongEvent}.
         *
         * @return an immutable event (never {@code null})
         */
        public PromptTooLongEvent<M> build() {
            return new PromptTooLongEvent<>(this);
        }
    }
}
