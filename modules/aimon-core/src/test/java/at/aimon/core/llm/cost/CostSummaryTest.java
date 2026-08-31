package at.aimon.core.llm.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.TokenUsage;

@DisplayName("CostSummary Tests")
class CostSummaryTest {

    @Nested
    @DisplayName("Empty")
    class Empty {

        @Test
        @DisplayName("empty() has zero cost, zero tokens and no model entries")
        void emptyState() {
            CostSummary summary = CostSummary.empty();

            assertThat(summary.isEmpty()).isTrue();
            assertThat(summary.getTotalCost().isZero()).isTrue();
            assertThat(summary.getTotalTokenUsage()).isEqualTo(TokenUsage.empty());
            assertThat(summary.getModelUsages()).isEmpty();
        }

        @Test
        @DisplayName("empty() is a shared singleton")
        void emptyIsSingleton() {
            assertThat(CostSummary.empty()).isSameAs(CostSummary.empty());
        }
    }

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("folding a call is copy-on-write — the original stays empty")
        void copyOnWrite() {
            CostSummary original = CostSummary.empty();

            CostSummary recorded = original.record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01));

            assertThat(original.isEmpty()).isTrue();
            assertThat(recorded.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("accumulates totals across calls of one model")
        void accumulatesSingleModel() {
            CostSummary summary = CostSummary.empty()
                    .record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01))
                    .record("gpt-4o", TokenUsage.of(2_000, 1_000, 3_000), Money.usd(0.02));

            assertThat(summary.getTotalCost().getAmount()).isEqualByComparingTo("0.03");
            assertThat(summary.getTotalTokenUsage().getTotalTokens()).isEqualTo(4_500);
            assertThat(summary.getModelUsages()).hasSize(1);

            ModelUsage gpt = summary.getModelUsages().get("gpt-4o");
            assertThat(gpt.getCost().getAmount()).isEqualByComparingTo("0.03");
            assertThat(gpt.getTokenUsage().getTotalTokens()).isEqualTo(4_500);
        }

        @Test
        @DisplayName("keeps a per-model breakdown for mixed-model executions")
        void mixedModelBreakdown() {
            CostSummary summary = CostSummary.empty()
                    .record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01))
                    .record("claude-sonnet-4", TokenUsage.of(2_000, 1_000, 3_000), Money.usd(0.05));

            assertThat(summary.getModelUsages()).containsOnlyKeys("gpt-4o", "claude-sonnet-4");
            assertThat(summary.getTotalCost().getAmount()).isEqualByComparingTo("0.06");
            assertThat(summary.getTotalTokenUsage().getTotalTokens()).isEqualTo(4_500);
        }

        @Test
        @DisplayName("buckets a null model name under 'unknown'")
        void nullModelBucketedUnknown() {
            CostSummary summary = CostSummary.empty().record(null, TokenUsage.of(10, 10, 20), Money.zeroUsd());

            assertThat(summary.getModelUsages()).containsOnlyKeys("unknown");
        }

        @Test
        @DisplayName("buckets an empty model name under 'unknown'")
        void emptyModelBucketedUnknown() {
            CostSummary summary = CostSummary.empty().record("", TokenUsage.of(10, 10, 20), Money.zeroUsd());

            assertThat(summary.getModelUsages()).containsOnlyKeys("unknown");
        }

        @Test
        @DisplayName("preserves insertion order of models")
        void preservesInsertionOrder() {
            CostSummary summary = CostSummary.empty().record("first", TokenUsage.of(1, 1, 2), Money.zeroUsd())
                    .record("second", TokenUsage.of(1, 1, 2), Money.zeroUsd())
                    .record("first", TokenUsage.of(1, 1, 2), Money.zeroUsd());

            assertThat(summary.getModelUsages().keySet()).containsExactly("first", "second");
        }

        @Test
        @DisplayName("rejects null usage")
        void rejectsNullUsage() {
            assertThatThrownBy(() -> CostSummary.empty().record("gpt-4o", null, Money.zeroUsd()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null cost")
        void rejectsNullCost() {
            assertThatThrownBy(() -> CostSummary.empty().record("gpt-4o", TokenUsage.of(1, 1, 2), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("getModelUsages returns an unmodifiable map")
        void modelUsagesUnmodifiable() {
            CostSummary summary = CostSummary.empty().record("gpt-4o", TokenUsage.of(1, 1, 2), Money.zeroUsd());

            assertThatThrownBy(() -> summary.getModelUsages().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("summaries with identical recordings are equal")
        void identicalRecordingsEqual() {
            CostSummary a = CostSummary.empty().record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01));
            CostSummary b = CostSummary.empty().record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01));

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different totals are not equal")
        void differentTotalsNotEqual() {
            CostSummary a = CostSummary.empty().record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01));
            CostSummary b = CostSummary.empty().record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.02));

            assertThat(a).isNotEqualTo(b);
        }
    }
}
