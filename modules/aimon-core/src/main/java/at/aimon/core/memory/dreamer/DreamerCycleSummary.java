package at.aimon.core.memory.dreamer;

import java.util.Objects;

import at.aimon.core.memory.Workspace;

/**
 * Aggregate metrics for one {@link DreamerEngine#consolidate(Workspace)} run.
 *
 * <p>
 * Captures what the engine saw and what it did so operators can tell, from the
 * job log, whether the cycle was a no-op, healthy, or chewed through errors.
 * Counts are non-negative and additive across subjects.
 *
 * <p>
 * Immutable. Use {@link #builder()}.
 */
public final class DreamerCycleSummary {

    private final Workspace workspace;
    private final int subjectsWalked;
    private final int subjectsWithPlan;
    private final int clustersConsolidated;
    private final int observationsMerged;
    private final int clusterFailures;
    private final int errors;
    private final long elapsedMillis;

    private DreamerCycleSummary(Builder builder) {
        this.workspace = Objects.requireNonNull(builder.workspace, "workspace cannot be null");
        this.subjectsWalked = nonNegative(builder.subjectsWalked, "subjectsWalked");
        this.subjectsWithPlan = nonNegative(builder.subjectsWithPlan, "subjectsWithPlan");
        this.clustersConsolidated = nonNegative(builder.clustersConsolidated, "clustersConsolidated");
        this.observationsMerged = nonNegative(builder.observationsMerged, "observationsMerged");
        this.clusterFailures = nonNegative(builder.clusterFailures, "clusterFailures");
        this.errors = nonNegative(builder.errors, "errors");
        this.elapsedMillis = nonNegativeLong(builder.elapsedMillis, "elapsedMillis");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public int getSubjectsWalked() {
        return subjectsWalked;
    }

    public int getSubjectsWithPlan() {
        return subjectsWithPlan;
    }

    public int getClustersConsolidated() {
        return clustersConsolidated;
    }

    public int getObservationsMerged() {
        return observationsMerged;
    }

    /**
     * Cluster-level apply failures (a merge or soft-delete that threw), distinct from subject-level
     * {@link #getErrors()}.
     */
    public int getClusterFailures() {
        return clusterFailures;
    }

    public int getErrors() {
        return errors;
    }

    /** Wall-clock duration of the consolidate cycle, in milliseconds. */
    public long getElapsedMillis() {
        return elapsedMillis;
    }

    @Override
    public String toString() {
        return "DreamerCycleSummary{" + "workspace=" + workspace.getId() + ", subjectsWalked=" + subjectsWalked
                + ", subjectsWithPlan=" + subjectsWithPlan + ", clustersConsolidated=" + clustersConsolidated
                + ", observationsMerged=" + observationsMerged + ", clusterFailures=" + clusterFailures + ", errors="
                + errors + ", elapsedMillis=" + elapsedMillis + '}';
    }

    private static int nonNegative(int v, String name) {
        if (v < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + v);
        }
        return v;
    }

    private static long nonNegativeLong(long v, String name) {
        if (v < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + v);
        }
        return v;
    }

    /** Builder for {@link DreamerCycleSummary}. */
    public static final class Builder {
        private Workspace workspace;
        private int subjectsWalked;
        private int subjectsWithPlan;
        private int clustersConsolidated;
        private int observationsMerged;
        private int clusterFailures;
        private int errors;
        private long elapsedMillis;

        private Builder() {
        }

        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder subjectsWalked(int n) {
            this.subjectsWalked = n;
            return this;
        }

        public Builder subjectsWithPlan(int n) {
            this.subjectsWithPlan = n;
            return this;
        }

        public Builder clustersConsolidated(int n) {
            this.clustersConsolidated = n;
            return this;
        }

        public Builder observationsMerged(int n) {
            this.observationsMerged = n;
            return this;
        }

        /** Sets the cluster-level apply-failure count. */
        public Builder clusterFailures(int n) {
            this.clusterFailures = n;
            return this;
        }

        public Builder errors(int n) {
            this.errors = n;
            return this;
        }

        /** Sets the wall-clock duration of the cycle, in milliseconds. */
        public Builder elapsedMillis(long n) {
            this.elapsedMillis = n;
            return this;
        }

        public DreamerCycleSummary build() {
            return new DreamerCycleSummary(this);
        }
    }
}
