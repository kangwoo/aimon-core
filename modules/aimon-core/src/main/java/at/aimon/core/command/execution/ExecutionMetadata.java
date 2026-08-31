package at.aimon.core.command.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import at.aimon.core.llm.TokenUsage;

/**
 * Represents metadata about a command execution.
 *
 * <p>
 * Contains information about the execution such as iteration count, duration, token usage, and timestamps.
 *
 * <p>
 * Immutable value object with a builder for flexible construction.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     Instant startTime = Instant.now();
 *     // ... perform execution
 *     Instant endTime = Instant.now();
 *
 *     ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(3)
 *             .tokenUsage(TokenUsage.of(150, 50, 200)).timestamps(startTime, endTime).build();
 *
 *     System.out.println("Duration: " + metadata.getDuration().toMillis() + "ms");
 *     System.out.println("Iterations: " + metadata.getIterationCount());
 * }
 * </pre>
 */
public final class ExecutionMetadata {
    /**
     * Creates a new builder for ExecutionMetadata.
     *
     * @return A new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a simple ExecutionMetadata with minimal information.
     *
     * <p>
     * Use this for commands that don't iterate or use tokens (e.g., direct commands).
     *
     * @param duration
     *            The execution duration (must not be null)
     * @param startTime
     *            The start time (must not be null)
     * @param endTime
     *            The end time (must not be null)
     * @return A new ExecutionMetadata with iterationCount=0 and empty token usage
     * @throws NullPointerException
     *             if any parameter is null
     * @throws IllegalArgumentException
     *             if validation fails
     */
    public static ExecutionMetadata simple(Duration duration, Instant startTime, Instant endTime) {
        return builder().duration(duration).startTime(startTime).endTime(endTime).build();
    }

    private final int iterationCount;
    private final Duration duration;
    private final TokenUsage tokenUsage;
    private final Instant startTime;
    private final Instant endTime;

    /**
     * Creates a new ExecutionMetadata.
     *
     * @param builder
     *            The builder containing the metadata values
     * @throws NullPointerException
     *             if any required field is null
     * @throws IllegalArgumentException
     *             if validation fails
     */
    private ExecutionMetadata(Builder builder) {
        if (builder.iterationCount < 0) {
            throw new IllegalArgumentException("Iteration count cannot be negative: " + builder.iterationCount);
        }
        Objects.requireNonNull(builder.duration, "Duration cannot be null");
        if (builder.duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be negative: " + builder.duration);
        }
        Objects.requireNonNull(builder.startTime, "Start time cannot be null");
        Objects.requireNonNull(builder.endTime, "End time cannot be null");
        if (builder.endTime.isBefore(builder.startTime)) {
            throw new IllegalArgumentException("End time cannot be before start time");
        }

        iterationCount = builder.iterationCount;
        duration = builder.duration;
        tokenUsage = Objects.requireNonNullElse(builder.tokenUsage, TokenUsage.empty());
        startTime = builder.startTime;
        endTime = builder.endTime;
    }

    /**
     * Gets the number of iterations executed.
     *
     * <p>
     * For LLM commands, this represents the number of tools execution loops. For direct commands, this is typically 0.
     *
     * @return The iteration count (>= 0)
     */
    public int getIterationCount() {
        return iterationCount;
    }

    /**
     * Gets the execution duration.
     *
     * @return The duration (never null, never negative)
     */
    public Duration getDuration() {
        return duration;
    }

    /**
     * Gets the execution duration in milliseconds.
     *
     * @return The duration in milliseconds
     */
    public long getDurationMillis() {
        return duration.toMillis();
    }

    /**
     * Gets the token usage.
     *
     * <p>
     * For LLM commands, this contains the accumulated token usage across all LLM calls. For direct commands, this is
     * typically empty (all zeros).
     *
     * @return The token usage (never null)
     */
    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    /**
     * Checks if this execution has token usage.
     *
     * @return true if token usage is not empty, false otherwise
     */
    public boolean hasTokenUsage() {
        return !tokenUsage.equals(TokenUsage.empty());
    }

    /**
     * Gets the start time of the execution.
     *
     * @return The start time (never null)
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Gets the end time of the execution.
     *
     * @return The end time (never null)
     */
    public Instant getEndTime() {
        return endTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ExecutionMetadata that = (ExecutionMetadata) o;
        return iterationCount == that.iterationCount && duration.equals(that.duration)
                && tokenUsage.equals(that.tokenUsage) && startTime.equals(that.startTime)
                && endTime.equals(that.endTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(iterationCount, duration, tokenUsage, startTime, endTime);
    }

    @Override
    public String toString() {
        return "ExecutionMetadata{" + "iterations=" + iterationCount + ", duration=" + duration.toMillis() + "ms"
                + ", tokens=" + tokenUsage.getTotalTokens() + ", startTime=" + startTime + ", endTime=" + endTime + '}';
    }

    /** Builder for ExecutionMetadata. */
    public static final class Builder {
        private int iterationCount;
        private Duration duration;
        private TokenUsage tokenUsage = TokenUsage.empty();
        private Instant startTime;
        private Instant endTime;

        private Builder() {
        }

        /**
         * Sets the iteration count.
         *
         * @param iterationCount
         *            The number of iterations (must be >= 0)
         * @return This builder
         */
        public Builder iterationCount(int iterationCount) {
            this.iterationCount = iterationCount;
            return this;
        }

        /**
         * Sets the duration.
         *
         * @param duration
         *            The execution duration (must not be null)
         * @return This builder
         */
        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        /**
         * Sets the token usage.
         *
         * @param tokenUsage
         *            The token usage (must not be null)
         * @return This builder
         */
        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        /**
         * Sets the start time.
         *
         * @param startTime
         *            The start time (must not be null)
         * @return This builder
         */
        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        /**
         * Sets the end time.
         *
         * @param endTime
         *            The end time (must not be null)
         * @return This builder
         */
        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        /**
         * Sets the start and end times, and automatically calculates the duration.
         *
         * @param startTime
         *            The start time (must not be null)
         * @param endTime
         *            The end time (must not be null, must be >= startTime)
         * @return This builder
         */
        public Builder timestamps(Instant startTime, Instant endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
            if (startTime != null && endTime != null) {
                duration = Duration.between(startTime, endTime);
            }
            return this;
        }

        /**
         * Builds the ExecutionMetadata.
         *
         * @return A new ExecutionMetadata instance
         * @throws NullPointerException
         *             if any required field is null
         * @throws IllegalArgumentException
         *             if validation fails
         */
        public ExecutionMetadata build() {
            return new ExecutionMetadata(this);
        }
    }
}
