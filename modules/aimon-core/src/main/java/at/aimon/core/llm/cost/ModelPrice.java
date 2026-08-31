package at.aimon.core.llm.cost;

import java.math.BigDecimal;
import java.util.Objects;

import at.aimon.core.llm.TokenUsage;

/**
 * Immutable per-model price: the cost of one million prompt (input) tokens and one million completion (output) tokens.
 *
 * <p>
 * Prices are expressed per <em>million</em> tokens because that is the unit LLM vendors publish and the most
 * human-legible scale to register. The two prices must share a {@link Money#getCurrency() currency}; a
 * {@link ModelPrice} then prices a concrete {@link TokenUsage} in that same currency via {@link #costOf(TokenUsage)}.
 *
 * <p>
 * Construct via {@link #builder()} or the {@link #perMillionUsd(double, double)} convenience factory.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     ModelPrice price = ModelPrice.perMillionUsd(3.00, 15.00); // $3 / $15 per 1M in / out tokens
 *     Money cost = price.costOf(TokenUsage.of(1_000, 500, 1_500));
 * }
 * </pre>
 */
public final class ModelPrice {

    private final Money inputPricePerMillionTokens;
    private final Money outputPricePerMillionTokens;

    private ModelPrice(Builder builder) {
        this.inputPricePerMillionTokens = Objects.requireNonNull(builder.inputPricePerMillionTokens,
                "inputPricePerMillionTokens cannot be null");
        this.outputPricePerMillionTokens = Objects.requireNonNull(builder.outputPricePerMillionTokens,
                "outputPricePerMillionTokens cannot be null");
        if (!inputPricePerMillionTokens.getCurrency().equals(outputPricePerMillionTokens.getCurrency())) {
            throw new IllegalArgumentException("Input and output prices must share a currency: "
                    + inputPricePerMillionTokens.getCurrency() + " vs " + outputPricePerMillionTokens.getCurrency());
        }
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience factory for US-dollar per-million-token prices.
     *
     * @param inputPerMillion
     *            USD cost per one million prompt tokens
     * @param outputPerMillion
     *            USD cost per one million completion tokens
     * @return a new {@link ModelPrice}
     */
    public static ModelPrice perMillionUsd(double inputPerMillion, double outputPerMillion) {
        return builder().inputPricePerMillionTokens(Money.usd(inputPerMillion))
                .outputPricePerMillionTokens(Money.usd(outputPerMillion)).build();
    }

    /**
     * Prices the given token usage.
     *
     * <p>
     * {@code cost = (inputPrice * promptTokens + outputPrice * completionTokens) / 1_000_000}, computed exactly and
     * rounded to {@link Money#COST_SCALE} decimal places. The {@code totalTokens} field of the usage is intentionally
     * ignored: only the prompt/completion split carries a price.
     *
     * @param usage
     *            the token usage to price (must not be null)
     * @return the cost in this price's currency (never null)
     */
    public Money costOf(TokenUsage usage) {
        Objects.requireNonNull(usage, "usage cannot be null");
        final Money inputCost = inputPricePerMillionTokens.multiply(BigDecimal.valueOf(usage.getPromptTokens()));
        final Money outputCost = outputPricePerMillionTokens.multiply(BigDecimal.valueOf(usage.getCompletionTokens()));
        return inputCost.add(outputCost).divide(Money.MILLION);
    }

    /**
     * @return the price of one million prompt tokens
     */
    public Money getInputPricePerMillionTokens() {
        return inputPricePerMillionTokens;
    }

    /**
     * @return the price of one million completion tokens
     */
    public Money getOutputPricePerMillionTokens() {
        return outputPricePerMillionTokens;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ModelPrice that = (ModelPrice) o;
        return inputPricePerMillionTokens.equals(that.inputPricePerMillionTokens)
                && outputPricePerMillionTokens.equals(that.outputPricePerMillionTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inputPricePerMillionTokens, outputPricePerMillionTokens);
    }

    @Override
    public String toString() {
        return "ModelPrice{in=" + inputPricePerMillionTokens + "/M, out=" + outputPricePerMillionTokens + "/M}";
    }

    /** Builder for {@link ModelPrice}. */
    public static final class Builder {
        private Money inputPricePerMillionTokens;
        private Money outputPricePerMillionTokens;

        private Builder() {
        }

        /**
         * @param inputPricePerMillionTokens
         *            the price of one million prompt tokens (must not be null)
         * @return this builder
         */
        public Builder inputPricePerMillionTokens(Money inputPricePerMillionTokens) {
            this.inputPricePerMillionTokens = inputPricePerMillionTokens;
            return this;
        }

        /**
         * @param outputPricePerMillionTokens
         *            the price of one million completion tokens (must not be null)
         * @return this builder
         */
        public Builder outputPricePerMillionTokens(Money outputPricePerMillionTokens) {
            this.outputPricePerMillionTokens = outputPricePerMillionTokens;
            return this;
        }

        /**
         * @return a new {@link ModelPrice}
         * @throws IllegalArgumentException
         *             if the input and output prices use different currencies
         */
        public ModelPrice build() {
            return new ModelPrice(this);
        }
    }
}
