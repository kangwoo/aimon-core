package at.aimon.core.llm.cost;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.TokenUsage;

/**
 * {@link CostEstimator} that prices calls by looking the model up in a {@link ModelPriceTable}.
 *
 * <p>
 * When the model is known, the estimate is {@code price.costOf(usage)}. When it is unknown, the call is priced at
 * {@link Money#zeroUsd() zero} and a warning is logged <em>once per model name</em> — so an unpriced model is visible
 * in
 * the logs without flooding them on every iteration of a ReAct loop.
 *
 * <p>
 * Stateless with respect to pricing; the only mutable state is the thread-safe set that de-duplicates the
 * unknown-model warnings.
 */
public final class TablePricedCostEstimator implements CostEstimator {

    private static final Logger log = LoggerFactory.getLogger(TablePricedCostEstimator.class);

    private final ModelPriceTable priceTable;
    private final Set<String> warnedUnknownModels = ConcurrentHashMap.newKeySet();

    /**
     * Creates an estimator backed by the given price table.
     *
     * @param priceTable
     *            the price table to resolve model prices from (must not be null)
     */
    public TablePricedCostEstimator(ModelPriceTable priceTable) {
        this.priceTable = Objects.requireNonNull(priceTable, "priceTable cannot be null");
    }

    /**
     * Convenience factory over {@link InMemoryModelPriceTable#withDefaults()}.
     *
     * @return an estimator priced by the framework-default model price table
     */
    public static TablePricedCostEstimator withDefaultPrices() {
        return new TablePricedCostEstimator(InMemoryModelPriceTable.withDefaults());
    }

    @Override
    public Money estimate(String modelName, TokenUsage usage) {
        Objects.requireNonNull(usage, "usage cannot be null");
        final Optional<ModelPrice> price = priceTable.priceOf(modelName);
        if (price.isPresent()) {
            return price.get().costOf(usage);
        }
        warnUnknownOnce(modelName);
        return Money.zeroUsd();
    }

    private void warnUnknownOnce(String modelName) {
        final String key = modelName == null ? "" : modelName;
        if (warnedUnknownModels.add(key)) {
            log.warn(
                    "No price registered for model '{}'; cost will be reported as {}. "
                            + "Register a price via the ModelPriceTable to enable cost tracking for this model.",
                    key, Money.zeroUsd());
        }
    }
}
