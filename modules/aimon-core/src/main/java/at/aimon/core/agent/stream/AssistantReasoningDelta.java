package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that a fragment of the model's <em>deliberation</em> was received during a streaming assistant response.
 *
 * <p>
 * <b>This is not the assistant's answer, and it must never be treated as one.</b> It carries the model's reasoning
 * summary (OpenAI) or thinking text (Anthropic) so a watcher can see that something is happening on an execution that
 * would otherwise be silent for tens of seconds. It is never appended to the assistant message, never persisted to a
 * transcript, and never summarised by {@link AssistantMessageReceived}. A subscriber that appends this and
 * {@link AssistantTextDelta} to one accumulator reproduces, one layer out, exactly the failure this separate type
 * exists to prevent — see {@link at.aimon.core.llm.streaming.ChunkAggregator} for the same invariant one layer in.
 *
 * <p>
 * <b>Use when:</b> the executor is operating in streaming mode, the deployment opted in to a reasoning stream on its
 * provider, and the provider emitted a reasoning delta. Renderers show it distinctly from answer text — the CLI paints
 * it dim under a {@code [thinking]} marker.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getDelta()} — the newly added deliberation fragment; guaranteed non-empty
 * <li>{@link #getChunkIndex()} — reasoning chunk ordinal (0-based) within the current streaming attempt
 * </ul>
 *
 * <p>
 * Ordering contract: within a single streaming attempt, {@code chunkIndex} values are strictly monotonically
 * increasing starting at 0. This is <em>its own</em> sequence, separate from {@link AssistantTextDelta}'s — sharing one
 * counter would punch holes in the text-delta sequence and break that event's identical contract for a consumer using
 * it to detect loss. An {@link AssistantTextStreamReset} event resets both for the next attempt.
 *
 * <p>
 * Immutable value object.
 */
public final class AssistantReasoningDelta extends AgentExecutionEvent {

    private final String delta;
    private final int chunkIndex;

    private AssistantReasoningDelta(Builder builder) {
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
     * Returns the incremental deliberation fragment carried by this chunk.
     *
     * @return the delta string (never null, never empty)
     */
    public String getDelta() {
        return delta;
    }

    /**
     * Returns the reasoning chunk ordinal (0-based) within the current streaming attempt.
     *
     * @return the chunk index (always {@code >= 0})
     */
    public int getChunkIndex() {
        return chunkIndex;
    }

    @Override
    protected String eventName() {
        return "AssistantReasoningDelta";
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
        AssistantReasoningDelta that = (AssistantReasoningDelta) o;
        return getIteration() == that.getIteration() && chunkIndex == that.chunkIndex
                && getTimestamp().equals(that.getTimestamp()) && getAgentRuntimeId().equals(that.getAgentRuntimeId())
                && delta.equals(that.delta);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), delta, chunkIndex);
    }

    /** Builder for {@link AssistantReasoningDelta}. */
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
         * Sets the incremental deliberation fragment.
         *
         * @param delta
         *            the reasoning fragment (must not be null or empty)
         * @return this builder
         */
        public Builder delta(String delta) {
            this.delta = delta;
            return this;
        }

        /**
         * Sets the reasoning chunk ordinal (0-based).
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
         * Builds the {@link AssistantReasoningDelta} event.
         *
         * @return a new {@link AssistantReasoningDelta}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, or {@code delta} is null
         * @throws IllegalArgumentException
         *             if {@code iteration} or {@code chunkIndex} is negative, or {@code delta} is empty
         */
        public AssistantReasoningDelta build() {
            return new AssistantReasoningDelta(this);
        }
    }
}
