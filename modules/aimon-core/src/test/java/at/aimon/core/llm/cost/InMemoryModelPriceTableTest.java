package at.aimon.core.llm.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryModelPriceTable Tests")
class InMemoryModelPriceTableTest {

    @Nested
    @DisplayName("Exact lookup")
    class ExactLookup {

        @Test
        @DisplayName("resolves an exactly-registered model")
        void resolvesExact() {
            InMemoryModelPriceTable table = InMemoryModelPriceTable.builder()
                    .register("my-model", ModelPrice.perMillionUsd(1.0, 2.0)).build();

            assertThat(table.priceOf("my-model")).contains(ModelPrice.perMillionUsd(1.0, 2.0));
        }

        @Test
        @DisplayName("exact match wins over a prefix that also matches")
        void exactWinsOverPrefix() {
            InMemoryModelPriceTable table = InMemoryModelPriceTable.builder()
                    .registerPrefix("gpt-4o", ModelPrice.perMillionUsd(2.50, 10.00))
                    .register("gpt-4o-exact", ModelPrice.perMillionUsd(9.99, 9.99)).build();

            assertThat(table.priceOf("gpt-4o-exact")).contains(ModelPrice.perMillionUsd(9.99, 9.99));
        }
    }

    @Nested
    @DisplayName("Prefix lookup")
    class PrefixLookup {

        @Test
        @DisplayName("resolves a model by case-insensitive prefix")
        void resolvesPrefixCaseInsensitive() {
            InMemoryModelPriceTable table = InMemoryModelPriceTable.builder()
                    .registerPrefix("gpt-4o", ModelPrice.perMillionUsd(2.50, 10.00)).build();

            assertThat(table.priceOf("GPT-4O-2024-08-06")).contains(ModelPrice.perMillionUsd(2.50, 10.00));
        }

        @Test
        @DisplayName("first registered prefix wins when several would match")
        void firstRegisteredPrefixWins() {
            InMemoryModelPriceTable table = InMemoryModelPriceTable.builder()
                    .registerPrefix("gpt-4o-mini", ModelPrice.perMillionUsd(0.15, 0.60))
                    .registerPrefix("gpt-4o", ModelPrice.perMillionUsd(2.50, 10.00)).build();

            assertThat(table.priceOf("gpt-4o-mini-2024")).contains(ModelPrice.perMillionUsd(0.15, 0.60));
        }
    }

    @Nested
    @DisplayName("Unknown models")
    class UnknownModels {

        @Test
        @DisplayName("unknown model resolves to empty (no fabricated default)")
        void unknownIsEmpty() {
            InMemoryModelPriceTable table = InMemoryModelPriceTable.builder()
                    .register("known", ModelPrice.perMillionUsd(1.0, 2.0)).build();

            assertThat(table.priceOf("nope")).isEmpty();
        }

        @Test
        @DisplayName("null model name resolves to empty")
        void nullIsEmpty() {
            assertThat(InMemoryModelPriceTable.withDefaults().priceOf(null)).isEmpty();
        }

        @Test
        @DisplayName("empty model name resolves to empty")
        void emptyIsEmpty() {
            assertThat(InMemoryModelPriceTable.withDefaults().priceOf("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("withDefaults")
    class WithDefaults {

        @Test
        @DisplayName("prices a dated gpt-4o snapshot via prefix")
        void pricesGpt4oSnapshot() {
            assertThat(InMemoryModelPriceTable.withDefaults().priceOf("gpt-4o-2024-08-06"))
                    .contains(ModelPrice.perMillionUsd(2.50, 10.00));
        }

        @Test
        @DisplayName("prices claude-sonnet-4 via prefix")
        void pricesClaudeSonnet4() {
            assertThat(InMemoryModelPriceTable.withDefaults().priceOf("claude-sonnet-4-20250514"))
                    .contains(ModelPrice.perMillionUsd(3.00, 15.00));
        }

        @Test
        @DisplayName("gpt-4o-mini resolves to the mini price, not the base gpt-4o price")
        void miniDoesNotCollideWithBase() {
            assertThat(InMemoryModelPriceTable.withDefaults().priceOf("gpt-4o-mini"))
                    .contains(ModelPrice.perMillionUsd(0.15, 0.60));
        }
    }

    @Nested
    @DisplayName("EMPTY table")
    class EmptyTable {

        @Test
        @DisplayName("ModelPriceTable.EMPTY always resolves to empty")
        void emptyAlwaysEmpty() {
            assertThat(ModelPriceTable.EMPTY.priceOf("gpt-4o")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("register rejects null model name")
        void registerRejectsNullName() {
            assertThatThrownBy(
                    () -> InMemoryModelPriceTable.builder().register(null, ModelPrice.perMillionUsd(1.0, 1.0)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("registerPrefix rejects null price")
        void registerPrefixRejectsNullPrice() {
            assertThatThrownBy(() -> InMemoryModelPriceTable.builder().registerPrefix("gpt", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
