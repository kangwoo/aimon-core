package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.InterruptReason;

/**
 * Signals that the current turn was interrupted before completing normally.
 *
 * <p>
 * <b>Use when:</b> the executor / session detects an {@link at.aimon.core.agent.interrupt.InterruptCoordinator} trip
 * (lease lost, explicit release, holder loss, user SIGINT, ...) and surrenders the turn. Subscribers render this so
 * UI clients can show a clearly terminated turn rather than a hanging spinner. Pairs with
 * {@link ExecutionCompleted} / {@link ExecutionError} as the third terminal-event flavor.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getReason()} — the {@link InterruptReason} that tripped the coordinator
 * <li>{@link #getIterationIndex()} — 1-based iteration number that was running when the interrupt landed
 * <li>{@link #getPartialOutput()} — assistant text accumulated so far in the interrupted iteration (may be empty)
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class InterruptedAt extends AgentExecutionEvent {

    private final InterruptReason reason;
    private final int iterationIndex;
    private final String partialOutput;

    private InterruptedAt(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.reason = Objects.requireNonNull(builder.reason, "Reason cannot be null");
        if (builder.iterationIndex < 0) {
            throw new IllegalArgumentException("iterationIndex cannot be negative: " + builder.iterationIndex);
        }
        this.iterationIndex = builder.iterationIndex;
        this.partialOutput = builder.partialOutput == null ? "" : builder.partialOutput;
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
     * Returns the interrupt reason that tripped the coordinator.
     *
     * @return the reason (never null)
     */
    public InterruptReason getReason() {
        return reason;
    }

    /**
     * Returns the 1-based iteration number that was running when the interrupt landed.
     *
     * @return the iteration index (always {@code >= 0})
     */
    public int getIterationIndex() {
        return iterationIndex;
    }

    /**
     * Returns the assistant text accumulated so far in the interrupted iteration.
     *
     * @return the partial output (never null; may be empty)
     */
    public String getPartialOutput() {
        return partialOutput;
    }

    /**
     * Returns the partial output as an {@link Optional}, empty when nothing was accumulated.
     *
     * @return the partial output as an Optional (never null)
     */
    public Optional<String> getPartialOutputOptional() {
        return partialOutput.isEmpty() ? Optional.empty() : Optional.of(partialOutput);
    }

    @Override
    protected String eventName() {
        return "InterruptedAt";
    }

    @Override
    protected String detailString() {
        return "reason=" + reason + ", iterationIndex=" + iterationIndex + ", partialOutputLength="
                + partialOutput.length();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        InterruptedAt that = (InterruptedAt) o;
        return iterationIndex == that.iterationIndex && getIteration() == that.getIteration() && reason == that.reason
                && partialOutput.equals(that.partialOutput) && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), reason, iterationIndex, partialOutput);
    }

    /** Builder for {@link InterruptedAt}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private InterruptReason reason;
        private int iterationIndex;
        private String partialOutput;

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
         * Sets the iteration number on the base event.
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
         * Sets the interrupt reason.
         *
         * @param reason
         *            the reason (must not be null)
         * @return this builder
         */
        public Builder reason(InterruptReason reason) {
            this.reason = reason;
            return this;
        }

        /**
         * Sets the 1-based iteration index that was running when the interrupt landed.
         *
         * @param iterationIndex
         *            the iteration index (must be {@code >= 0})
         * @return this builder
         */
        public Builder iterationIndex(int iterationIndex) {
            this.iterationIndex = iterationIndex;
            return this;
        }

        /**
         * Sets the assistant text accumulated so far.
         *
         * @param partialOutput
         *            the partial output (may be null, treated as empty)
         * @return this builder
         */
        public Builder partialOutput(String partialOutput) {
            this.partialOutput = partialOutput;
            return this;
        }

        /**
         * Builds the {@link InterruptedAt} event.
         *
         * @return a new {@link InterruptedAt}
         * @throws NullPointerException
         *             if a required field is null
         * @throws IllegalArgumentException
         *             if {@code iteration} or {@code iterationIndex} is negative
         */
        public InterruptedAt build() {
            return new InterruptedAt(this);
        }
    }
}
