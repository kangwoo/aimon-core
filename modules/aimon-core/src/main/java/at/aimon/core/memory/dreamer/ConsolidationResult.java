package at.aimon.core.memory.dreamer;

/**
 * Outcome of applying a {@link ConsolidationPlan} — the <em>actual</em> work done, as opposed to
 * the planned work. {@link ConsolidationStrategy#apply} swallows per-cluster failures so one bad
 * cluster cannot stall a subject; this object lets the {@link DreamerEngine} report accurate
 * telemetry instead of optimistically counting the plan.
 *
 * <p>
 * Immutable; counts are non-negative and additive across clusters.
 */
public final class ConsolidationResult {

    private static final ConsolidationResult EMPTY = new ConsolidationResult(0, 0, 0);

    private final int observationsRemoved;
    private final int clustersApplied;
    private final int failures;

    /**
     * @param observationsRemoved
     *            observations actually retired (merge loser + soft-deleted extra losers)
     * @param clustersApplied
     *            clusters whose primary merge succeeded
     * @param failures
     *            cluster-level apply failures; all must be {@code >= 0}
     */
    public ConsolidationResult(int observationsRemoved, int clustersApplied, int failures) {
        this.observationsRemoved = nonNegative(observationsRemoved, "observationsRemoved");
        this.clustersApplied = nonNegative(clustersApplied, "clustersApplied");
        this.failures = nonNegative(failures, "failures");
    }

    /** An all-zero result (empty plan, or nothing applied). */
    public static ConsolidationResult empty() {
        return EMPTY;
    }

    /** Observations actually retired (merge loser + soft-deleted extra losers) across all clusters. */
    public int getObservationsRemoved() {
        return observationsRemoved;
    }

    /** Clusters whose primary merge succeeded. */
    public int getClustersApplied() {
        return clustersApplied;
    }

    /** Cluster-level apply failures (primary merge failed, or an extra-loser soft-delete failed). */
    public int getFailures() {
        return failures;
    }

    /** Returns a new result that is the element-wise sum of this and {@code other}. */
    public ConsolidationResult plus(ConsolidationResult other) {
        return new ConsolidationResult(this.observationsRemoved + other.observationsRemoved,
                this.clustersApplied + other.clustersApplied, this.failures + other.failures);
    }

    @Override
    public String toString() {
        return "ConsolidationResult{observationsRemoved=" + observationsRemoved + ", clustersApplied=" + clustersApplied
                + ", failures=" + failures + '}';
    }

    private static int nonNegative(int v, String name) {
        if (v < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + v);
        }
        return v;
    }
}
