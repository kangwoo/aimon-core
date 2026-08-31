package at.aimon.core.memory.dreamer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.knowledge.embedding.EmbeddingClient;
import at.aimon.core.knowledge.embedding.EmbeddingResult;
import at.aimon.core.memory.Observation;

/**
 * {@link SurprisalScorer} that compares observations purely by content
 * embedding cosine similarity — no LLM judgement required.
 *
 * <p>
 * Per design doc §6.3.2:
 *
 * <pre>
 * sim_max  = max(cosine(emb(obs), emb(n))) over n in neighbors
 * sim_mean = mean(cosine(emb(obs), emb(n))) over n in neighbors
 * surprisal = (1.0 - sim_max) * 0.7 + (1.0 - sim_mean) * 0.3
 * </pre>
 *
 * Empty neighbor list yields {@code 1.0}.
 *
 * <p>
 * Embeddings for {@code observation} and every {@code neighbor} are fetched in
 * a single {@link EmbeddingClient#embedBatch(List)} call so the scorer issues
 * exactly one provider round-trip per evaluation.
 */
public final class EmbeddingSurprisalScorer implements SurprisalScorer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingSurprisalScorer.class);

    private static final double SIM_MAX_WEIGHT = 0.7d;
    private static final double SIM_MEAN_WEIGHT = 0.3d;

    private final EmbeddingClient embeddingClient;

    public EmbeddingSurprisalScorer(EmbeddingClient embeddingClient) {
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient must not be null");
    }

    @Override
    public double score(Observation observation, List<Observation> neighbors) {
        Objects.requireNonNull(observation, "observation must not be null");
        Objects.requireNonNull(neighbors, "neighbors must not be null");

        if (neighbors.isEmpty()) {
            return 1.0d;
        }

        final List<String> texts = new ArrayList<>(neighbors.size() + 1);
        texts.add(observation.getContent());
        for (Observation n : neighbors) {
            texts.add(n.getContent());
        }
        final List<EmbeddingResult> results = embeddingClient.embedBatch(texts);
        if (results.size() != texts.size()) {
            throw new IllegalStateException(
                    "EmbeddingClient returned " + results.size() + " vectors for " + texts.size() + " inputs");
        }

        final float[] target = results.get(0).getVector();
        double simMax = Double.NEGATIVE_INFINITY;
        double simSum = 0.0d;
        for (int i = 1; i < results.size(); i++) {
            double sim = cosine(target, results.get(i).getVector());
            simSum += sim;
            if (sim > simMax) {
                simMax = sim;
            }
        }
        final double simMean = simSum / neighbors.size();

        double surprisal = (1.0d - simMax) * SIM_MAX_WEIGHT + (1.0d - simMean) * SIM_MEAN_WEIGHT;
        double clamped = clamp01(surprisal);
        log.debug("Embedding surprisal: observation={}, neighbors={}, simMax={}, simMean={}, surprisal={}",
                observation.getId(), neighbors.size(), simMax, simMean, clamped);
        return clamped;
    }

    /**
     * Returns the cosine similarity of two equal-length vectors. Zero-norm
     * vectors yield {@code 0.0} so identical empty inputs do not mark each
     * other as "fully redundant".
     */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vector dimension mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0.0d;
        double na = 0.0d;
        double nb = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * (double) b[i];
            na += (double) a[i] * (double) a[i];
            nb += (double) b[i] * (double) b[i];
        }
        if (na == 0.0d || nb == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0d;
        }
        if (v < 0.0d) {
            return 0.0d;
        }
        if (v > 1.0d) {
            return 1.0d;
        }
        return v;
    }

    /** Read-only view of the scorer's weights — useful for property-based tests. */
    public static List<Double> weights() {
        return List.of(SIM_MAX_WEIGHT, SIM_MEAN_WEIGHT);
    }
}
