package at.aimon.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.MemoryDreamerConfig.EmbeddingScorerConfig;
import at.aimon.cli.config.MemoryDreamerConfig.LlmScorerConfig;
import at.aimon.cli.config.MemoryDreamerConfig.ScorerConfig;
import at.aimon.cli.config.MemoryDreamerConfig.ScorerType;

@DisplayName("MemoryDreamerConfig")
class MemoryDreamerConfigTest {

    @Nested
    @DisplayName("Defaults")
    class Defaults {
        @Test
        @DisplayName("resolvedCron falls back to DEFAULT_CRON when blank or null")
        void cronFallback() {
            final MemoryDreamerConfig empty = new MemoryDreamerConfig();
            empty.setCron(null);
            assertThat(empty.resolvedCron()).isEqualTo(MemoryDreamerConfig.DEFAULT_CRON);

            empty.setCron("   ");
            assertThat(empty.resolvedCron()).isEqualTo(MemoryDreamerConfig.DEFAULT_CRON);

            empty.setCron("0 0 * * *");
            assertThat(empty.resolvedCron()).isEqualTo("0 0 * * *");
        }

        @Test
        @DisplayName("tuning getters fall back to documented defaults when null")
        void tuningFallbacks() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            assertThat(config.resolvedSurprisalThreshold()).isEqualTo(MemoryDreamerConfig.DEFAULT_SURPRISAL_THRESHOLD);
            assertThat(config.resolvedWalkSeedCount()).isEqualTo(MemoryDreamerConfig.DEFAULT_WALK_SEED_COUNT);
            assertThat(config.resolvedNeighborTopK()).isEqualTo(MemoryDreamerConfig.DEFAULT_NEIGHBOR_TOP_K);
        }

        @Test
        @DisplayName("explicit tuning values override defaults")
        void tuningOverrides() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            config.setSurprisalThreshold(0.42d);
            config.setWalkSeedCount(3);
            config.setNeighborTopK(11);

            assertThat(config.resolvedSurprisalThreshold()).isEqualTo(0.42d);
            assertThat(config.resolvedWalkSeedCount()).isEqualTo(3);
            assertThat(config.resolvedNeighborTopK()).isEqualTo(11);
        }
    }

    @Nested
    @DisplayName("resolvedScorerType")
    class ScorerTypeResolution {
        @Test
        @DisplayName("missing scorer block falls back to DEFAULT_SCORER_TYPE (LLM)")
        void missingFallsBackToLlm() {
            assertThat(new MemoryDreamerConfig().resolvedScorerType()).isEqualTo(ScorerType.LLM);
        }

        @Test
        @DisplayName("blank type falls back to LLM")
        void blankTypeFallsBack() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            final ScorerConfig scorer = new ScorerConfig();
            scorer.setType("   ");
            config.setScorer(scorer);
            assertThat(config.resolvedScorerType()).isEqualTo(ScorerType.LLM);
        }

        @Test
        @DisplayName("explicit 'embedding' (case-insensitive)")
        void embeddingType() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            final ScorerConfig scorer = new ScorerConfig();
            scorer.setType("Embedding");
            config.setScorer(scorer);
            assertThat(config.resolvedScorerType()).isEqualTo(ScorerType.EMBEDDING);
        }

        @Test
        @DisplayName("unknown type rejected")
        void unknownTypeRejected() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            final ScorerConfig scorer = new ScorerConfig();
            scorer.setType("bm25");
            config.setScorer(scorer);
            assertThatThrownBy(config::resolvedScorerType).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bm25");
        }
    }

    @Nested
    @DisplayName("isReady")
    class IsReady {
        @Test
        @DisplayName("disabled by default")
        void disabledByDefault() {
            assertThat(new MemoryDreamerConfig().isReady()).isFalse();
        }

        @Test
        @DisplayName("LLM scorer (default) is ready as soon as enabled")
        void llmReadyOnEnable() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            config.setEnabled(true);
            assertThat(config.isReady()).isTrue();
        }

        @Test
        @DisplayName("explicit LLM scorer with empty model is still ready (model falls back to global)")
        void explicitLlmReady() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            config.setEnabled(true);
            final ScorerConfig scorer = new ScorerConfig();
            scorer.setType("llm");
            scorer.setLlm(new LlmScorerConfig());
            config.setScorer(scorer);
            assertThat(config.isReady()).isTrue();
        }

        @Test
        @DisplayName("embedding scorer without API key is not ready")
        void embeddingWithoutKey() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            config.setEnabled(true);
            final ScorerConfig scorer = new ScorerConfig();
            scorer.setType("embedding");
            config.setScorer(scorer);
            assertThat(config.isReady()).isFalse();
        }

        @Test
        @DisplayName("embedding scorer with blank API key is not ready")
        void embeddingWithBlankKey() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            config.setEnabled(true);
            final ScorerConfig scorer = new ScorerConfig();
            scorer.setType("embedding");
            final EmbeddingScorerConfig emb = new EmbeddingScorerConfig();
            emb.setApiKey("   ");
            scorer.setEmbedding(emb);
            config.setScorer(scorer);
            assertThat(config.isReady()).isFalse();
        }

        @Test
        @DisplayName("embedding scorer with API key is ready")
        void embeddingWithKey() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            config.setEnabled(true);
            config.setScorer(embeddingScorer("sk-test"));
            assertThat(config.isReady()).isTrue();
        }

        @Test
        @DisplayName("not enabled → not ready regardless of scorer settings")
        void notEnabled() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            config.setScorer(embeddingScorer("sk-test"));
            assertThat(config.isReady()).isFalse();
        }
    }

    @Nested
    @DisplayName("equals/hashCode")
    class EqualsHashCode {
        @Test
        @DisplayName("two configs with identical fields are equal")
        void identical() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
        }

        @Test
        @DisplayName("differs when surprisalThreshold changes")
        void notEqualOnDifferentThreshold() {
            final MemoryDreamerConfig a = populated();
            final MemoryDreamerConfig b = populated();
            b.setSurprisalThreshold(0.99d);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("differs when scorer type changes")
        void notEqualOnDifferentScorerType() {
            final MemoryDreamerConfig a = populated();
            final MemoryDreamerConfig b = populated();
            final ScorerConfig swapped = new ScorerConfig();
            swapped.setType("llm");
            b.setScorer(swapped);
            assertThat(a).isNotEqualTo(b);
        }

        private MemoryDreamerConfig populated() {
            final MemoryDreamerConfig config = new MemoryDreamerConfig();
            config.setEnabled(true);
            config.setCron("0 * * * *");
            config.setScorer(embeddingScorer("sk-test"));
            config.setSurprisalThreshold(0.3d);
            config.setWalkSeedCount(5);
            config.setNeighborTopK(7);
            return config;
        }
    }

    private static ScorerConfig embeddingScorer(String apiKey) {
        final ScorerConfig scorer = new ScorerConfig();
        scorer.setType("embedding");
        final EmbeddingScorerConfig emb = new EmbeddingScorerConfig();
        emb.setApiKey(apiKey);
        emb.setBaseUrl("https://api.openai.com/v1");
        emb.setModel("text-embedding-3-small");
        emb.setDimensions(1536);
        scorer.setEmbedding(emb);
        return scorer;
    }
}
