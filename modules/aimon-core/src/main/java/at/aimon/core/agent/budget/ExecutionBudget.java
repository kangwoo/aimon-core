package at.aimon.core.agent.budget;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.cost.Money;

/**
 * Declarative safety limits for an agent execution.
 *
 * <p>
 * Each dimension is optional; an unset limit means "no limit applied for this dimension". Tracker and guard components
 * consume these limits during the ReAct loop to decide when to stop execution with a structured
 * {@link CompletionReason}.
 *
 * <p>
 * Immutable value object — instances are thread-safe and freely shareable.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     ExecutionBudget budget = ExecutionBudget.builder().maxIterations(20).maxTokens(100_000)
 *             .maxWallClockDuration(Duration.ofMinutes(5)).build();
 * }
 * </pre>
 *
 * @see CompletionReason
 */
public final class ExecutionBudget {

    /**
     * Budget without any limits.
     *
     * <p>
     * Convenience for callers that want an explicit "no budget" value instead of passing {@code null}. Equivalent to
     * {@code ExecutionBudget.builder().build()}.
     *
     * @return an unlimited budget
     */
    public static ExecutionBudget unlimited() {
        return new Builder().build();
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final Integer maxIterations;
    private final Integer maxTokens;
    private final Duration maxWallClockDuration;
    private final Integer compactionTokenThreshold;
    private final Money maxCostUsd;

    private ExecutionBudget(Builder builder) {
        this.maxIterations = builder.maxIterations;
        this.maxTokens = builder.maxTokens;
        this.maxWallClockDuration = builder.maxWallClockDuration;
        this.compactionTokenThreshold = builder.compactionTokenThreshold;
        this.maxCostUsd = builder.maxCostUsd;
    }

    /**
     * @return optional maximum number of ReAct iterations
     */
    public Optional<Integer> getMaxIterations() {
        return Optional.ofNullable(maxIterations);
    }

    /**
     * @return optional cumulative token budget (prompt + completion) across all iterations
     */
    public Optional<Integer> getMaxTokens() {
        return Optional.ofNullable(maxTokens);
    }

    /**
     * @return optional wall-clock duration budget measured from the start of execution
     */
    public Optional<Duration> getMaxWallClockDuration() {
        return Optional.ofNullable(maxWallClockDuration);
    }

    /**
     * Optional cumulative-token threshold at which the tracker emits a soft {@link BudgetDecision#SHOULD_COMPACT}
     * hint (rather than a hard {@link BudgetDecision#STOP}), asking the loop to proactively compact the conversation so
     * subsequent iterations cost fewer tokens and the remaining hard budget stretches further.
     *
     * <p>
     * This is a <b>soft</b> limit: it never stops execution and therefore does not count toward {@link #isUnlimited()}.
     * When unset, the tracker never emits {@code SHOULD_COMPACT} and behaviour is unchanged.
     *
     * @return optional cumulative-token compaction threshold
     */
    public Optional<Integer> getCompactionTokenThreshold() {
        return Optional.ofNullable(compactionTokenThreshold);
    }

    /**
     * Optional cumulative monetary budget (in USD) across all iterations. When set, the tracker emits a hard
     * {@link BudgetDecision#STOP} with {@link CompletionReason#COST_BUDGET_EXCEEDED} once the accumulated estimated
     * cost
     * reaches this ceiling.
     *
     * <p>
     * This axis is opt-in and only meaningful when a cost estimator is wired into the executor; without pricing the
     * accumulated cost stays at zero and the limit never fires.
     *
     * @return optional cumulative cost budget in USD
     */
    public Optional<Money> getMaxCostUsd() {
        return Optional.ofNullable(maxCostUsd);
    }

    /**
     * @return true if this budget declares no <b>hard</b> limits at all. The soft
     *         {@link #getCompactionTokenThreshold() compaction threshold} is deliberately excluded — it never stops
     *         execution, so a budget carrying only a compaction threshold is still "unlimited" for STOP purposes.
     */
    public boolean isUnlimited() {
        return maxIterations == null && maxTokens == null && maxWallClockDuration == null && maxCostUsd == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ExecutionBudget that = (ExecutionBudget) o;
        return Objects.equals(maxIterations, that.maxIterations) && Objects.equals(maxTokens, that.maxTokens)
                && Objects.equals(maxWallClockDuration, that.maxWallClockDuration)
                && Objects.equals(compactionTokenThreshold, that.compactionTokenThreshold)
                && Objects.equals(maxCostUsd, that.maxCostUsd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxIterations, maxTokens, maxWallClockDuration, compactionTokenThreshold, maxCostUsd);
    }

    @Override
    public String toString() {
        return "ExecutionBudget{maxIterations=" + maxIterations + ", maxTokens=" + maxTokens + ", maxWallClockDuration="
                + maxWallClockDuration + ", compactionTokenThreshold=" + compactionTokenThreshold + ", maxCostUsd="
                + maxCostUsd + '}';
    }

    /** Builder for {@link ExecutionBudget}. Unset dimensions mean "no limit". */
    public static final class Builder {
        private Integer maxIterations;
        private Integer maxTokens;
        private Duration maxWallClockDuration;
        private Integer compactionTokenThreshold;
        private Money maxCostUsd;

        private Builder() {
        }

        /**
         * Sets the maximum number of ReAct iterations.
         *
         * @param maxIterations
         *            the iteration limit (must be {@code >= 1})
         * @return this builder
         * @throws IllegalArgumentException
         *             if {@code maxIterations < 1}
         */
        public Builder maxIterations(int maxIterations) {
            if (maxIterations < 1) {
                throw new IllegalArgumentException("maxIterations must be >= 1, got: " + maxIterations);
            }
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the cumulative token budget (prompt + completion) across all iterations.
         *
         * @param maxTokens
         *            the token limit (must be {@code >= 1})
         * @return this builder
         * @throws IllegalArgumentException
         *             if {@code maxTokens < 1}
         */
        public Builder maxTokens(int maxTokens) {
            if (maxTokens < 1) {
                throw new IllegalArgumentException("maxTokens must be >= 1, got: " + maxTokens);
            }
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Sets the wall-clock duration budget measured from the start of execution.
         *
         * @param duration
         *            the duration (must be positive)
         * @return this builder
         * @throws NullPointerException
         *             if {@code duration} is null
         * @throws IllegalArgumentException
         *             if {@code duration} is zero or negative
         */
        public Builder maxWallClockDuration(Duration duration) {
            Objects.requireNonNull(duration, "Duration cannot be null");
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("Duration must be positive, got: " + duration);
            }
            this.maxWallClockDuration = duration;
            return this;
        }

        /**
         * Sets the cumulative-token threshold at which the tracker emits a soft
         * {@link BudgetDecision#SHOULD_COMPACT} hint. Must be less than {@link #maxTokens(int)} when both are set,
         * otherwise the hard STOP fires first and the hint is never observed (not enforced here — the tracker gives
         * STOP priority regardless).
         *
         * @param compactionTokenThreshold
         *            the cumulative-token threshold (must be {@code >= 1})
         * @return this builder
         * @throws IllegalArgumentException
         *             if {@code compactionTokenThreshold < 1}
         */
        public Builder compactionTokenThreshold(int compactionTokenThreshold) {
            if (compactionTokenThreshold < 1) {
                throw new IllegalArgumentException(
                        "compactionTokenThreshold must be >= 1, got: " + compactionTokenThreshold);
            }
            this.compactionTokenThreshold = compactionTokenThreshold;
            return this;
        }

        /**
         * Sets the cumulative monetary budget in USD. Execution stops with
         * {@link CompletionReason#COST_BUDGET_EXCEEDED} once the accumulated estimated cost reaches this amount.
         *
         * @param maxCostUsd
         *            the USD cost ceiling (must not be null, must be a positive USD amount)
         * @return this builder
         * @throws NullPointerException
         *             if {@code maxCostUsd} is null
         * @throws IllegalArgumentException
         *             if the amount is not positive or is not denominated in USD
         */
        public Builder maxCostUsd(Money maxCostUsd) {
            Objects.requireNonNull(maxCostUsd, "maxCostUsd cannot be null");
            if (!Money.USD.equals(maxCostUsd.getCurrency())) {
                throw new IllegalArgumentException("maxCostUsd must be denominated in USD, got: " + maxCostUsd);
            }
            if (maxCostUsd.isZero()) {
                throw new IllegalArgumentException("maxCostUsd must be positive, got: " + maxCostUsd);
            }
            this.maxCostUsd = maxCostUsd;
            return this;
        }

        /**
         * @return a new {@link ExecutionBudget}
         */
        public ExecutionBudget build() {
            return new ExecutionBudget(this);
        }
    }
}
