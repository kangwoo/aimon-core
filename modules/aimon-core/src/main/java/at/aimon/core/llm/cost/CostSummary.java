package at.aimon.core.llm.cost;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.llm.TokenUsage;

/**
 * Immutable accumulation of LLM cost over an execution, broken down per model.
 *
 * <p>
 * A summary is grown functionally: {@link #record(String, TokenUsage, Money)} returns a <em>new</em> summary with the
 * call folded in. This copy-on-write shape lets the executor keep a single reassignable field
 * ({@code scope.costSummary = scope.costSummary.record(...)}) without any shared mutable state, and lets the finished
 * summary be handed out on the execution result as a safe immutable value.
 *
 * <p>
 * Alongside the per-model {@link ModelUsage} breakdown it maintains a running {@link #getTotalCost() total cost} and
 * {@link #getTotalTokenUsage() total token usage}. In the common single-model execution the breakdown holds exactly one
 * entry; the map structure keeps mixed-model executions (e.g. a cheaper summariser model used for compaction)
 * first-class.
 */
public final class CostSummary {

    private static final CostSummary EMPTY = new CostSummary(new LinkedHashMap<>(), Money.zeroUsd(),
            TokenUsage.empty());

    private final Map<String, ModelUsage> modelUsages;
    private final Money totalCost;
    private final TokenUsage totalTokenUsage;

    private CostSummary(Map<String, ModelUsage> modelUsages, Money totalCost, TokenUsage totalTokenUsage) {
        this.modelUsages = Collections.unmodifiableMap(modelUsages);
        this.totalCost = totalCost;
        this.totalTokenUsage = totalTokenUsage;
    }

    /**
     * @return the empty summary — zero cost, zero tokens, no per-model entries
     */
    public static CostSummary empty() {
        return EMPTY;
    }

    /**
     * Returns a new summary with one LLM call folded in.
     *
     * <p>
     * The usage and cost are added to the running totals and to the {@link ModelUsage} for {@code modelName} (a new
     * entry is created on first sight of the model). A null or empty model name is bucketed under the constant
     * {@code "unknown"} key so accounting is never silently dropped.
     *
     * @param modelName
     *            the model that produced the call (may be null or empty)
     * @param usage
     *            the token usage of the call (must not be null)
     * @param cost
     *            the estimated cost of the call (must not be null)
     * @return a new {@link CostSummary} including this call
     */
    public CostSummary record(String modelName, TokenUsage usage, Money cost) {
        Objects.requireNonNull(usage, "usage cannot be null");
        Objects.requireNonNull(cost, "cost cannot be null");
        final String key = (modelName == null || modelName.isEmpty()) ? "unknown" : modelName;

        final LinkedHashMap<String, ModelUsage> merged = new LinkedHashMap<>(modelUsages);
        final ModelUsage existing = merged.get(key);
        if (existing == null) {
            merged.put(key, ModelUsage.of(key, usage, cost));
        } else {
            merged.put(key, existing.add(usage, cost));
        }
        return new CostSummary(merged, ModelUsage.addCost(totalCost, cost), totalTokenUsage.add(usage));
    }

    /**
     * @return the total cost across all models (never null; {@link Money#zeroUsd()} when empty)
     */
    public Money getTotalCost() {
        return totalCost;
    }

    /**
     * @return the total token usage across all models (never null)
     */
    public TokenUsage getTotalTokenUsage() {
        return totalTokenUsage;
    }

    /**
     * @return an unmodifiable, insertion-ordered view of the per-model roll-ups keyed by model name
     */
    public Map<String, ModelUsage> getModelUsages() {
        return modelUsages;
    }

    /**
     * @return true if no call has been recorded
     */
    public boolean isEmpty() {
        return modelUsages.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CostSummary that = (CostSummary) o;
        return modelUsages.equals(that.modelUsages) && totalCost.equals(that.totalCost)
                && totalTokenUsage.equals(that.totalTokenUsage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelUsages, totalCost, totalTokenUsage);
    }

    @Override
    public String toString() {
        return "CostSummary{totalCost=" + totalCost + ", totalTokens=" + totalTokenUsage.getTotalTokens() + ", models="
                + modelUsages.keySet() + '}';
    }
}
