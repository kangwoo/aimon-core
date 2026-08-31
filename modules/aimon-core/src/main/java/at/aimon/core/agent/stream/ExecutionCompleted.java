package at.aimon.core.agent.stream;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;

/**
 * Terminal event signalling that the overall agent execution finished on its own terms (including clean budget-driven
 * stops).
 *
 * <p>
 * <b>Use when:</b> the executor has decided no further iterations will run and the execution ended without an
 * unhandled error. Subscribers typically use this to finalize UIs, persist the execution summary, or unsubscribe from
 * the publisher.
 *
 * <p>
 * Base-class iteration number of {@code 0} is acceptable for this event because it is not associated with a specific
 * iteration; use {@link #getTotalIterations()} instead to find out how many iterations ran.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getCompletionReason()} — reason the execution ended (reuses {@link CompletionReason}; non-null)
 * <li>{@link #getTotalIterations()} — total number of iterations that actually ran
 * <li>{@link #getElapsed()} — optional wall-clock duration, if the executor measured it
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class ExecutionCompleted extends AgentExecutionEvent {

    private final CompletionReason completionReason;
    private final int totalIterations;
    private final Optional<Duration> elapsed;

    private ExecutionCompleted(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.completionReason = Objects.requireNonNull(builder.completionReason, "completionReason cannot be null");
        if (builder.totalIterations < 0) {
            throw new IllegalArgumentException("totalIterations cannot be negative: " + builder.totalIterations);
        }
        this.totalIterations = builder.totalIterations;
        this.elapsed = Objects.requireNonNull(builder.elapsed, "elapsed Optional cannot be null");
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
     * Returns the reason the execution ended.
     *
     * @return the completion reason (never null)
     */
    public CompletionReason getCompletionReason() {
        return completionReason;
    }

    /**
     * Returns the total number of iterations that actually ran.
     *
     * @return the iteration count ({@code >= 0})
     */
    public int getTotalIterations() {
        return totalIterations;
    }

    /**
     * Returns the wall-clock duration of the execution, if known.
     *
     * @return {@link Optional} duration; empty when the executor did not measure it (never null)
     */
    public Optional<Duration> getElapsed() {
        return elapsed;
    }

    @Override
    protected String eventName() {
        return "ExecutionCompleted";
    }

    @Override
    protected String detailString() {
        return "completionReason=" + completionReason + ", totalIterations=" + totalIterations + ", elapsed=" + elapsed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ExecutionCompleted that = (ExecutionCompleted) o;
        return totalIterations == that.totalIterations && getIteration() == that.getIteration()
                && getTimestamp().equals(that.getTimestamp()) && getAgentRuntimeId().equals(that.getAgentRuntimeId())
                && completionReason == that.completionReason && elapsed.equals(that.elapsed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), completionReason, totalIterations,
                elapsed);
    }

    /** Builder for {@link ExecutionCompleted}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private CompletionReason completionReason;
        private int totalIterations;
        private Optional<Duration> elapsed = Optional.empty();

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
         * Sets the base iteration number (may be {@code 0} for this terminal event).
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
         * Sets the completion reason.
         *
         * @param completionReason
         *            the completion reason (must not be null)
         * @return this builder
         */
        public Builder completionReason(CompletionReason completionReason) {
            this.completionReason = completionReason;
            return this;
        }

        /**
         * Sets the total number of iterations that actually ran.
         *
         * @param totalIterations
         *            the iteration count ({@code >= 0})
         * @return this builder
         */
        public Builder totalIterations(int totalIterations) {
            this.totalIterations = totalIterations;
            return this;
        }

        /**
         * Sets the elapsed wall-clock duration (nullable — null becomes {@link Optional#empty()}).
         *
         * @param elapsed
         *            the elapsed duration, or {@code null} to clear
         * @return this builder
         */
        public Builder elapsed(Duration elapsed) {
            this.elapsed = Optional.ofNullable(elapsed);
            return this;
        }

        /**
         * Builds the {@link ExecutionCompleted} event.
         *
         * @return a new {@link ExecutionCompleted}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, or {@code completionReason} is null
         * @throws IllegalArgumentException
         *             if {@code iteration} or {@code totalIterations} is negative
         */
        public ExecutionCompleted build() {
            return new ExecutionCompleted(this);
        }
    }
}
