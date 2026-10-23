package at.aimon.core.llm.cost;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.TokenUsage;

/**
 * Immutable per-model price: the cost of one million prompt (input) tokens and one million completion (output) tokens,
 * plus — optionally — the cost of one million prompt tokens read from and written to the provider's prompt cache.
 *
 * <p>
 * Prices are expressed per <em>million</em> tokens because that is the unit LLM vendors publish and the most
 * human-legible scale to register. The two prices must share a {@link Money#getCurrency() currency}; a
 * {@link ModelPrice} then prices a concrete {@link TokenUsage} in that same currency via {@link #costOf(TokenUsage)}.
 *
 * <p>
 * Construct via {@link #builder()} or the {@link #perMillionUsd(double, double)} /
 * {@link #perMillionUsd(double, double, double, double)} convenience factories.
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
    private final Money cacheReadPricePerMillionTokens;
    private final Money cacheWritePricePerMillionTokens;

    private ModelPrice(Builder builder) {
        this.inputPricePerMillionTokens = Objects.requireNonNull(builder.inputPricePerMillionTokens,
                "inputPricePerMillionTokens cannot be null");
        this.outputPricePerMillionTokens = Objects.requireNonNull(builder.outputPricePerMillionTokens,
                "outputPricePerMillionTokens cannot be null");
        if (!inputPricePerMillionTokens.getCurrency().equals(outputPricePerMillionTokens.getCurrency())) {
            throw new IllegalArgumentException("Input and output prices must share a currency: "
                    + inputPricePerMillionTokens.getCurrency() + " vs " + outputPricePerMillionTokens.getCurrency());
        }
        this.cacheReadPricePerMillionTokens = sameCurrency(builder.cacheReadPricePerMillionTokens, "cache read");
        this.cacheWritePricePerMillionTokens = sameCurrency(builder.cacheWritePricePerMillionTokens, "cache write");
    }

    private Money sameCurrency(Money price, String label) {
        if (price != null && !price.getCurrency().equals(inputPricePerMillionTokens.getCurrency())) {
            throw new IllegalArgumentException("The " + label + " price must share the input price's currency: "
                    + price.getCurrency() + " vs " + inputPricePerMillionTokens.getCurrency());
        }
        return price;
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
     * Convenience factory for US-dollar per-million-token prices with prompt-cache rates.
     *
     * @param inputPerMillion
     *            USD cost per one million uncached prompt tokens
     * @param outputPerMillion
     *            USD cost per one million completion tokens
     * @param cacheReadPerMillion
     *            USD cost per one million prompt tokens read from the cache
     * @param cacheWritePerMillion
     *            USD cost per one million prompt tokens written to the cache
     * @return a new {@link ModelPrice}
     */
    public static ModelPrice perMillionUsd(double inputPerMillion, double outputPerMillion, double cacheReadPerMillion,
            double cacheWritePerMillion) {
        return builder().inputPricePerMillionTokens(Money.usd(inputPerMillion))
                .outputPricePerMillionTokens(Money.usd(outputPerMillion))
                .cacheReadPricePerMillionTokens(Money.usd(cacheReadPerMillion))
                .cacheWritePricePerMillionTokens(Money.usd(cacheWritePerMillion)).build();
    }

    /**
     * Prices the given token usage.
     *
     * <p>
     * {@code cost = (inputPrice * promptTokens + outputPrice * completionTokens) / 1_000_000}, computed exactly and
     * rounded to {@link Money#COST_SCALE} decimal places. The {@code totalTokens} field of the usage is intentionally
     * ignored: only the prompt/completion split carries a price.
     *
     * <p>
     * {@link TokenUsage#getReasoningTokens()} is ignored for a different reason, and one worth stating so that a
     * reader does not take it for an oversight: reasoning tokens are a <em>subset</em> of the completion tokens, not
     * an addition to them (OpenAI reports them inside {@code output_tokens_details}), so they are already priced by
     * the {@code outputPrice * completionTokens} term. Adding them here would bill them twice.
     *
     * <h4>Prompt cache</h4>
     *
     * <p>
     * {@link TokenUsage#getPromptTokens()} is the whole input context, cache included, and the cache counters are
     * subsets of it. A cache part is moved out of the input term and priced at its own rate only when <em>both</em>
     * its counter is measured and this price has a rate for it; otherwise it stays in the prompt and is priced at the
     * input rate, as every prompt token was before cache rates existed. So a price with no cache rates, or a usage
     * with unmeasured counters, costs exactly {@code inputPrice * promptTokens}. A usage whose measured cache counters
     * exceed its prompt is inconsistent and is priced the same plain way rather than going negative.
     *
     * @param usage
     *            the token usage to price (must not be null)
     * @return the cost in this price's currency (never null)
     */
    public Money costOf(TokenUsage usage) {
        Objects.requireNonNull(usage, "usage cannot be null");
        final long cacheRead = cachePart(usage.getCacheReadTokens().isPresent(), cacheReadPricePerMillionTokens,
                usage.getCacheReadTokens().orElse(0));
        final long cacheWrite = cachePart(usage.getCacheWriteTokens().isPresent(), cacheWritePricePerMillionTokens,
                usage.getCacheWriteTokens().orElse(0));
        final long uncached = (long) usage.getPromptTokens() - cacheRead - cacheWrite;
        Money cost = outputPricePerMillionTokens.multiply(BigDecimal.valueOf(usage.getCompletionTokens()));
        if (uncached < 0) {
            return cost.add(inputPricePerMillionTokens.multiply(BigDecimal.valueOf(usage.getPromptTokens())))
                    .divide(Money.MILLION);
        }
        cost = cost.add(inputPricePerMillionTokens.multiply(BigDecimal.valueOf(uncached)));
        if (cacheRead > 0) {
            cost = cost.add(cacheReadPricePerMillionTokens.multiply(BigDecimal.valueOf(cacheRead)));
        }
        if (cacheWrite > 0) {
            cost = cost.add(cacheWritePricePerMillionTokens.multiply(BigDecimal.valueOf(cacheWrite)));
        }
        return cost.divide(Money.MILLION);
    }

    /** The tokens priced at a cache rate: zero unless the counter is measured and the rate is set. */
    private static long cachePart(boolean measured, Money rate, int tokens) {
        return measured && rate != null ? tokens : 0L;
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

    /**
     * @return the price of one million prompt tokens read from the cache, or empty when this price has none (those
     *         tokens are then priced at the input rate)
     */
    public Optional<Money> getCacheReadPricePerMillionTokens() {
        return Optional.ofNullable(cacheReadPricePerMillionTokens);
    }

    /**
     * @return the price of one million prompt tokens written to the cache, or empty when this price has none (those
     *         tokens are then priced at the input rate)
     */
    public Optional<Money> getCacheWritePricePerMillionTokens() {
        return Optional.ofNullable(cacheWritePricePerMillionTokens);
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
                && outputPricePerMillionTokens.equals(that.outputPricePerMillionTokens)
                && Objects.equals(cacheReadPricePerMillionTokens, that.cacheReadPricePerMillionTokens)
                && Objects.equals(cacheWritePricePerMillionTokens, that.cacheWritePricePerMillionTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inputPricePerMillionTokens, outputPricePerMillionTokens, cacheReadPricePerMillionTokens,
                cacheWritePricePerMillionTokens);
    }

    @Override
    public String toString() {
        return "ModelPrice{in=" + inputPricePerMillionTokens + "/M, out=" + outputPricePerMillionTokens + "/M"
                + (cacheReadPricePerMillionTokens == null ? "" : ", cacheRead=" + cacheReadPricePerMillionTokens + "/M")
                + (cacheWritePricePerMillionTokens == null
                        ? ""
                        : ", cacheWrite=" + cacheWritePricePerMillionTokens + "/M")
                + '}';
    }

    /** Builder for {@link ModelPrice}. */
    public static final class Builder {
        private Money inputPricePerMillionTokens;
        private Money outputPricePerMillionTokens;
        private Money cacheReadPricePerMillionTokens;
        private Money cacheWritePricePerMillionTokens;

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
         * @param cacheReadPricePerMillionTokens
         *            the price of one million prompt tokens read from the cache, or null for none (optional)
         * @return this builder
         */
        public Builder cacheReadPricePerMillionTokens(Money cacheReadPricePerMillionTokens) {
            this.cacheReadPricePerMillionTokens = cacheReadPricePerMillionTokens;
            return this;
        }

        /**
         * @param cacheWritePricePerMillionTokens
         *            the price of one million prompt tokens written to the cache, or null for none (optional)
         * @return this builder
         */
        public Builder cacheWritePricePerMillionTokens(Money cacheWritePricePerMillionTokens) {
            this.cacheWritePricePerMillionTokens = cacheWritePricePerMillionTokens;
            return this;
        }

        /**
         * @return a new {@link ModelPrice}
         * @throws IllegalArgumentException
         *             if the prices use different currencies
         */
        public ModelPrice build() {
            return new ModelPrice(this);
        }
    }
}
