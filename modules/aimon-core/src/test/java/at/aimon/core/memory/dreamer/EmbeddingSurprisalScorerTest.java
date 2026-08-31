package at.aimon.core.memory.dreamer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.knowledge.embedding.EmbeddingClient;
import at.aimon.core.knowledge.embedding.EmbeddingResult;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("EmbeddingSurprisalScorer")
class EmbeddingSurprisalScorerTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();

    @Test
    @DisplayName("empty neighbors → surprisal == 1.0 (no batch call)")
    void emptyNeighbors() {
        StubEmbeddingClient embedder = new StubEmbeddingClient();
        EmbeddingSurprisalScorer scorer = new EmbeddingSurprisalScorer(embedder);

        double score = scorer.score(observation("obs-1", "alpha"), List.of());

        assertThat(score).isEqualTo(1.0d);
        assertThat(embedder.batches).isEmpty();
    }

    @Test
    @DisplayName("identical neighbor → surprisal ≈ 0.0")
    void identicalNeighbor() {
        StubEmbeddingClient embedder = new StubEmbeddingClient().stub("alpha", new float[]{1f, 0f, 0f})
                .stub("alpha-twin", new float[]{1f, 0f, 0f});

        EmbeddingSurprisalScorer scorer = new EmbeddingSurprisalScorer(embedder);

        double score = scorer.score(observation("obs-1", "alpha"), List.of(observation("obs-2", "alpha-twin")));

        assertThat(score).isCloseTo(0.0d, within(1e-9d));
    }

    @Test
    @DisplayName("orthogonal neighbor → surprisal == 1.0")
    void orthogonalNeighbor() {
        StubEmbeddingClient embedder = new StubEmbeddingClient().stub("alpha", new float[]{1f, 0f, 0f}).stub("beta",
                new float[]{0f, 1f, 0f});

        EmbeddingSurprisalScorer scorer = new EmbeddingSurprisalScorer(embedder);

        double score = scorer.score(observation("obs-1", "alpha"), List.of(observation("obs-2", "beta")));

        assertThat(score).isCloseTo(1.0d, within(1e-9d));
    }

    @Test
    @DisplayName("multi-neighbor blend uses 0.7*max + 0.3*mean")
    void multiNeighborBlend() {
        StubEmbeddingClient embedder = new StubEmbeddingClient().stub("target", new float[]{1f, 0f, 0f})
                .stub("near", new float[]{1f, 0f, 0f}).stub("far", new float[]{0f, 1f, 0f});

        EmbeddingSurprisalScorer scorer = new EmbeddingSurprisalScorer(embedder);

        double score = scorer.score(observation("obs-target", "target"),
                List.of(observation("obs-near", "near"), observation("obs-far", "far")));

        // sim_max = 1.0 → (1.0-1.0)*0.7 = 0.0
        // sim_mean = (1.0 + 0.0)/2 = 0.5 → (1.0-0.5)*0.3 = 0.15
        // surprisal = 0.0 + 0.15 = 0.15
        assertThat(score).isCloseTo(0.15d, within(1e-9d));
    }

    @Test
    @DisplayName("issues exactly one batch embed call per evaluation")
    void singleBatchCall() {
        StubEmbeddingClient embedder = new StubEmbeddingClient().stub("a", new float[]{1f, 0f})
                .stub("b", new float[]{0f, 1f}).stub("c", new float[]{1f, 1f});

        EmbeddingSurprisalScorer scorer = new EmbeddingSurprisalScorer(embedder);

        scorer.score(observation("obs-target", "a"), List.of(observation("obs-1", "b"), observation("obs-2", "c")));

        assertThat(embedder.batches).hasSize(1);
        assertThat(embedder.batches.get(0)).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("zero-norm vector → cosine == 0 (avoids NaN)")
    void zeroNormVectorSafe() {
        double cosine = EmbeddingSurprisalScorer.cosine(new float[]{0f, 0f}, new float[]{1f, 1f});
        assertThat(cosine).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("dimension mismatch in cosine throws IllegalArgumentException")
    void dimensionMismatchRejected() {
        assertThatThrownBy(() -> EmbeddingSurprisalScorer.cosine(new float[]{1f, 0f}, new float[]{1f, 0f, 0f}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("null observation rejected")
    void nullObservationRejected() {
        EmbeddingSurprisalScorer scorer = new EmbeddingSurprisalScorer(new StubEmbeddingClient());
        assertThatThrownBy(() -> scorer.score(null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null neighbor list rejected")
    void nullNeighborsRejected() {
        EmbeddingSurprisalScorer scorer = new EmbeddingSurprisalScorer(new StubEmbeddingClient());
        assertThatThrownBy(() -> scorer.score(observation("obs-1", "x"), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Observation observation(String localId, String content) {
        return Observation.builder().id(ObservationId.of(WS, localId))
                .subject(PeerView.of(WS, Principal.user("alice", "Alice")))
                .observer(PeerView.of(WS, Principal.user("bob", "Bob"))).content(content).type(ObservationType.EXPLICIT)
                .createdAt(Instant.parse("2024-01-15T10:00:00Z")).confidence(0.8d).build();
    }

    /** Tiny in-memory embedding client that returns pre-stubbed vectors per text. */
    private static final class StubEmbeddingClient implements EmbeddingClient {

        private final java.util.Map<String, float[]> vectors = new java.util.HashMap<>();
        private final List<List<String>> batches = new ArrayList<>();

        StubEmbeddingClient stub(String text, float[] vector) {
            vectors.put(text, vector);
            return this;
        }

        @Override
        public EmbeddingResult embed(String text) {
            return EmbeddingResult.builder().vector(resolve(text)).tokenCount(1).build();
        }

        @Override
        public List<EmbeddingResult> embedBatch(List<String> texts) {
            batches.add(List.copyOf(texts));
            List<EmbeddingResult> out = new ArrayList<>(texts.size());
            for (String t : texts) {
                out.add(EmbeddingResult.builder().vector(resolve(t)).tokenCount(1).build());
            }
            return out;
        }

        @Override
        public int getDimensions() {
            return vectors.values().stream().mapToInt(v -> v.length).findFirst().orElse(0);
        }

        private float[] resolve(String text) {
            float[] v = vectors.get(text);
            if (v == null) {
                throw new IllegalStateException("no stub for: " + text);
            }
            return v;
        }
    }
}
