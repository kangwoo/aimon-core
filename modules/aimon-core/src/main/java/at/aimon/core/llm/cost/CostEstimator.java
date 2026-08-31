package at.aimon.core.llm.cost;

import at.aimon.core.llm.TokenUsage;

/**
 * Strategy that prices a single LLM call: given the model that was invoked and the {@link TokenUsage} it reported,
 * returns the {@link Money} it cost.
 *
 * <p>
 * A cost estimator is stateless and thread-safe. It is the seam the executor talks to; the concrete pricing source (a
 * {@link ModelPriceTable}, a remote catalogue, a flat rate, ...) lives behind it. Per the multi-instance design rule,
 * swapping the pricing strategy is a wiring change, never a code change in the caller.
 *
 * <p>
 * The framework default is {@link #NOOP}, which prices every call at {@link Money#zeroUsd() zero} — so cost tracking is
 * entirely opt-in and wiring nothing leaves behaviour unchanged.
 *
 * @see TablePricedCostEstimator
 * @see ModelPriceTable
 */
@FunctionalInterface
public interface CostEstimator {

    /**
     * The default estimator: every call costs {@link Money#zeroUsd() USD 0.00}. Used when no pricing has been wired, so
     * the surrounding cost machinery runs but observes nothing.
     */
    CostEstimator NOOP = (modelName, usage) -> Money.zeroUsd();

    /**
     * Estimates the cost of a single LLM call.
     *
     * @param modelName
     *            the model that produced the usage (may be null or empty if the model is unknown)
     * @param usage
     *            the token usage reported for the call (must not be null)
     * @return the estimated cost (never null; {@link Money#zeroUsd()} when the model cannot be priced)
     */
    Money estimate(String modelName, TokenUsage usage);
}
