package at.aimon.core.memory.dreamer;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;

/**
 * Default {@link DreamerEngine} implementation: enumerates every subject in the
 * workspace via {@link ObservationStore#findSubjects} and runs the injected
 * {@link ConsolidationStrategy} once per subject (plan → apply).
 *
 * <p>
 * When a {@link RepresentationStore} is wired, the engine also refreshes each
 * subject's cross-session <em>global</em> {@link Representation} from its current
 * observations after consolidation — this is the producer for the
 * {@code findLatestGlobal} path that {@code RepresentationMemoryContextProvider}
 * and {@code MemoryRecallTool} (GLOBAL mode) read. The summary is built
 * deterministically from the highest-confidence observations so the background
 * job adds no extra LLM cost.
 *
 * <p>
 * The engine swallows exceptions per subject and continues, so one peer with a
 * misbehaving LLM or transient store error cannot stall an entire cycle. The
 * design ({@code §6.3}) calls this out explicitly: dreamer cycles are
 * eventually-consistent and best-effort.
 *
 * <p>
 * Stateless (depends only on its injected dependencies) so the same instance
 * can be reused across cycles and across workspaces.
 */
public final class DefaultDreamerEngine implements DreamerEngine {

    /** Hard upper bound on subjects per cycle to keep one tenant from monopolizing the worker. */
    public static final int DEFAULT_MAX_SUBJECTS_PER_CYCLE = 1024;

    /** Observations pulled per subject when refreshing the global representation. */
    public static final int DEFAULT_GLOBAL_REPRESENTATION_LIMIT = 32;

    /** Audit-retention window for soft-deleted observations (design doc §5.2); purged at the start of each cycle. */
    private static final Duration AUDIT_RETENTION = Duration.ofDays(30);

    /** Highest-confidence observations folded into the deterministic global summary line. */
    private static final int SUMMARY_OBSERVATION_LIMIT = 5;

    private static final int CHARS_PER_TOKEN = 4;

    private static final Logger log = LoggerFactory.getLogger(DefaultDreamerEngine.class);

    private final ObservationStore observationStore;
    private final ConsolidationStrategy strategy;
    private final int maxSubjectsPerCycle;
    private final RepresentationStore representationStore;
    private final int globalRepresentationLimit;

    public DefaultDreamerEngine(ObservationStore observationStore, ConsolidationStrategy strategy) {
        this(observationStore, strategy, DEFAULT_MAX_SUBJECTS_PER_CYCLE);
    }

    public DefaultDreamerEngine(ObservationStore observationStore, ConsolidationStrategy strategy,
            int maxSubjectsPerCycle) {
        this(observationStore, strategy, maxSubjectsPerCycle, null, DEFAULT_GLOBAL_REPRESENTATION_LIMIT);
    }

    /**
     * Creates an engine that, in addition to consolidating, refreshes each subject's global
     * {@link Representation} from its current observations.
     *
     * @param representationStore
     *            store for the refreshed global representation; {@code null} disables that step
     * @param globalRepresentationLimit
     *            observations pulled per subject for the snapshot (must be {@code >= 1})
     */
    public DefaultDreamerEngine(ObservationStore observationStore, ConsolidationStrategy strategy,
            int maxSubjectsPerCycle, RepresentationStore representationStore, int globalRepresentationLimit) {
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore cannot be null");
        this.strategy = Objects.requireNonNull(strategy, "strategy cannot be null");
        if (maxSubjectsPerCycle < 1) {
            throw new IllegalArgumentException("maxSubjectsPerCycle must be >= 1, got " + maxSubjectsPerCycle);
        }
        if (globalRepresentationLimit < 1) {
            throw new IllegalArgumentException(
                    "globalRepresentationLimit must be >= 1, got " + globalRepresentationLimit);
        }
        this.maxSubjectsPerCycle = maxSubjectsPerCycle;
        this.representationStore = representationStore;
        this.globalRepresentationLimit = globalRepresentationLimit;
    }

