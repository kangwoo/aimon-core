package at.aimon.core.llm.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.TokenUsage;

/**
 * Prompt-cache rates on {@link ModelPrice}: a cache part leaves the input term only when it is both measured and
 * priced, so nothing is billed twice and nothing unmeasured is guessed.
 */
@DisplayName("ModelPrice - prompt cache")
class ModelPriceCacheTest {

    // $3 in, $15 out, $0.30 cache read, $3.75 cache write per million.
    private static final ModelPrice PRICE = ModelPrice.perMillionUsd(3.00, 15.00, 0.30, 3.75);

    @Test
    @DisplayName("measured cache reads and writes are priced at their own rates and taken out of the input")
    void cachePartsArePricedSeparately() {
        // prompt 1,000,000 = 600,000 uncached + 300,000 read + 100,000 write; output 0
        final TokenUsage usage = TokenUsage.of(1_000_000, 0, 1_000_000).withCacheTokens(300_000, 100_000);

        // 0.6 * 3.00 + 0.3 * 0.30 + 0.1 * 3.75 = 1.80 + 0.09 + 0.375 = 2.265
        assertThat(PRICE.costOf(usage).getAmount()).isEqualByComparingTo("2.265");
    }

    @Test
    @DisplayName("unmeasured counters leave the whole prompt at the input rate")
    void unmeasuredCountersAreNotGuessed() {
        final TokenUsage usage = TokenUsage.of(1_000_000, 0, 1_000_000);

        assertThat(PRICE.costOf(usage).getAmount()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("an unmeasured write still lets a measured read be priced")
    void partiallyMeasured() {
        final TokenUsage usage = TokenUsage.of(1_000_000, 0, 1_000_000).withCacheTokens(500_000, null);

        // 0.5 * 3.00 + 0.5 * 0.30 = 1.65
        assertThat(PRICE.costOf(usage).getAmount()).isEqualByComparingTo("1.65");
    }

    @Test
    @DisplayName("a price without cache rates prices every prompt token at the input rate, as before")
    void noCacheRatesKeepsThePlainFormula() {
        final TokenUsage usage = TokenUsage.of(1_000_000, 1_000_000, 2_000_000).withCacheTokens(300_000, 100_000);

        assertThat(ModelPrice.perMillionUsd(3.00, 15.00).costOf(usage).getAmount()).isEqualByComparingTo("18.00");
    }

    @Test
    @DisplayName("cache counters exceeding the prompt are inconsistent and fall back to the plain formula")
    void inconsistentCountersFallBack() {
        final TokenUsage usage = TokenUsage.of(100, 0, 100).withCacheTokens(80, 80);

        // 100 * 3.00 / 1M
        assertThat(PRICE.costOf(usage).getAmount()).isEqualByComparingTo("0.0003");
    }

    @Test
    @DisplayName("reasoning tokens remain inside the output term")
    void reasoningIsNotBilledTwice() {
        final TokenUsage usage = TokenUsage.of(0, 1_000_000, 1_000_000, 600_000);

        assertThat(PRICE.costOf(usage).getAmount()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("a write rate without a read rate prices only a measured write separately")
    void writeRateOnly() {
        final ModelPrice writeOnly = ModelPrice.builder().inputPricePerMillionTokens(Money.usd(3.00))
                .outputPricePerMillionTokens(Money.usd(15.00)).cacheWritePricePerMillionTokens(Money.usd(3.75)).build();
        final TokenUsage usage = TokenUsage.of(1_000_000, 0, 1_000_000).withCacheTokens(300_000, 100_000);

        // 0.9 * 3.00 (reads stay at the input rate) + 0.1 * 3.75 = 2.70 + 0.375
        assertThat(writeOnly.costOf(usage).getAmount()).isEqualByComparingTo("3.075");
        assertThat(writeOnly.getCacheReadPricePerMillionTokens()).isEmpty();
    }

    @Test
    @DisplayName("only a measured write is priced separately when the read is unmeasured")
    void onlyWriteMeasured() {
        final TokenUsage usage = TokenUsage.of(1_000_000, 0, 1_000_000).withCacheTokens(null, 100_000);

        // 0.9 * 3.00 + 0.1 * 3.75 = 3.075
        assertThat(PRICE.costOf(usage).getAmount()).isEqualByComparingTo("3.075");
    }

    @Test
    @DisplayName("cache rates take part in equality, scale-insensitively")
    void equality() {
        final ModelPrice sameRates = ModelPrice.builder().inputPricePerMillionTokens(Money.usd(3.00))
                .outputPricePerMillionTokens(Money.usd(15.00))
                .cacheReadPricePerMillionTokens(Money.of(new java.math.BigDecimal("0.300"), Money.USD))
                .cacheWritePricePerMillionTokens(Money.usd(3.75)).build();

        assertThat(sameRates).isEqualTo(PRICE).hasSameHashCodeAs(PRICE);
        assertThat(ModelPrice.perMillionUsd(3.00, 15.00)).isNotEqualTo(PRICE);
    }

    @Test
    @DisplayName("Opus 4.5 resolves to its own price, not the older claude-opus-4 rates")
    void opus45HasItsOwnPrice() {
        final ModelPrice opus45 = InMemoryModelPriceTable.withDefaults().priceOf("claude-opus-4-5-20251101")
                .orElseThrow();

        assertThat(opus45.getInputPricePerMillionTokens().getAmount()).isEqualByComparingTo("5.00");
        assertThat(InMemoryModelPriceTable.withDefaults().priceOf("claude-opus-4-1").orElseThrow()
                .getInputPricePerMillionTokens().getAmount()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("cache rates must share the input currency")
    void currencyMustMatch() {
        assertThatThrownBy(() -> ModelPrice.builder().inputPricePerMillionTokens(Money.usd(1))
                .outputPricePerMillionTokens(Money.usd(2))
                .cacheReadPricePerMillionTokens(Money.of(java.math.BigDecimal.ONE, "EUR")).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the default table prices Anthropic cache reads at 0.1x and writes at 1.25x the input rate")
    void defaultTableCarriesCacheRates() {
        final ModelPrice sonnet = InMemoryModelPriceTable.withDefaults().priceOf("claude-sonnet-4-5").orElseThrow();

        assertThat(sonnet.getCacheReadPricePerMillionTokens())
                .hasValueSatisfying(money -> assertThat(money.getAmount()).isEqualByComparingTo("0.30"));
        assertThat(sonnet.getCacheWritePricePerMillionTokens())
                .hasValueSatisfying(money -> assertThat(money.getAmount()).isEqualByComparingTo("3.75"));
    }
}
