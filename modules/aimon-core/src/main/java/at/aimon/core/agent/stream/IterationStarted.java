package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that a new ReAct iteration has begun.
 *
 * <p>
 * <b>Use when:</b> the executor enters a new iteration of the ReAct loop, immediately before dispatching an LLM call
 * for that iteration. Subscribers typically use this to render "Iteration N/M" headers or to reset per-iteration
 * buffers.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getPlannedIteration()} — iteration the executor intends to run; equal to {@link #getIteration()} in
 * normal flow but kept as a separate field for forward compatibility with resume scenarios (e.g., replay from a
 * snapshot that started at a different iteration number).
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class IterationStarted extends AgentExecutionEvent {

    private final int plannedIteration;

    private IterationStarted(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        if (builder.plannedIteration < 0) {
            throw new IllegalArgumentException("plannedIteration cannot be negative: " + builder.plannedIteration);
        }
        this.plannedIteration = builder.plannedIteration;
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
     * Returns the iteration number the executor intends to run.
     *
     * @return the planned iteration (always {@code >= 0})
     */
    public int getPlannedIteration() {
        return plannedIteration;
    }

    @Override
    protected String eventName() {
        return "IterationStarted";
    }

    @Override
    protected String detailString() {
        return "plannedIteration=" + plannedIteration;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        IterationStarted that = (IterationStarted) o;
        return plannedIteration == that.plannedIteration && getIteration() == that.getIteration()
                && getTimestamp().equals(that.getTimestamp()) && getAgentRuntimeId().equals(that.getAgentRuntimeId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), plannedIteration);
    }

    /** Builder for {@link IterationStarted}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private int plannedIteration;

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
         * Sets the planned iteration number.
         *
         * @param plannedIteration
         *            the planned iteration number (must be {@code >= 0})
         * @return this builder
         */
        public Builder plannedIteration(int plannedIteration) {
            this.plannedIteration = plannedIteration;
            return this;
        }

        /**
         * Builds the {@link IterationStarted} event.
         *
         * @return a new {@link IterationStarted}
         * @throws NullPointerException
         *             if {@code timestamp} or {@code agentRuntimeId} is null
         * @throws IllegalArgumentException
         *             if {@code iteration} or {@code plannedIteration} is negative
         */
        public IterationStarted build() {
            return new IterationStarted(this);
        }
    }
}
