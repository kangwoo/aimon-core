package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.llm.TokenUsage;

/**
 * Signals that the current streaming attempt has finished — either because the provider closed the stream normally, or
 * because the executor cancelled the stream early (for example due to a user interrupt).
 *
 * <p>
 * <b>Use when:</b> the streaming sink has observed the terminal
 * {@link at.aimon.core.llm.streaming.LlmStreamChunk.Kind#STREAM_END STREAM_END} chunk, or the executor aborted the
 * stream and wants subscribers to flush any trailing state (trim newlines, finalize progress indicators, etc.).
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getTotalLength()} — final length of the accumulated assistant text for this attempt
 * <li>{@link #getTokenUsage()} — token-usage breakdown when reported by the provider; never-null {@link Optional}
 * <li>{@link #getFinishReason()} — provider-reported finish reason (e.g., {@code "stop"}, {@code "length"},
 * {@code "tool_calls"}) or the synthetic {@code "interrupted"} marker when the executor cancelled the stream;
 * never-null
 * {@link Optional}
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class AssistantTextStreamCompleted extends AgentExecutionEvent {

    private final int totalLength;
    private final Optional<TokenUsage> tokenUsage;
    private final Optional<String> finishReason;

    private AssistantTextStreamCompleted(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        if (builder.totalLength < 0) {
            throw new IllegalArgumentException("totalLength cannot be negative: " + builder.totalLength);
        }
        this.totalLength = builder.totalLength;
        this.tokenUsage = Objects.requireNonNull(builder.tokenUsage, "tokenUsage Optional cannot be null");
        this.finishReason = Objects.requireNonNull(builder.finishReason, "finishReason Optional cannot be null");
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the final length of the accumulated assistant text (character count) for this attempt.
     *
     * @return the total length (always {@code >= 0})
     */
    public int getTotalLength() {
        return totalLength;
    }

    /**
     * Returns the token-usage breakdown for the completed streaming call.
     *
     * @return {@link Optional} of {@link TokenUsage}; empty when the provider did not report usage (never null)
     */
    public Optional<TokenUsage> getTokenUsage() {
        return tokenUsage;
    }

    /**
     * Returns the provider-reported finish reason.
     *
     * @return {@link Optional} of finish reason; empty when the provider did not report one (never null)
     */
    public Optional<String> getFinishReason() {
        return finishReason;
    }

    @Override
    protected String eventName() {
        return "AssistantTextStreamCompleted";
    }

    @Override
    protected String detailString() {
        return "totalLength=" + totalLength + ", tokenUsage=" + tokenUsage + ", finishReason=" + finishReason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AssistantTextStreamCompleted that = (AssistantTextStreamCompleted) o;
        return getIteration() == that.getIteration() && totalLength == that.totalLength
                && getTimestamp().equals(that.getTimestamp()) && getAgentRuntimeId().equals(that.getAgentRuntimeId())
                && tokenUsage.equals(that.tokenUsage) && finishReason.equals(that.finishReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), totalLength, tokenUsage, finishReason);
    }

    /** Builder for {@link AssistantTextStreamCompleted}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private int totalLength;
        private Optional<TokenUsage> tokenUsage = Optional.empty();
        private Optional<String> finishReason = Optional.empty();

        private Builder() {
        }

        /**
         * Sets the event timestamp.
         *
         * @param timestamp
         *            the wall-clock instant (must not be null)
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the agent runtime identifier.
         *
         * @param agentRuntimeId
         *            the agent runtime identifier (must not be null)
         * @return this builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * Sets the 1-based iteration number.
         *
         * @param iteration
         *            the iteration number (must be {@code >= 0})
         * @return this builder
         */
        public Builder iteration(int iteration) {
            this.iteration = iteration;
            return this;
        }

        /**
         * Sets the final accumulated text length.
         *
         * @param totalLength
         *            the text length (must be {@code >= 0})
         * @return this builder
         */
        public Builder totalLength(int totalLength) {
            this.totalLength = totalLength;
            return this;
        }

        /**
         * Sets the token usage (nullable — null becomes {@link Optional#empty()}).
         *
         * @param tokenUsage
         *            the token usage, or {@code null} to clear
         * @return this builder
         */
        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = Optional.ofNullable(tokenUsage);
            return this;
        }

        /**
         * Sets the finish reason (nullable — null becomes {@link Optional#empty()}).
         *
         * @param finishReason
         *            the finish reason, or {@code null} to clear
         * @return this builder
         */
        public Builder finishReason(String finishReason) {
            this.finishReason = Optional.ofNullable(finishReason);
            return this;
        }

        /**
         * Builds the {@link AssistantTextStreamCompleted} event.
         *
         * @return a new {@link AssistantTextStreamCompleted}
         * @throws NullPointerException
         *             if {@code timestamp} or {@code agentRuntimeId} is null
         * @throws IllegalArgumentException
         *             if {@code iteration} or {@code totalLength} is negative
         */
        public AssistantTextStreamCompleted build() {
            return new AssistantTextStreamCompleted(this);
        }
    }
}
