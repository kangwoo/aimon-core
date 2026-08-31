package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that a partial text fragment was received from the LLM as part of a streaming assistant response.
 *
 * <p>
 * <b>Use when:</b> the executor is operating in streaming mode and the provider emitted a new text-delta chunk.
 * Subscribers (notably REPL / UI renderers) append {@link #getDelta()} to their accumulator to show text to the user
 * progressively, yielding a Time-To-First-Token benefit over the non-streaming path.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getDelta()} — the newly added text fragment; guaranteed non-empty
 * <li>{@link #getChunkIndex()} — provider chunk ordinal (0-based) within the current streaming attempt
 * </ul>
 *
 * <p>
 * This event carries only the <i>incremental</i> text for the current chunk. No cumulative metadata (e.g.,
 * {@code cumulativeLength}) is attached — subscribers maintain their own accumulator and can derive totals themselves.
 *
 * <p>
 * Ordering contract: within a single streaming attempt, {@code chunkIndex} values are strictly monotonically
 * increasing starting at 0. An {@link AssistantTextStreamReset} event resets the ordering for the next attempt.
 *
 * <p>
 * Immutable value object.
 */
public final class AssistantTextDelta extends AgentExecutionEvent {

    private final String delta;
    private final int chunkIndex;

    private AssistantTextDelta(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.delta = Objects.requireNonNull(builder.delta, "delta cannot be null");
        if (this.delta.isEmpty()) {
            throw new IllegalArgumentException("delta cannot be empty");
        }
        if (builder.chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex cannot be negative: " + builder.chunkIndex);
        }
        this.chunkIndex = builder.chunkIndex;
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
     * Returns the incremental text fragment carried by this chunk.
     *
     * @return the delta string (never null, never empty)
     */
    public String getDelta() {
        return delta;
    }

    /**
     * Returns the provider-assigned chunk ordinal (0-based) within the current streaming attempt.
     *
     * @return the chunk index (always {@code >= 0})
     */
    public int getChunkIndex() {
        return chunkIndex;
    }

    @Override
    protected String eventName() {
        return "AssistantTextDelta";
    }

    @Override
    protected String detailString() {
        return "chunkIndex=" + chunkIndex + ", deltaLength=" + delta.length();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AssistantTextDelta that = (AssistantTextDelta) o;
        return getIteration() == that.getIteration() && chunkIndex == that.chunkIndex
                && getTimestamp().equals(that.getTimestamp()) && getAgentRuntimeId().equals(that.getAgentRuntimeId())
                && delta.equals(that.delta);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), delta, chunkIndex);
    }

    /** Builder for {@link AssistantTextDelta}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private String delta;
        private int chunkIndex;

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
         * Sets the incremental text fragment.
         *
         * @param delta
         *            the text fragment (must not be null or empty)
         * @return this builder
         */
        public Builder delta(String delta) {
            this.delta = delta;
            return this;
        }

        /**
         * Sets the provider chunk ordinal (0-based).
         *
         * @param chunkIndex
         *            the chunk index (must be {@code >= 0})
         * @return this builder
         */
        public Builder chunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
            return this;
        }

        /**
         * Builds the {@link AssistantTextDelta} event.
         *
         * @return a new {@link AssistantTextDelta}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, or {@code delta} is null
         * @throws IllegalArgumentException
         *             if {@code iteration} or {@code chunkIndex} is negative, or {@code delta} is empty
         */
        public AssistantTextDelta build() {
            return new AssistantTextDelta(this);
        }
    }
}
