package at.aimon.core.memory;

import java.util.Map;
import java.util.Objects;

/**
 * One result of a {@link MemorySearcher} search.
 *
 * <p>
 * <b>Rank is the list position, not {@link #getScore() score}.</b> The default backend has no scores to give — the
 * index behind it promises ordering and nothing else — so a score would have to be invented from the rank to fill the
 * field, and an invented number is indistinguishable from a measured one once the model reads it. A backend that
 * cannot score leaves this at {@code 0} and says so through {@link MemorySearcher#ranksByScore()}.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class MemoryHit {

    private final Observation observation;
    private final double score;
    private final boolean confidenceAvailable;
    private final Map<String, Double> signals;

    private MemoryHit(Builder builder) {
        this.observation = Objects.requireNonNull(builder.observation, "observation cannot be null");
        if (Double.isNaN(builder.score) || builder.score < 0.0d || builder.score > 1.0d) {
            throw new IllegalArgumentException("score must be within [0, 1], got " + builder.score);
        }
        this.score = builder.score;
        this.confidenceAvailable = builder.confidenceAvailable;
        this.signals = Map.copyOf(Objects.requireNonNull(builder.signals, "signals cannot be null"));
    }

    /**
     * Starts a hit.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the observation that matched.
     *
     * @return the observation, never null
     */
    public Observation getObservation() {
        return observation;
    }

    /**
     * Returns the relevance score, when the backend has one.
     *
     * @return the score in {@code [0, 1]}, or {@code 0} when {@link MemorySearcher#ranksByScore()} is {@code false}
     */
    public double getScore() {
        return score;
    }

    /**
     * Returns whether {@code getObservation().getConfidence()} is a stored value rather than a placeholder.
     *
     * @return {@code true} when the number means something
     */
    public boolean isConfidenceAvailable() {
        return confidenceAvailable;
    }

    /**
     * Returns the per-signal breakdown behind {@link #getScore()}, for backends that fuse several signals.
     *
     * @return the breakdown, never null; empty for backends that do not explain their ranking
     */
    public Map<String, Double> getSignals() {
        return signals;
    }

    @Override
    public String toString() {
        return "MemoryHit{observation=" + observation.getId() + ", score=" + score + "}";
    }

    /** Builder for {@link MemoryHit}. */
    public static final class Builder {

        private Observation observation;
        private double score;
        private boolean confidenceAvailable;
        private Map<String, Double> signals = Map.of();

        private Builder() {
        }

        /**
         * Sets the matched observation. Required.
         *
         * @param observation
         *            the observation (must not be null)
         * @return this builder
         */
        public Builder observation(Observation observation) {
            this.observation = Objects.requireNonNull(observation, "observation cannot be null");
            return this;
        }

        /**
         * Sets the relevance score. Leave at {@code 0} when the backend does not score.
         *
         * @param score
         *            the score in {@code [0, 1]}
         * @return this builder
         */
        public Builder score(double score) {
            this.score = score;
            return this;
        }

        /**
         * Declares whether the observation's confidence is a stored value.
         *
         * @param confidenceAvailable
         *            {@code true} when it is
         * @return this builder
         */
        public Builder confidenceAvailable(boolean confidenceAvailable) {
            this.confidenceAvailable = confidenceAvailable;
            return this;
        }

        /**
         * Sets the per-signal breakdown behind the score.
         *
         * @param signals
         *            the breakdown (must not be null)
         * @return this builder
         */
        public Builder signals(Map<String, Double> signals) {
            this.signals = Objects.requireNonNull(signals, "signals cannot be null");
            return this;
        }

        /**
         * Validates and builds the hit.
         *
         * @return the immutable hit
         * @throws IllegalArgumentException
         *             if {@code score} is outside {@code [0, 1]}
         */
        public MemoryHit build() {
            return new MemoryHit(this);
        }
    }
}
