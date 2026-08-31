package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that the current ReAct iteration has finished.
 *
 * <p>
 * <b>Use when:</b> the executor finishes processing an iteration (both LLM response and any subsequent tool
 * invocations). Subscribers typically use this to close per-iteration UI sections or to decide whether to display a
 * "thinking" indicator for the next iteration.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getCompletedIteration()} — iteration number that just completed (normally equal to
 * {@link #getIteration()}; kept explicit for forward compatibility)
 * <li>{@link #isWillContinue()} — {@code true} when the executor intends to run another iteration next,
 * {@code false} when the executor is about to stop (e.g., budget exhausted, LLM produced final answer)
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class IterationCompleted extends AgentExecutionEvent {

    private final int completedIteration;
    private final boolean willContinue;

    private IterationCompleted(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        if (builder.completedIteration < 0) {
            throw new IllegalArgumentException("completedIteration cannot be negative: " + builder.completedIteration);
        }
        this.completedIteration = builder.completedIteration;
        this.willContinue = builder.willContinue;
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
     * Returns the iteration number that just completed.
     *
     * @return the completed iteration number ({@code >= 0})
     */
    public int getCompletedIteration() {
        return completedIteration;
    }

    /**
     * Returns whether the executor intends to run another iteration.
     *
     * @return {@code true} if another iteration will follow
     */
    public boolean isWillContinue() {
        return willContinue;
    }

    @Override
    protected String eventName() {
        return "IterationCompleted";
    }

    @Override
    protected String detailString() {
        return "completedIteration=" + completedIteration + ", willContinue=" + willContinue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        IterationCompleted that = (IterationCompleted) o;
        return completedIteration == that.completedIteration && willContinue == that.willContinue
                && getIteration() == that.getIteration() && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), completedIteration, willContinue);
    }

    /** Builder for {@link IterationCompleted}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private int completedIteration;
        private boolean willContinue;

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
         * Sets the completed iteration number.
         *
         * @param completedIteration
         *            the completed iteration number ({@code >= 0})
         * @return this builder
         */
        public Builder completedIteration(int completedIteration) {
            this.completedIteration = completedIteration;
            return this;
        }

        /**
         * Sets whether the executor will continue with another iteration.
         *
         * @param willContinue
         *            {@code true} if another iteration will follow
         * @return this builder
         */
        public Builder willContinue(boolean willContinue) {
            this.willContinue = willContinue;
            return this;
        }

        /**
         * Builds the {@link IterationCompleted} event.
         *
         * @return a new {@link IterationCompleted}
         * @throws NullPointerException
         *             if {@code timestamp} or {@code agentRuntimeId} is null
         * @throws IllegalArgumentException
         *             if {@code iteration} or {@code completedIteration} is negative
         */
        public IterationCompleted build() {
            return new IterationCompleted(this);
        }
    }
}
