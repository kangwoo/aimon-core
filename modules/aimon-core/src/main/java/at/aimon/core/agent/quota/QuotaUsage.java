package at.aimon.core.agent.quota;

import java.util.Objects;

import at.aimon.core.llm.cost.Money;

/**
 * An amount of usage: total tokens and estimated cost in US dollars.
 *
 * <p>
 * The cost is whatever the executor's {@link at.aimon.core.llm.cost.CostEstimator} priced the calls at. With the
 * default {@link at.aimon.core.llm.cost.CostEstimator#NOOP} every call costs zero, and a cost limit never fires — the
 * same rule as {@link at.aimon.core.agent.budget.ExecutionBudget#getMaxCostUsd()}.
 *
 * <p>
 * Immutable value object.
 */
public final class QuotaUsage {

    private static final QuotaUsage ZERO = new QuotaUsage(0L, Money.zeroUsd());

    private final long tokens;
    private final Money cost;

    private QuotaUsage(long tokens, Money cost) {
        if (tokens < 0) {
            throw new IllegalArgumentException("Tokens cannot be negative: " + tokens);
        }
        this.tokens = tokens;
        this.cost = Objects.requireNonNull(cost, "Cost cannot be null");
        if (!Money.USD.equals(cost.getCurrency())) {
            throw new IllegalArgumentException("Quota cost must be in USD, got " + cost.getCurrency());
        }
    }

    /**
     * Creates a usage amount.
     *
     * @param tokens
     *            the total tokens (must not be negative)
     * @param cost
     *            the estimated cost (must not be null, must be USD)
     * @return a new usage (never null)
     */
    public static QuotaUsage of(long tokens, Money cost) {
        return new QuotaUsage(tokens, cost);
    }

    /**
     * Gets the empty usage.
     *
     * @return zero tokens and zero dollars (never null)
     */
    public static QuotaUsage zero() {
        return ZERO;
    }

    /**
     * Gets the total tokens.
     *
     * @return the tokens (never negative)
     */
    public long getTokens() {
        return tokens;
    }

    /**
     * Gets the estimated cost.
     *
     * @return the cost in USD (never null)
     */
    public Money getCost() {
        return cost;
    }

    /**
     * Adds another usage to this one.
     *
     * @param other
     *            the addend (must not be null)
     * @return a new usage holding the sum (never null)
     */
    public QuotaUsage plus(QuotaUsage other) {
        Objects.requireNonNull(other, "Other cannot be null");
        return new QuotaUsage(Math.addExact(tokens, other.tokens), cost.add(other.cost));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final QuotaUsage that = (QuotaUsage) o;
        return tokens == that.tokens && cost.compareTo(that.cost) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tokens, cost.getAmount().stripTrailingZeros());
    }

    @Override
    public String toString() {
        return tokens + " tokens / " + cost;
    }
}
