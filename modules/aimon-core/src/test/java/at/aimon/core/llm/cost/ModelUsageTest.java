package at.aimon.core.llm.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.TokenUsage;

@DisplayName("ModelUsage Tests")
class ModelUsageTest {

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("folds usage and cost of the same currency")
        void foldsSameCurrency() {
            ModelUsage usage = ModelUsage.of("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01))
                    .add(TokenUsage.of(2_000, 1_000, 3_000), Money.usd(0.02));

            assertThat(usage.getModelName()).isEqualTo("gpt-4o");
            assertThat(usage.getTokenUsage().getTotalTokens()).isEqualTo(4_500);
            assertThat(usage.getCost().getAmount()).isEqualByComparingTo("0.03");
        }

        @Test
        @DisplayName("rejects null additional usage")
        void rejectsNullUsage() {
            ModelUsage usage = ModelUsage.of("gpt-4o", TokenUsage.empty(), Money.zeroUsd());
            assertThatThrownBy(() -> usage.add(null, Money.zeroUsd())).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("addCost currency tolerance")
    class AddCostCurrencyTolerance {

        @Test
        @DisplayName("a zero-USD seed is superseded by the first real amount of another currency")
        void zeroUsdSupersededByOtherCurrency() {
            Money folded = ModelUsage.addCost(Money.zeroUsd(), Money.of(new BigDecimal("2.00"), "EUR"));

            assertThat(folded.getCurrency()).isEqualTo("EUR");
            assertThat(folded.getAmount()).isEqualByComparingTo("2.00");
        }

        @Test
        @DisplayName("same-currency amounts are summed normally")
        void sameCurrencySummed() {
            Money folded = ModelUsage.addCost(Money.usd(1.00), Money.usd(2.00));

            assertThat(folded.getAmount()).isEqualByComparingTo("3.00");
        }

        @Test
        @DisplayName("a non-zero left operand rejects a different-currency addition")
        void nonZeroLeftRejectsCrossCurrency() {
            assertThatThrownBy(() -> ModelUsage.addCost(Money.usd(1.00), Money.of(BigDecimal.ONE, "EUR")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Currency mismatch");
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("same roll-ups are equal")
        void sameRollUpsEqual() {
            ModelUsage a = ModelUsage.of("gpt-4o", TokenUsage.of(1, 1, 2), Money.usd(0.01));
            ModelUsage b = ModelUsage.of("gpt-4o", TokenUsage.of(1, 1, 2), Money.usd(0.01));

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different model names are not equal")
        void differentNamesNotEqual() {
            assertThat(ModelUsage.of("a", TokenUsage.of(1, 1, 2), Money.zeroUsd()))
                    .isNotEqualTo(ModelUsage.of("b", TokenUsage.of(1, 1, 2), Money.zeroUsd()));
        }
    }
}
