package at.aimon.core.memory.dreamer;

import java.util.List;
import java.util.Objects;

import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Output of {@link ConsolidationStrategy#plan(Workspace, PeerView)} — a list
 * of {@link ObservationCluster clusters} to merge for one
 * {@link PeerView subject}.
 *
 * <p>
 * The plan is data only. It does not mutate the {@code ObservationStore} until
 * {@link ConsolidationStrategy#apply(ConsolidationPlan)} is invoked. Splitting
 * planning from application makes the dreamer easy to dry-run, audit, and
 * unit-test.
 *
 * <p>
 * An empty {@link #getClusters() clusters} list is the normal result for
 * subjects whose observations are all sufficiently novel — the dreamer should
 * skip {@code apply} in that case.
 */
public final class ConsolidationPlan {

    private final Workspace workspace;
    private final PeerView subject;
    private final List<ObservationCluster> clusters;

    private ConsolidationPlan(Builder builder) {
        this.workspace = Objects.requireNonNull(builder.workspace, "workspace cannot be null");
        this.subject = Objects.requireNonNull(builder.subject, "subject cannot be null");
        this.clusters = List.copyOf(Objects.requireNonNull(builder.clusters, "clusters cannot be null"));
        if (!subject.getWorkspace().equals(workspace)) {
            throw new IllegalArgumentException("subject workspace (" + subject.getWorkspace().getId()
                    + ") must equal plan workspace (" + workspace.getId() + ")");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Empty plan — convenience for "no consolidation needed". */
    public static ConsolidationPlan empty(Workspace workspace, PeerView subject) {
        return builder().workspace(workspace).subject(subject).clusters(List.of()).build();
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public PeerView getSubject() {
        return subject;
    }

    public List<ObservationCluster> getClusters() {
        return clusters;
    }

    public boolean isEmpty() {
        return clusters.isEmpty();
    }

    /** Total number of merge() calls this plan will issue when applied. */
    public int totalMerges() {
        int sum = 0;
        for (ObservationCluster c : clusters) {
            sum += c.getLosers().size();
        }
        return sum;
    }

    public static final class Builder {
        private Workspace workspace;
        private PeerView subject;
        private List<ObservationCluster> clusters = List.of();

        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder subject(PeerView subject) {
            this.subject = subject;
            return this;
        }

        public Builder clusters(List<ObservationCluster> clusters) {
            this.clusters = clusters;
            return this;
        }

        public ConsolidationPlan build() {
            return new ConsolidationPlan(this);
        }
    }
}
