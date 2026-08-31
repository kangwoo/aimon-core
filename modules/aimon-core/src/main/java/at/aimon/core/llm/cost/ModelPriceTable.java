package at.aimon.core.llm.cost;

import java.util.Optional;

/**
 * Look-up of a {@link ModelPrice} by model name.
 *
 * <p>
 * A price table is a stateless, thread-safe strategy that maps a model identifier (e.g. {@code "gpt-4o"},
 * {@code "claude-sonnet-4"}) to its pricing, or reports that the model is unknown. Unknown models return
 * {@link Optional#empty()} rather than a sentinel price, so callers can decide how to treat them (the framework's
 * {@link TablePricedCostEstimator} prices them at {@link Money#zeroUsd() zero} and warns once).
 *
 * <p>
 * The default implementation is {@link InMemoryModelPriceTable}. Alternative implementations (config-backed,
 * remote-catalog-backed, ...) can be swapped in without touching callers, per the multi-instance design rule.
 *
 * @see InMemoryModelPriceTable
 * @see CostEstimator
 */
public interface ModelPriceTable {

    /**
     * An empty table that knows no prices. Combined with {@link TablePricedCostEstimator} this yields zero-cost
     * estimates for every model — the observe-nothing default.
     */
    ModelPriceTable EMPTY = modelName -> Optional.empty();

    /**
     * Resolves the price for the given model name.
     *
     * @param modelName
     *            the model identifier (may be null or empty, in which case the result is empty)
     * @return the price if known, otherwise {@link Optional#empty()}
     */
    Optional<ModelPrice> priceOf(String modelName);
}
