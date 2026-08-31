package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that a conversation compaction step was applied between iterations.
 *
 * <p>
 * <b>Use when:</b> a compaction strategy (e.g., summarization, truncation) replaces a portion of the running
 * conversation with a compacted summary before the next iteration. This event is intended to be emitted once the
 * compaction engine is wired (see the conversation-compaction design docs) and is provided here for forward
 * compatibility.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getStrategyName()} — name of the compaction strategy that ran (non-null)
 * <li>{@link #getMessagesBefore()} — number of messages in the conversation immediately before compaction
 * <li>{@link #getMessagesAfter()} — number of messages in the conversation immediately after compaction
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class CompactBoundary extends AgentExecutionEvent {

    private final String strategyName;
    private final int messagesBefore;
    private final int messagesAfter;

    private CompactBoundary(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.strategyName = Objects.requireNonNull(builder.strategyName, "strategyName cannot be null");
        if (this.strategyName.isEmpty()) {
            throw new IllegalArgumentException("strategyName cannot be empty");
        }
        if (builder.messagesBefore < 0) {
            throw new IllegalArgumentException("messagesBefore cannot be negative: " + builder.messagesBefore);
        }
        if (builder.messagesAfter < 0) {
            throw new IllegalArgumentException("messagesAfter cannot be negative: " + builder.messagesAfter);
        }
        if (builder.messagesAfter > builder.messagesBefore) {
            throw new IllegalArgumentException("messagesAfter (" + builder.messagesAfter
                    + ") cannot exceed messagesBefore (" + builder.messagesBefore + ")");
        }
        this.messagesBefore = builder.messagesBefore;
        this.messagesAfter = builder.messagesAfter;
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
     * Returns the name of the compaction strategy that ran.
     *
     * @return the strategy name (never null, never empty)
     */
    public String getStrategyName() {
        return strategyName;
    }

    /**
     * Returns the number of messages before compaction.
     *
     * @return the message count before compaction ({@code >= 0})
     */
    public int getMessagesBefore() {
        return messagesBefore;
    }

    /**
     * Returns the number of messages after compaction.
     *
     * @return the message count after compaction ({@code >= 0})
     */
    public int getMessagesAfter() {
        return messagesAfter;
    }

    @Override
    protected String eventName() {
        return "CompactBoundary";
    }

    @Override
    protected String detailString() {
        return "strategyName='" + strategyName + "', messagesBefore=" + messagesBefore + ", messagesAfter="
                + messagesAfter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CompactBoundary that = (CompactBoundary) o;
        return messagesBefore == that.messagesBefore && messagesAfter == that.messagesAfter
                && getIteration() == that.getIteration() && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId()) && strategyName.equals(that.strategyName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), strategyName, messagesBefore,
                messagesAfter);
    }

    /** Builder for {@link CompactBoundary}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private String strategyName;
        private int messagesBefore;
        private int messagesAfter;

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
         * Sets the compaction strategy name.
         *
         * @param strategyName
         *            the strategy name (must not be null or empty)
         * @return this builder
         */
        public Builder strategyName(String strategyName) {
            this.strategyName = strategyName;
            return this;
        }

        /**
         * Sets the message count before compaction.
         *
         * @param messagesBefore
         *            the message count ({@code >= 0})
         * @return this builder
         */
        public Builder messagesBefore(int messagesBefore) {
            this.messagesBefore = messagesBefore;
            return this;
        }

        /**
         * Sets the message count after compaction.
         *
         * @param messagesAfter
         *            the message count ({@code >= 0})
         * @return this builder
         */
        public Builder messagesAfter(int messagesAfter) {
            this.messagesAfter = messagesAfter;
            return this;
        }

        /**
         * Builds the {@link CompactBoundary} event.
         *
         * @return a new {@link CompactBoundary}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, or {@code strategyName} is null
         * @throws IllegalArgumentException
         *             if {@code strategyName} is empty, {@code iteration} is negative, or {@code messagesBefore} /
         *             {@code messagesAfter} is negative
         */
        public CompactBoundary build() {
            return new CompactBoundary(this);
        }
    }
}
