package at.aimon.core.llm.cost;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link ModelPriceTable} backed by an in-memory map of explicit entries plus case-insensitive prefix patterns.
 *
 * <p>
 * Built via {@link Builder}. Look-ups first consult the exact entries, then the registered <em>prefix patterns</em> in
 * registration order; if nothing matches the model is reported as unknown ({@link Optional#empty()}). Unlike
 * {@link at.aimon.core.llm.InMemoryModelContextWindowRegistry} there is deliberately no fallback default price — an
 * unpriced model must be visible as "unknown" so cost is never silently fabricated from a guessed rate.
 *
 * <p>
 * Mirrors the structure of {@link at.aimon.core.llm.InMemoryModelContextWindowRegistry} so the two registries read the
 * same way.
 */
public final class InMemoryModelPriceTable implements ModelPriceTable {

    private final Map<String, ModelPrice> exactEntries;
    private final Map<String, ModelPrice> prefixEntries;

    private InMemoryModelPriceTable(Builder builder) {
        this.exactEntries = Map.copyOf(builder.exactEntries);
        this.prefixEntries = new LinkedHashMap<>(builder.prefixEntries);
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a table pre-populated with representative list prices (USD per one million tokens) for common OpenAI and
     * Anthropic models.
     *
     * <p>
     * These figures are convenience defaults, not a billing source of truth — vendor prices change and vary by tier.
     * Callers that need authoritative numbers should build their own table (or override individual entries) via
     * {@link #builder()}.
     *
     * @return a table with framework-default model prices
     */
    public static InMemoryModelPriceTable withDefaults() {
        return builder()
                // OpenAI (exact + prefix so dated snapshots such as gpt-4o-2024-08-06 also resolve). Cached input is
                // half price; OpenAI bills no cache writes and reports them as 0, so the write rate is moot (0).
                .registerPrefix("gpt-4o-mini", ModelPrice.perMillionUsd(0.15, 0.60, 0.075, 0.0))
                .registerPrefix("gpt-4o", ModelPrice.perMillionUsd(2.50, 10.00, 1.25, 0.0))
                .registerPrefix("gpt-4-turbo", ModelPrice.perMillionUsd(10.00, 30.00))
                .registerPrefix("o1-mini", ModelPrice.perMillionUsd(1.10, 4.40, 0.55, 0.0))
                .registerPrefix("o1", ModelPrice.perMillionUsd(15.00, 60.00, 7.50, 0.0))
                // Anthropic Claude: cache reads at 0.1x input, five-minute cache writes at 1.25x input.
                .registerPrefix("claude-3-5-haiku", ModelPrice.perMillionUsd(0.80, 4.00, 0.08, 1.00))
                .registerPrefix("claude-3-5-sonnet", ModelPrice.perMillionUsd(3.00, 15.00, 0.30, 3.75))
                .registerPrefix("claude-3-opus", ModelPrice.perMillionUsd(15.00, 75.00, 1.50, 18.75))
                .registerPrefix("claude-3-haiku", ModelPrice.perMillionUsd(0.25, 1.25, 0.03, 0.30))
                .registerPrefix("claude-3-7-sonnet", ModelPrice.perMillionUsd(3.00, 15.00, 0.30, 3.75))
                .registerPrefix("claude-haiku-4", ModelPrice.perMillionUsd(1.00, 5.00, 0.10, 1.25))
                .registerPrefix("claude-sonnet-4", ModelPrice.perMillionUsd(3.00, 15.00, 0.30, 3.75))
                // Opus 4.5 dropped to $5 / $25; registered before the claude-opus-4 prefix that would otherwise match
                // it.
                .registerPrefix("claude-opus-4-5", ModelPrice.perMillionUsd(5.00, 25.00, 0.50, 6.25))
                .registerPrefix("claude-opus-4", ModelPrice.perMillionUsd(15.00, 75.00, 1.50, 18.75)).build();
    }

    @Override
    public Optional<ModelPrice> priceOf(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return Optional.empty();
        }
        final ModelPrice exact = exactEntries.get(modelName);
        if (exact != null) {
            return Optional.of(exact);
        }
        final String lower = modelName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ModelPrice> entry : prefixEntries.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** Builder for {@link InMemoryModelPriceTable}. */
    public static final class Builder {
        private final ConcurrentHashMap<String, ModelPrice> exactEntries = new ConcurrentHashMap<>();
        private final LinkedHashMap<String, ModelPrice> prefixEntries = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Registers an exact model-name to price mapping.
         *
         * @param modelName
         *            the exact model identifier (must not be null)
         * @param price
         *            the price (must not be null)
         * @return this builder
         */
        public Builder register(String modelName, ModelPrice price) {
            Objects.requireNonNull(modelName, "modelName cannot be null");
            Objects.requireNonNull(price, "price cannot be null");
            exactEntries.put(modelName, price);
            return this;
        }

        /**
         * Registers a case-insensitive prefix match.
         *
         * <p>
         * Patterns are evaluated in registration order; the first match wins. Register more specific prefixes
         * (e.g. {@code gpt-4o-mini}) before their broader siblings (e.g. {@code gpt-4o}).
         *
         * @param modelNamePrefix
         *            the case-insensitive model-name prefix (must not be null)
         * @param price
         *            the price (must not be null)
         * @return this builder
         */
        public Builder registerPrefix(String modelNamePrefix, ModelPrice price) {
            Objects.requireNonNull(modelNamePrefix, "modelNamePrefix cannot be null");
            Objects.requireNonNull(price, "price cannot be null");
            prefixEntries.put(modelNamePrefix.toLowerCase(Locale.ROOT), price);
            return this;
        }

        /**
         * @return a new {@link InMemoryModelPriceTable}
         */
        public InMemoryModelPriceTable build() {
            return new InMemoryModelPriceTable(this);
        }
    }
}
