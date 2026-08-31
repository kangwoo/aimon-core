package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;

/**
 * Terminal event signalling that the overall agent execution ended with an error.
 *
 * <p>
 * <b>Use when:</b> the executor aborted the run because of an unhandled exception or a non-clean stop. Subscribers
 * typically render the error to the user, mark the execution as failed, and unsubscribe.
 *
 * <p>
 * Base-class iteration number of {@code 0} is acceptable for this event because it is not associated with a specific
 * iteration.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getErrorMessage()} — short, human-readable description of the error (non-null)
 * <li>{@link #getCause()} — optional root {@link Throwable}, wrapped in a never-null {@link Optional}
 * <li>{@link #getCompletionReason()} — optional {@link CompletionReason}; empty when the error is not a clean-stop
 * boundary (e.g., uncaught runtime exception), populated when it maps to a well-known terminal reason such as
 * {@link CompletionReason#ERROR}
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class ExecutionError extends AgentExecutionEvent {

    private final String errorMessage;
    private final Optional<Throwable> cause;
    private final Optional<CompletionReason> completionReason;

    private ExecutionError(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.errorMessage = Objects.requireNonNull(builder.errorMessage, "errorMessage cannot be null");
        if (this.errorMessage.isEmpty()) {
            throw new IllegalArgumentException("errorMessage cannot be empty");
        }
        this.cause = Objects.requireNonNull(builder.cause, "cause Optional cannot be null");
        this.completionReason = Objects.requireNonNull(builder.completionReason,
                "completionReason Optional cannot be null");
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
     * Returns the human-readable description of the error.
     *
     * @return the error message (never null, never empty)
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the optional root cause of the error.
     *
     * @return {@link Optional} {@link Throwable}; empty when no cause was supplied (never null)
     */
    public Optional<Throwable> getCause() {
        return cause;
    }

    /**
     * Returns the optional {@link CompletionReason} when the error corresponds to a well-known clean-stop boundary.
     *
     * @return {@link Optional} completion reason; empty when the error is an uncategorized failure (never null)
     */
    public Optional<CompletionReason> getCompletionReason() {
        return completionReason;
    }

    @Override
    protected String eventName() {
        return "ExecutionError";
    }

    @Override
    protected String detailString() {
        return "errorMessage='" + errorMessage + "', cause=" + cause.map(Throwable::getClass).map(Class::getName)
                + ", completionReason=" + completionReason;
    }

    /**
     * Compares two events for equality across every persisted field.
     *
     * <p>
     * <b>Note on {@code cause} equality:</b> {@link Throwable} does not override {@link Object#equals(Object)}, so the
     * cause comparison collapses to reference equality. Two {@link ExecutionError} instances carrying "equivalent" but
     * distinct throwable instances will therefore compare as not equal. Callers that want to dedupe by message or type
     * should compare the relevant fields explicitly rather than relying on this method.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ExecutionError that = (ExecutionError) o;
        return getIteration() == that.getIteration() && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId()) && errorMessage.equals(that.errorMessage)
                && cause.equals(that.cause) && completionReason.equals(that.completionReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), errorMessage, cause, completionReason);
    }

    /** Builder for {@link ExecutionError}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private String errorMessage;
        private Optional<Throwable> cause = Optional.empty();
        private Optional<CompletionReason> completionReason = Optional.empty();

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
         * Sets the human-readable error message.
         *
         * @param errorMessage
         *            the error message (must not be null or empty)
         * @return this builder
         */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /**
         * Sets the optional root cause (nullable — null becomes {@link Optional#empty()}).
         *
         * @param cause
         *            the root {@link Throwable}, or {@code null} to clear
         * @return this builder
         */
        public Builder cause(Throwable cause) {
            this.cause = Optional.ofNullable(cause);
            return this;
        }

        /**
         * Sets the optional {@link CompletionReason} (nullable — null becomes {@link Optional#empty()}).
         *
         * @param completionReason
         *            the completion reason, or {@code null} to clear
         * @return this builder
         */
        public Builder completionReason(CompletionReason completionReason) {
            this.completionReason = Optional.ofNullable(completionReason);
            return this;
        }

        /**
         * Builds the {@link ExecutionError} event.
         *
         * @return a new {@link ExecutionError}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, or {@code errorMessage} is null
         * @throws IllegalArgumentException
         *             if {@code errorMessage} is empty or {@code iteration} is negative
         */
        public ExecutionError build() {
            return new ExecutionError(this);
        }
    }
}
