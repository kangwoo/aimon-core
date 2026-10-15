package at.aimon.core.agent.quota;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.cost.Money;

/**
 * An upper bound on the usage of one window: a token ceiling, a cost ceiling, or both.
 *
 * <p>
 * A limit is <b>reached</b> once the usage is at or above either ceiling — the same {@code >=} boundary
 * {@link at.aimon.core.agent.budget.BudgetTracker} uses. A limit with neither ceiling is never reached.
 *
 * <p>
 * Immutable value object. Use {@link #total()} or {@link #daily()} to start a builder:
 *
 * <pre>
 * {
 *     &#64;code
 *     QuotaLimit perSession = QuotaLimit.total().maxTokens(2_000_000).build();
 *     QuotaLimit perUserPerDay = QuotaLimit.daily().maxCost(Money.usd(20)).build();
 * }
 * </pre>
 */
public final class QuotaLimit {

    private final QuotaWindow window;
    private final Long maxTokens;
    private final Money maxCost;

    private QuotaLimit(Builder builder) {
        this.window = Objects.requireNonNull(builder.window, "Window cannot be null");
        this.maxTokens = builder.maxTokens;
        this.maxCost = builder.maxCost;
    }

    /**
     * Starts a builder for a limit of the given window.
     *
     * @param window
     *            the window (must not be null)
     * @return a new builder (never null)
     */
    public static Builder builder(QuotaWindow window) {
        return new Builder(Objects.requireNonNull(window, "Window cannot be null"));
    }

    /**
     * Starts a builder for a {@link QuotaWindow#TOTAL} limit.
     *
     * @return a new builder (never null)
     */
    public static Builder total() {
        return new Builder(QuotaWindow.TOTAL);
    }

    /**
     * Starts a builder for a {@link QuotaWindow#DAILY} limit.
     *
     * @return a new builder (never null)
     */
    public static Builder daily() {
        return new Builder(QuotaWindow.DAILY);
    }

    /**
     * Gets the window.
     *
     * @return the window (never null)
     */
    public QuotaWindow getWindow() {
        return window;
    }

    /**
     * Gets the token ceiling.
     *
     * @return the ceiling, or empty when tokens are not limited
     */
    public Optional<Long> getMaxTokens() {
        return Optional.ofNullable(maxTokens);
    }

    /**
     * Gets the cost ceiling.
     *
     * @return the ceiling in USD, or empty when cost is not limited
     */
    public Optional<Money> getMaxCost() {
        return Optional.ofNullable(maxCost);
    }

    /**
     * Tells whether the given usage has reached this limit.
     *
     * @param used
     *            the accumulated usage of one key (must not be null)
     * @return {@code true} when the usage is at or above either ceiling
     */
    public boolean isReachedBy(QuotaUsage used) {
        Objects.requireNonNull(used, "Used cannot be null");
        if (maxTokens != null && used.getTokens() >= maxTokens) {
            return true;
        }
        return maxCost != null && used.getCost().compareTo(maxCost) >= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final QuotaLimit that = (QuotaLimit) o;
        return window == that.window && Objects.equals(maxTokens, that.maxTokens)
                && Objects.equals(maxCost, that.maxCost);
    }

    @Override
    public int hashCode() {
        return Objects.hash(window, maxTokens, maxCost);
    }

    @Override
    public String toString() {
        return "QuotaLimit{window=" + window + ", maxTokens=" + maxTokens + ", maxCost=" + maxCost + '}';
    }

    /** Builder for {@link QuotaLimit}. */
    public static final class Builder {
        private final QuotaWindow window;
        private Long maxTokens;
        private Money maxCost;

        private Builder(QuotaWindow window) {
            this.window = window;
        }

        /**
         * Sets the token ceiling.
         *
         * @param maxTokens
         *            the ceiling (must be positive)
         * @return this builder
         * @throws IllegalArgumentException
         *             if {@code maxTokens} is not positive
         */
        public Builder maxTokens(long maxTokens) {
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be positive: " + maxTokens);
            }
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Sets the cost ceiling.
         *
         * @param maxCost
         *            the ceiling (must not be null, must be a positive USD amount)
         * @return this builder
         * @throws IllegalArgumentException
         *             if {@code maxCost} is not in USD or is not positive
         */
        public Builder maxCost(Money maxCost) {
            Objects.requireNonNull(maxCost, "Max cost cannot be null");
            if (!Money.USD.equals(maxCost.getCurrency())) {
                throw new IllegalArgumentException("Quota cost limits are USD-only, got " + maxCost.getCurrency());
            }
            // A zero ceiling is reached by zero usage, which would stop every turn at iteration 0 — the maxTokens rule.
            if (maxCost.getAmount().signum() <= 0) {
                throw new IllegalArgumentException("Max cost must be positive, got " + maxCost);
            }
            this.maxCost = maxCost;
            return this;
        }

        /**
         * Builds the limit.
         *
         * @return a new limit (never null)
         */
        public QuotaLimit build() {
            return new QuotaLimit(this);
        }
    }
}
