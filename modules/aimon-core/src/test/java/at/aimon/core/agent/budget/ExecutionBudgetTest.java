package at.aimon.core.agent.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.cost.Money;

@DisplayName("ExecutionBudget Tests")
class ExecutionBudgetTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Empty builder should produce unlimited budget")
        void emptyBuilderUnlimited() {
            ExecutionBudget budget = ExecutionBudget.builder().build();

            assertThat(budget.isUnlimited()).isTrue();
            assertThat(budget.getMaxIterations()).isEmpty();
            assertThat(budget.getMaxTokens()).isEmpty();
            assertThat(budget.getMaxWallClockDuration()).isEmpty();
        }

        @Test
        @DisplayName("Builder should set all dimensions")
        void buildsAllDimensions() {
            ExecutionBudget budget = ExecutionBudget.builder().maxIterations(10).maxTokens(1000)
                    .maxWallClockDuration(Duration.ofSeconds(30)).build();

            assertThat(budget.isUnlimited()).isFalse();
            assertThat(budget.getMaxIterations()).contains(10);
            assertThat(budget.getMaxTokens()).contains(1000);
            assertThat(budget.getMaxWallClockDuration()).contains(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("maxIterations < 1 rejected")
        void rejectsZeroIterations() {
            assertThatThrownBy(() -> ExecutionBudget.builder().maxIterations(0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxIterations");
        }

        @Test
        @DisplayName("maxTokens < 1 rejected")
        void rejectsZeroTokens() {
            assertThatThrownBy(() -> ExecutionBudget.builder().maxTokens(-1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxTokens");
        }

        @Test
        @DisplayName("null duration rejected")
        void rejectsNullDuration() {
            assertThatThrownBy(() -> ExecutionBudget.builder().maxWallClockDuration(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("zero duration rejected")
        void rejectsZeroDuration() {
            assertThatThrownBy(() -> ExecutionBudget.builder().maxWallClockDuration(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        }

        @Test
        @DisplayName("negative duration rejected")
        void rejectsNegativeDuration() {
            assertThatThrownBy(() -> ExecutionBudget.builder().maxWallClockDuration(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        }

        @Test
        @DisplayName("compactionTokenThreshold should be settable and retrievable")
        void setsCompactionTokenThreshold() {
            ExecutionBudget budget = ExecutionBudget.builder().compactionTokenThreshold(1000).build();

            assertThat(budget.getCompactionTokenThreshold()).contains(1000);
        }

        @Test
        @DisplayName("compactionTokenThreshold unset defaults to empty")
        void compactionTokenThresholdUnsetByDefault() {
            ExecutionBudget budget = ExecutionBudget.builder().build();

            assertThat(budget.getCompactionTokenThreshold()).isEmpty();
        }

        @Test
        @DisplayName("compactionTokenThreshold(0) rejected")
        void rejectsZeroCompactionTokenThreshold() {
            assertThatThrownBy(() -> ExecutionBudget.builder().compactionTokenThreshold(0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compactionTokenThreshold");
        }

        @Test
        @DisplayName("negative compactionTokenThreshold rejected")
        void rejectsNegativeCompactionTokenThreshold() {
            assertThatThrownBy(() -> ExecutionBudget.builder().compactionTokenThreshold(-1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compactionTokenThreshold");
        }
    }

    @Nested
    @DisplayName("maxCostUsd dimension")
    class CostAxis {

        @Test
        @DisplayName("maxCostUsd is settable and retrievable")
        void setsAndRetrievesMaxCost() {
            ExecutionBudget budget = ExecutionBudget.builder().maxCostUsd(Money.usd(1.50)).build();

            assertThat(budget.getMaxCostUsd()).contains(Money.usd(1.50));
        }

        @Test
        @DisplayName("maxCostUsd unset defaults to empty")
        void maxCostUnsetByDefault() {
            assertThat(ExecutionBudget.builder().build().getMaxCostUsd()).isEmpty();
        }

        @Test
        @DisplayName("a budget with only maxCostUsd set is a hard budget (not unlimited)")
        void maxCostMakesBudgetNotUnlimited() {
            assertThat(ExecutionBudget.builder().maxCostUsd(Money.usd(1.00)).build().isUnlimited()).isFalse();
        }

        @Test
        @DisplayName("maxCostUsd rejects null")
        void rejectsNull() {
            assertThatThrownBy(() -> ExecutionBudget.builder().maxCostUsd(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("maxCostUsd rejects a non-USD currency")
        void rejectsNonUsd() {
            assertThatThrownBy(() -> ExecutionBudget.builder().maxCostUsd(Money.of(BigDecimal.ONE, "EUR")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("USD");
        }

        @Test
        @DisplayName("maxCostUsd rejects a zero amount")
        void rejectsZero() {
            assertThatThrownBy(() -> ExecutionBudget.builder().maxCostUsd(Money.zeroUsd()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        }

        @Test
        @DisplayName("equal maxCostUsd values produce equal budgets")
        void equalCostBudgetsEqual() {
            ExecutionBudget a = ExecutionBudget.builder().maxCostUsd(Money.usd(2.00)).build();
            ExecutionBudget b = ExecutionBudget.builder().maxCostUsd(Money.usd(2.00)).build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("differing maxCostUsd produces non-equal budgets")
        void differingCostBudgetsNotEqual() {
            assertThat(ExecutionBudget.builder().maxCostUsd(Money.usd(2.00)).build())
                    .isNotEqualTo(ExecutionBudget.builder().maxCostUsd(Money.usd(3.00)).build());
        }

        @Test
        @DisplayName("toString includes maxCostUsd")
        void toStringIncludesMaxCost() {
            assertThat(ExecutionBudget.builder().maxCostUsd(Money.usd(1.25)).build().toString())
                    .contains("maxCostUsd=");
        }
    }

    @Nested
    @DisplayName("Factories")
    class FactoryTests {

        @Test
        @DisplayName("unlimited() should produce the same semantic as empty builder")
        void unlimitedFactory() {
            ExecutionBudget budget = ExecutionBudget.unlimited();

            assertThat(budget.isUnlimited()).isTrue();
            assertThat(budget).isEqualTo(ExecutionBudget.builder().build());
        }

        @Test
        @DisplayName("budget with only compactionTokenThreshold set is still unlimited (soft dimension)")
        void compactionTokenThresholdOnlyIsUnlimited() {
            ExecutionBudget budget = ExecutionBudget.builder().compactionTokenThreshold(1000).build();

            assertThat(budget.isUnlimited()).isTrue();
        }

        @Test
        @DisplayName("budget with a hard dimension set is not unlimited even with compactionTokenThreshold")
        void hardDimensionWithCompactionThresholdIsNotUnlimited() {
            ExecutionBudget budget = ExecutionBudget.builder().maxTokens(500).compactionTokenThreshold(100).build();

            assertThat(budget.isUnlimited()).isFalse();
        }
    }

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("equal for same values")
        void equalForSameValues() {
            ExecutionBudget a = ExecutionBudget.builder().maxIterations(10).maxTokens(1000).build();
            ExecutionBudget b = ExecutionBudget.builder().maxIterations(10).maxTokens(1000).build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("not equal for different iteration limit")
        void notEqualForDifferentValues() {
            ExecutionBudget a = ExecutionBudget.builder().maxIterations(10).build();
            ExecutionBudget b = ExecutionBudget.builder().maxIterations(11).build();

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("equal for same compactionTokenThreshold values")
        void equalForSameCompactionTokenThreshold() {
            ExecutionBudget a = ExecutionBudget.builder().maxTokens(1000).compactionTokenThreshold(500).build();
            ExecutionBudget b = ExecutionBudget.builder().maxTokens(1000).compactionTokenThreshold(500).build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("not equal when differing only in compactionTokenThreshold")
        void notEqualForDifferentCompactionTokenThreshold() {
            ExecutionBudget a = ExecutionBudget.builder().compactionTokenThreshold(500).build();
            ExecutionBudget b = ExecutionBudget.builder().compactionTokenThreshold(600).build();

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("toString should include all dimensions")
        void toStringIncludesAll() {
            ExecutionBudget budget = ExecutionBudget.builder().maxIterations(5).maxTokens(999)
                    .maxWallClockDuration(Duration.ofSeconds(7)).build();

            assertThat(budget.toString()).contains("maxIterations=5").contains("maxTokens=999")
                    .contains("maxWallClockDuration=");
        }

        @Test
        @DisplayName("toString should include compactionTokenThreshold")
        void toStringIncludesCompactionTokenThreshold() {
            ExecutionBudget budget = ExecutionBudget.builder().compactionTokenThreshold(250).build();

            assertThat(budget.toString()).contains("compactionTokenThreshold").contains("compactionTokenThreshold=250");
        }
    }
}
