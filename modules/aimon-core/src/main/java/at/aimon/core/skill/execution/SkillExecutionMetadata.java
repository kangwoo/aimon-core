package at.aimon.core.skill.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import at.aimon.core.llm.TokenUsage;

/**
 * Metadata captured during a skill execution.
 *
 * <p>
 * Mirrors {@code ExecutionMetadata} from the (deprecating) command package but lives under
 * {@code at.aimon.core.skill.execution} so the skill execution path is independent of {@code ext.command}.
 * Introduced in SK-08-C.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class SkillExecutionMetadata {

    /**
     * Creates a new builder.
     *
     * @return A new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final int iterationCount;
    private final Duration duration;
    private final TokenUsage tokenUsage;
    private final Instant startTime;
    private final Instant endTime;

    private SkillExecutionMetadata(Builder builder) {
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

        this.iterationCount = builder.iterationCount;
        this.duration = builder.duration;
        this.tokenUsage = Objects.requireNonNullElse(builder.tokenUsage, TokenUsage.empty());
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
    }

    /**
     * Gets the number of ReAct iterations executed.
     *
     * @return Non-negative iteration count
     */
    public int getIterationCount() {
        return iterationCount;
    }

    /**
     * Gets the total execution duration.
     *
     * @return The duration (never null, never negative)
     */
    public Duration getDuration() {
        return duration;
    }

    /**
     * Gets the total execution duration in milliseconds.
     *
     * @return Duration in milliseconds
     */
    public long getDurationMillis() {
        return duration.toMillis();
    }

    /**
     * Gets the accumulated LLM token usage.
     *
     * @return The token usage (never null)
     */
    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    /**
     * Gets the execution start time.
     *
     * @return The start instant (never null)
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Gets the execution end time.
     *
     * @return The end instant (never null)
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
        final SkillExecutionMetadata that = (SkillExecutionMetadata) o;
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
        return "SkillExecutionMetadata{" + "iterations=" + iterationCount + ", duration=" + duration.toMillis() + "ms"
                + ", tokens=" + tokenUsage.getTotalTokens() + ", startTime=" + startTime + ", endTime=" + endTime + '}';
    }

    /** Builder for {@link SkillExecutionMetadata}. */
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
         *            The iteration count (must be &gt;= 0)
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
         *            The duration (must not be null)
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
         *            The accumulated token usage (must not be null)
         * @return This builder
         */
        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        /**
         * Sets start and end times and computes the duration.
         *
         * @param startTime
         *            The start time (must not be null)
         * @param endTime
         *            The end time (must not be null, &gt;= startTime)
         * @return This builder
         */
        public Builder timestamps(Instant startTime, Instant endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
            if (startTime != null && endTime != null) {
                this.duration = Duration.between(startTime, endTime);
            }
            return this;
        }

        /**
         * Builds the metadata.
         *
         * @return A new {@link SkillExecutionMetadata} instance (never null)
         * @throws NullPointerException
         *             if required fields are null
         * @throws IllegalArgumentException
         *             if validation fails
         */
        public SkillExecutionMetadata build() {
            return new SkillExecutionMetadata(this);
        }
    }
}
