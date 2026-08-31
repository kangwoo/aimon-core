package at.aimon.core.llm.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.TokenUsage;

@DisplayName("TablePricedCostEstimator Tests")
class TablePricedCostEstimatorTest {

    @Nested
    @DisplayName("Known models")
    class KnownModels {

        @Test
        @DisplayName("prices a known model via its table entry")
        void pricesKnownModel() {
            TablePricedCostEstimator estimator = new TablePricedCostEstimator(
                    InMemoryModelPriceTable.builder().register("known", ModelPrice.perMillionUsd(3.00, 15.00)).build());

            // (3.00 * 1000 + 15.00 * 500) / 1_000_000 = 0.0105
            Money cost = estimator.estimate("known", TokenUsage.of(1_000, 500, 1_500));

            assertThat(cost.getAmount()).isEqualByComparingTo("0.0105");
        }

        @Test
        @DisplayName("withDefaultPrices prices a default-registered model")
        void withDefaultPrices() {
            TablePricedCostEstimator estimator = TablePricedCostEstimator.withDefaultPrices();

            Money cost = estimator.estimate("gpt-4o", TokenUsage.of(1_000_000, 0, 1_000_000));

            // 2.50 per 1M input tokens
            assertThat(cost.getAmount()).isEqualByComparingTo("2.50");
        }
    }

    @Nested
    @DisplayName("Unknown models")
    class UnknownModels {

        @Test
        @DisplayName("unknown model is priced at zero USD")
        void unknownIsZero() {
            TablePricedCostEstimator estimator = new TablePricedCostEstimator(ModelPriceTable.EMPTY);

            Money cost = estimator.estimate("mystery", TokenUsage.of(1_000, 500, 1_500));

            assertThat(cost.isZero()).isTrue();
            assertThat(cost.getCurrency()).isEqualTo(Money.USD);
        }

        @Test
        @DisplayName("repeated unknown-model estimates stay zero (warn-once does not affect the value)")
        void repeatedUnknownStaysZero() {
            TablePricedCostEstimator estimator = new TablePricedCostEstimator(ModelPriceTable.EMPTY);

            assertThat(estimator.estimate("mystery", TokenUsage.of(10, 10, 20)).isZero()).isTrue();
            assertThat(estimator.estimate("mystery", TokenUsage.of(10, 10, 20)).isZero()).isTrue();
        }

        @Test
        @DisplayName("null model name is priced at zero USD without throwing")
        void nullModelIsZero() {
            TablePricedCostEstimator estimator = TablePricedCostEstimator.withDefaultPrices();

            assertThat(estimator.estimate(null, TokenUsage.of(10, 10, 20)).isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("Contract")
    class Contract {

        @Test
        @DisplayName("rejects null price table")
        void rejectsNullTable() {
            assertThatThrownBy(() -> new TablePricedCostEstimator(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null usage")
        void rejectsNullUsage() {
            assertThatThrownBy(() -> TablePricedCostEstimator.withDefaultPrices().estimate("gpt-4o", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("CostEstimator.NOOP")
    class Noop {

        @Test
        @DisplayName("NOOP prices every call at zero USD")
        void noopIsZero() {
            assertThat(CostEstimator.NOOP.estimate("gpt-4o", TokenUsage.of(1_000_000, 1_000_000, 2_000_000)).isZero())
                    .isTrue();
        }
    }
}