    @Override
    public DreamerCycleSummary consolidate(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");

        final long startNanos = System.nanoTime();

        // Enforce the audit-retention window: permanently remove observations soft-deleted longer ago
        // than AUDIT_RETENTION (design §5.2). The dreamer is the natural home for this maintenance pass.
        try {
            int purged = observationStore.purgeSoftDeletedBefore(workspace, Instant.now().minus(AUDIT_RETENTION));
            if (purged > 0) {
                log.info("dreamer purged {} soft-deleted observations past retention: workspace={}", purged,
                        workspace.getId());
            }
        } catch (RuntimeException e) {
            log.warn("dreamer retention purge failed: workspace={} error={}", workspace.getId(), e.getMessage());
        }

        List<PeerView> subjects = observationStore.findSubjects(workspace, maxSubjectsPerCycle);
        log.info("dreamer cycle starting: workspace={}, subjects={}", workspace.getId(), subjects.size());

        int subjectsWithPlan = 0;
        int clustersConsolidated = 0;
        int observationsMerged = 0;
        int clusterFailures = 0;
        int errors = 0;

        for (PeerView subject : subjects) {
            try {
                ConsolidationPlan plan = strategy.plan(workspace, subject);
                if (plan.isEmpty()) {
                    log.debug("dreamer plan empty: workspace={}, subject={}", workspace.getId(), subject.key());
                } else {
                    subjectsWithPlan++;
                    clustersConsolidated += plan.getClusters().size();
                    log.debug("dreamer applying plan: workspace={}, subject={}, clusters={}, plannedMerges={}",
                            workspace.getId(), subject.key(), plan.getClusters().size(), plan.totalMerges());
                    // Count what was ACTUALLY applied, not what was planned — apply() swallows per-cluster
                    // failures so the plan total would over-report merges and hide failures.
                    ConsolidationResult result = strategy.apply(plan);
                    observationsMerged += result.getObservationsRemoved();
                    clusterFailures += result.getFailures();
                }
            } catch (RuntimeException e) {
                errors++;
                log.warn("dreamer subject failed: workspace={} subject={} error={}", workspace.getId(), subject.key(),
                        e.getMessage(), e);
            }
            // Refresh the subject's cross-session global representation from its post-consolidation state.
            // Isolated from the consolidation try so a snapshot failure neither masks nor is masked by it.
            if (representationStore != null) {
                try {
                    refreshGlobalRepresentation(subject);
                } catch (RuntimeException e) {
                    errors++;
                    log.warn("dreamer global representation failed: workspace={} subject={} error={}",
                            workspace.getId(), subject.key(), e.getMessage());
                }
            }
        }

        final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        DreamerCycleSummary summary = DreamerCycleSummary.builder().workspace(workspace).subjectsWalked(subjects.size())
                .subjectsWithPlan(subjectsWithPlan).clustersConsolidated(clustersConsolidated)
                .observationsMerged(observationsMerged).clusterFailures(clusterFailures).errors(errors)
                .elapsedMillis(elapsedMillis).build();
        log.info("dreamer cycle complete: {}", summary);
        return summary;
    }

    /**
     * Rebuilds {@code subject}'s global (cross-session) representation from its current observations and
     * saves it, so {@code findLatestGlobal} returns a fresh snapshot. The summary is derived
     * deterministically from the highest-confidence observations — no LLM call — to keep the background
     * job cheap. No-op when the subject has no observations.
     */
    private void refreshGlobalRepresentation(PeerView subject) {
        List<Observation> observations = observationStore.findBySubject(subject, globalRepresentationLimit);
        if (observations.isEmpty()) {
            return;
        }
        String summary = buildSummary(subject, observations);
        Representation representation = Representation.builder().subject(subject).observer(null).sessionId(null)
                .observations(observations).summary(summary).generatedAt(Instant.now())
                .tokenCount(estimateTokens(summary, observations)).build();
        representationStore.save(representation);
        log.debug("dreamer refreshed global representation: subject={}, observations={}", subject.key(),
                observations.size());
    }

    private static String buildSummary(PeerView subject, List<Observation> observations) {
        String name = subject.getPrincipal().getDisplayName() != null
                ? subject.getPrincipal().getDisplayName()
                : subject.getPrincipal().getId();
        String facts = observations.stream().sorted(Comparator.comparingDouble(Observation::getConfidence).reversed())
                .limit(SUMMARY_OBSERVATION_LIMIT).map(Observation::getContent).collect(Collectors.joining("; "));
        return "Consolidated insights about " + name + ": " + facts;
    }

    private static int estimateTokens(String summary, List<Observation> observations) {
        int chars = summary.length();
        for (Observation o : observations) {
            chars += o.getContent().length();
        }
        return Math.max(1, chars / CHARS_PER_TOKEN);
    }
}
