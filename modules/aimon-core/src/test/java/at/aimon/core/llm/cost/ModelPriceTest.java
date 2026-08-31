package at.aimon.core.llm.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.TokenUsage;

@DisplayName("ModelPrice Tests")
class ModelPriceTest {

    @Nested
    @DisplayName("costOf")
    class CostOf {

        @Test
        @DisplayName("prices prompt and completion tokens per million")
        void pricesPromptAndCompletion() {
            ModelPrice price = ModelPrice.perMillionUsd(3.00, 15.00);

            // (3.00 * 1000 + 15.00 * 500) / 1_000_000 = (3000 + 7500) / 1_000_000 = 0.0105
            Money cost = price.costOf(TokenUsage.of(1_000, 500, 1_500));

            assertThat(cost.getCurrency()).isEqualTo(Money.USD);
            assertThat(cost.getAmount()).isEqualByComparingTo("0.0105");
        }

        @Test
        @DisplayName("ignores the total-tokens field, using only the prompt/completion split")
        void ignoresTotalField() {
            ModelPrice price = ModelPrice.perMillionUsd(2.00, 4.00);

            // total is deliberately larger than prompt+completion; only the split is priced.
            Money cost = price.costOf(TokenUsage.of(1_000_000, 1_000_000, 5_000_000));

            // (2.00 + 4.00) = 6.00
            assertThat(cost.getAmount()).isEqualByComparingTo("6.00");
        }

        @Test
        @DisplayName("zero token usage yields zero cost")
        void zeroUsageYieldsZeroCost() {
            ModelPrice price = ModelPrice.perMillionUsd(3.00, 15.00);

            assertThat(price.costOf(TokenUsage.empty()).isZero()).isTrue();
        }

        @Test
        @DisplayName("rejects null usage")
        void rejectsNullUsage() {
            assertThatThrownBy(() -> ModelPrice.perMillionUsd(1.0, 1.0).costOf(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("exposes the registered input and output prices")
        void exposesPrices() {
            ModelPrice price = ModelPrice.builder().inputPricePerMillionTokens(Money.usd(3.00))
                    .outputPricePerMillionTokens(Money.usd(15.00)).build();

            assertThat(price.getInputPricePerMillionTokens()).isEqualTo(Money.usd(3.00));
            assertThat(price.getOutputPricePerMillionTokens()).isEqualTo(Money.usd(15.00));
        }

        @Test
        @DisplayName("rejects null input price")
        void rejectsNullInput() {
            assertThatThrownBy(() -> ModelPrice.builder().outputPricePerMillionTokens(Money.usd(1.0)).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects mixed-currency input and output prices")
        void rejectsMixedCurrency() {
            assertThatThrownBy(() -> ModelPrice.builder().inputPricePerMillionTokens(Money.usd(3.00))
                    .outputPricePerMillionTokens(Money.of(java.math.BigDecimal.valueOf(15.0), "EUR")).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("same prices are equal and hash identically")
        void samePricesEqual() {
            ModelPrice a = ModelPrice.perMillionUsd(3.00, 15.00);
            ModelPrice b = ModelPrice.perMillionUsd(3.00, 15.00);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different prices are not equal")
        void differentPricesNotEqual() {
            assertThat(ModelPrice.perMillionUsd(3.00, 15.00)).isNotEqualTo(ModelPrice.perMillionUsd(3.00, 16.00));
        }
    }
}
