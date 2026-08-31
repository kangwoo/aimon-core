package at.aimon.core.memory.dreamer;

import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Two-phase consolidation pipeline for one {@link PeerView subject}.
 *
 * <p>
 * Per design doc §6.3.3, the dreamer separates plan computation from
 * application: {@link #plan(Workspace, PeerView)} reads the
 * {@code ObservationStore} and returns a list of {@link ObservationCluster
 * clusters}, while {@link #apply(ConsolidationPlan)} performs the
 * {@code ObservationStore.merge} writes. Splitting them keeps the LLM /
 * embedding cost path side-effect-free and lets operators dry-run
 * consolidation against production data without mutating it.
 *
 * <p>
 * Implementations must be thread-safe; the dreamer engine may schedule
 * multiple subjects concurrently.
 */
public interface ConsolidationStrategy {

    /**
     * Builds — but does not apply — a consolidation plan for {@code subject}
     * within {@code workspace}. Returns {@link ConsolidationPlan#empty} if
     * nothing meets the consolidation threshold.
     *
     * @param workspace
     *            the tenant scope (must not be null)
     * @param subject
     *            the peer whose observations are being consolidated (must not
     *            be null; must belong to {@code workspace})
     */
    ConsolidationPlan plan(Workspace workspace, PeerView subject);

    /**
     * Applies a previously-built {@link ConsolidationPlan} by issuing
     * {@code ObservationStore.merge} (and soft-delete) calls for every
     * {@link ObservationCluster cluster}. Empty plans are no-ops.
     *
     * <p>
     * Per-cluster failures are swallowed so one bad cluster cannot stall a
     * subject; the returned {@link ConsolidationResult} reports what was
     * <em>actually</em> applied (and how many clusters failed) so callers can emit
     * accurate telemetry.
     *
     * @return the actual work performed (never null; {@link ConsolidationResult#empty()} for an empty plan)
     */
    ConsolidationResult apply(ConsolidationPlan plan);
}
