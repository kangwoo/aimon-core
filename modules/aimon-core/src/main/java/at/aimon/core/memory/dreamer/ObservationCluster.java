package at.aimon.core.memory.dreamer;

import java.util.List;
import java.util.Objects;

import at.aimon.core.memory.Observation;

/**
 * One consolidation unit: a {@code winner} observation that survives, the
 * {@code losers} that will be soft-deleted into it, and the
 * {@code merged} payload (LLM-generated) that replaces the winner's content.
 *
 * <p>
 * Per design doc §6.3.3 the dreamer groups low-surprisal observations into
 * clusters; each cluster becomes a sequence of
 * {@link at.aimon.core.memory.ObservationStore#merge merge} calls when applied.
 *
 * <p>
 * Immutable. Use {@link #builder()}.
 */
public final class ObservationCluster {

    private final Observation winner;
    private final List<Observation> losers;
    private final Observation merged;

    private ObservationCluster(Builder builder) {
        this.winner = Objects.requireNonNull(builder.winner, "winner cannot be null");
        Objects.requireNonNull(builder.losers, "losers cannot be null");
        if (builder.losers.isEmpty()) {
            throw new IllegalArgumentException("a cluster must have at least one loser");
        }
        this.losers = List.copyOf(builder.losers);
        this.merged = Objects.requireNonNull(builder.merged, "merged cannot be null");

        if (!merged.getId().equals(winner.getId())) {
            throw new IllegalArgumentException(
                    "merged.id (" + merged.getId() + ") must equal winner.id (" + winner.getId() + ")");
        }
        for (Observation loser : losers) {
            if (loser.getId().equals(winner.getId())) {
                throw new IllegalArgumentException("loser cannot equal winner: " + winner.getId());
            }
            if (!loser.getSubject().getWorkspace().equals(winner.getSubject().getWorkspace())) {
                throw new IllegalArgumentException(
                        "loser " + loser.getId() + " must belong to the same workspace as winner " + winner.getId());
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Observation getWinner() {
        return winner;
    }

    public List<Observation> getLosers() {
        return losers;
    }

    public Observation getMerged() {
        return merged;
    }

    /** Total observation count (winner + losers). Convenience for metrics. */
    public int size() {
        return 1 + losers.size();
    }

    public static final class Builder {
        private Observation winner;
        private List<Observation> losers;
        private Observation merged;

        public Builder winner(Observation winner) {
            this.winner = winner;
            return this;
        }

        public Builder losers(List<Observation> losers) {
            this.losers = losers;
            return this;
        }

        public Builder merged(Observation merged) {
            this.merged = merged;
            return this;
        }

        public ObservationCluster build() {
            return new ObservationCluster(this);
        }
    }
}
