package at.aimon.core.llm.cost;

import java.util.Objects;

import at.aimon.core.llm.TokenUsage;

/**
 * Immutable per-model roll-up: the accumulated {@link TokenUsage} and {@link Money cost} attributed to a single model
 * name within one {@link CostSummary}.
 *
 * <p>
 * Instances are combined functionally: {@link #add(TokenUsage, Money)} returns a new {@link ModelUsage} with the extra
 * usage and cost folded in. There is no mutation.
 */
public final class ModelUsage {

    private final String modelName;
    private final TokenUsage tokenUsage;
    private final Money cost;

    private ModelUsage(String modelName, TokenUsage tokenUsage, Money cost) {
        this.modelName = Objects.requireNonNull(modelName, "modelName cannot be null");
        this.tokenUsage = Objects.requireNonNull(tokenUsage, "tokenUsage cannot be null");
        this.cost = Objects.requireNonNull(cost, "cost cannot be null");
    }

    /**
     * Creates a model usage roll-up.
     *
     * @param modelName
     *            the model identifier (must not be null)
     * @param tokenUsage
     *            the accumulated token usage (must not be null)
     * @param cost
     *            the accumulated cost (must not be null)
     * @return a new {@link ModelUsage}
     */
    public static ModelUsage of(String modelName, TokenUsage tokenUsage, Money cost) {
        return new ModelUsage(modelName, tokenUsage, cost);
    }

    /**
     * Returns a new roll-up with the given usage and cost added to this one.
     *
     * @param additionalUsage
     *            the token usage to add (must not be null)
     * @param additionalCost
     *            the cost to add — must share this roll-up's currency (must not be null)
     * @return a new {@link ModelUsage}
     * @throws IllegalArgumentException
     *             if the currencies differ
     */
    public ModelUsage add(TokenUsage additionalUsage, Money additionalCost) {
        Objects.requireNonNull(additionalUsage, "additionalUsage cannot be null");
        Objects.requireNonNull(additionalCost, "additionalCost cannot be null");
        return new ModelUsage(modelName, tokenUsage.add(additionalUsage), addCost(cost, additionalCost));
    }

    /**
     * Folds two amounts, tolerating a zero left operand of a different currency so that an initial zero-USD cost can be
     * superseded by the first real priced amount in another currency.
     */
    static Money addCost(Money current, Money addition) {
        if (current.isZero() && !current.getCurrency().equals(addition.getCurrency())) {
            return addition;
        }
        return current.add(addition);
    }

    /**
     * @return the model identifier
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * @return the accumulated token usage
     */
    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    /**
     * @return the accumulated cost
     */
    public Money getCost() {
        return cost;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ModelUsage that = (ModelUsage) o;
        return modelName.equals(that.modelName) && tokenUsage.equals(that.tokenUsage) && cost.equals(that.cost);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelName, tokenUsage, cost);
    }

    @Override
    public String toString() {
        return "ModelUsage{model=" + modelName + ", " + tokenUsage + ", cost=" + cost + '}';
    }
}
