package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that the current streaming attempt was discarded and a new attempt will begin.
 *
 * <p>
 * <b>Use when:</b> the underlying {@link at.aimon.core.llm.invoke.LlmCallGateway} decided to retry the LLM call (e.g.,
 * after a 5xx response, a 429 rate-limit, or a fallback-model swap) and the attempt-level buffering mode is
 * <i>direct</i> — meaning partial text from the discarded attempt was already forwarded to the UI and must now be
 * visually rolled back before the next attempt's text starts arriving.
 *
 * <p>
 * Subscribers (notably REPL / UI renderers) should respond by clearing any partial text accumulated from the previous
 * attempt and preparing for a fresh stream. The executor is responsible for also resetting the server-side aggregator
 * so the final assistant message reflects only the successful attempt.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getPreviousAttemptIndex()} — the 0-based attempt index that was discarded
 * <li>{@link #getNextAttemptIndex()} — the 0-based attempt index that will begin next (strictly greater than the
 * previous one)
 * <li>{@link #getReason()} — short human-readable cause (e.g., {@code "5xx_retry"}, {@code "fallback_model"})
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class AssistantTextStreamReset extends AgentExecutionEvent {

    private final int previousAttemptIndex;
    private final int nextAttemptIndex;
    private final String reason;

    private AssistantTextStreamReset(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        if (builder.previousAttemptIndex < 0) {
            throw new IllegalArgumentException(
                    "previousAttemptIndex cannot be negative: " + builder.previousAttemptIndex);
        }
        if (builder.nextAttemptIndex <= builder.previousAttemptIndex) {
            throw new IllegalArgumentException("nextAttemptIndex (" + builder.nextAttemptIndex
                    + ") must be strictly greater than previousAttemptIndex (" + builder.previousAttemptIndex + ")");
        }
        this.previousAttemptIndex = builder.previousAttemptIndex;
        this.nextAttemptIndex = builder.nextAttemptIndex;
        this.reason = Objects.requireNonNull(builder.reason, "reason cannot be null");
        if (this.reason.isEmpty()) {
            throw new IllegalArgumentException("reason cannot be empty");
        }
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
     * Returns the 0-based index of the attempt that was discarded.
     *
     * @return the previous attempt index (always {@code >= 0})
     */
    public int getPreviousAttemptIndex() {
        return previousAttemptIndex;
    }

    /**
     * Returns the 0-based index of the attempt that will begin next.
     *
     * @return the next attempt index (always strictly greater than {@link #getPreviousAttemptIndex()})
     */
    public int getNextAttemptIndex() {
        return nextAttemptIndex;
    }

    /**
     * Returns a short human-readable reason for the discard.
     *
     * @return the reason string (never null, never empty)
     */
    public String getReason() {
        return reason;
    }

    @Override
    protected String eventName() {
        return "AssistantTextStreamReset";
    }

    @Override
    protected String detailString() {
        return "previousAttemptIndex=" + previousAttemptIndex + ", nextAttemptIndex=" + nextAttemptIndex + ", reason='"
                + reason + "'";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AssistantTextStreamReset that = (AssistantTextStreamReset) o;
        return getIteration() == that.getIteration() && previousAttemptIndex == that.previousAttemptIndex
                && nextAttemptIndex == that.nextAttemptIndex && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId()) && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), previousAttemptIndex, nextAttemptIndex,
                reason);
    }

    /** Builder for {@link AssistantTextStreamReset}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private int previousAttemptIndex;
        private int nextAttemptIndex;
        private String reason;

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
         * Sets the 0-based index of the attempt being discarded.
         *
         * @param previousAttemptIndex
         *            the discarded attempt index (must be {@code >= 0})
         * @return this builder
         */
        public Builder previousAttemptIndex(int previousAttemptIndex) {
            this.previousAttemptIndex = previousAttemptIndex;
            return this;
        }

        /**
         * Sets the 0-based index of the upcoming attempt.
         *
         * @param nextAttemptIndex
         *            the upcoming attempt index (must be strictly greater than {@code previousAttemptIndex})
         * @return this builder
         */
        public Builder nextAttemptIndex(int nextAttemptIndex) {
            this.nextAttemptIndex = nextAttemptIndex;
            return this;
        }

        /**
         * Sets the short human-readable reason for the discard.
         *
         * @param reason
         *            the reason (must not be null or empty)
         * @return this builder
         */
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * Builds the {@link AssistantTextStreamReset} event.
         *
         * @return a new {@link AssistantTextStreamReset}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, or {@code reason} is null
         * @throws IllegalArgumentException
         *             if {@code iteration} or {@code previousAttemptIndex} is negative, {@code nextAttemptIndex} is not
         *             strictly greater than {@code previousAttemptIndex}, or {@code reason} is empty
         */
        public AssistantTextStreamReset build() {
            return new AssistantTextStreamReset(this);
        }
    }
}
