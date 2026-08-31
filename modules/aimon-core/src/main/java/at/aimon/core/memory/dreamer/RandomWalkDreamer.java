package at.aimon.core.memory.dreamer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.base.text.CodeFences;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.tagging.BoundMetadataLlmClient;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Random-walk {@link ConsolidationStrategy} per design doc §6.3.3:
 *
 * <ol>
 * <li>Pick the {@code walkSeedCount} most recent observations of the subject as seeds.</li>
 * <li>For each seed, expand to {@code neighborTopK} neighbors via
 * {@link ObservationStore#semanticSearch}.</li>
 * <li>Cluster every (seed → neighbor) pair whose
 * {@link SurprisalScorer surprisal} is below
 * {@code surprisalThreshold} — these are "redundant enough to merge".</li>
 * <li>For each cluster, ask the {@link LlmClient} to produce a single
 * consolidated statement, package it into an {@link ObservationCluster},
 * and emit a {@link ConsolidationPlan}.</li>
 * </ol>
 *
 * <p>
 * The strategy keeps planning side-effect-free: the LLM and embedding calls
 * happen inside {@link #plan(Workspace, PeerView)}, while
 * {@link #apply(ConsolidationPlan)} performs the {@code ObservationStore.merge}
 * writes only. A failed LLM call drops the cluster from the plan rather than
 * propagating; the dreamer engine logs and continues so one bad subject does
 * not stall the cycle.
 *
 * <p>
 * Thread-safe as long as injected dependencies are.
 */
public final class RandomWalkDreamer implements ConsolidationStrategy {

    private static final Logger log = LoggerFactory.getLogger(RandomWalkDreamer.class);

    private static final String MERGE_SYSTEM_PROMPT = """
            You are consolidating multiple observations about the same subject into a single statement.
            Given N observations that have been judged redundant, output ONE consolidated observation that
            preserves the shared information without repetition.

            Output ONLY a JSON object (no markdown, no commentary) with these fields:
            - "content": one short factual sentence (string, required, non-empty)
            - "confidence": probability the consolidated statement is correct, in [0.0, 1.0] (number, required)

            Example:
            {"content": "Alice prefers tea over coffee", "confidence": 0.95}
            """;

    private static final LlmCallMetadata SELF_METADATA = LlmCallMetadata.builder().component("memory")
            .feature("consolidation").build();

    private final ObservationStore observationStore;
    private final SurprisalScorer surprisalScorer;
    private final LlmClient llmClient;
    private final String llmModelName;
    private final double surprisalThreshold;
    private final int walkSeedCount;
    private final int neighborTopK;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public RandomWalkDreamer(ObservationStore observationStore, SurprisalScorer surprisalScorer, LlmClient llmClient,
            String llmModelName, double surprisalThreshold, int walkSeedCount, int neighborTopK) {
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore must not be null");
        this.surprisalScorer = Objects.requireNonNull(surprisalScorer, "surprisalScorer must not be null");
        this.llmClient = new BoundMetadataLlmClient(Objects.requireNonNull(llmClient, "llmClient must not be null"),
                SELF_METADATA);
        this.llmModelName = Objects.requireNonNull(llmModelName, "llmModelName must not be null");
        if (llmModelName.isBlank()) {
            throw new IllegalArgumentException("llmModelName must not be blank");
        }
        if (Double.isNaN(surprisalThreshold) || surprisalThreshold < 0.0d || surprisalThreshold > 1.0d) {
            throw new IllegalArgumentException("surprisalThreshold must be in [0, 1], got " + surprisalThreshold);
        }
        if (walkSeedCount < 1) {
            throw new IllegalArgumentException("walkSeedCount must be >= 1, got " + walkSeedCount);
        }
        if (neighborTopK < 1) {
            throw new IllegalArgumentException("neighborTopK must be >= 1, got " + neighborTopK);
        }
        this.surprisalThreshold = surprisalThreshold;
        this.walkSeedCount = walkSeedCount;
        this.neighborTopK = neighborTopK;
    }

    @Override
    public ConsolidationPlan plan(Workspace workspace, PeerView subject) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        Objects.requireNonNull(subject, "subject cannot be null");
        if (!subject.getWorkspace().equals(workspace)) {
            throw new IllegalArgumentException("subject workspace (" + subject.getWorkspace().getId()
                    + ") must equal plan workspace (" + workspace.getId() + ")");
        }

        List<Observation> seeds = observationStore.findBySubject(subject, walkSeedCount);
        log.debug("RandomWalk planning: workspace={}, subject={}, seeds={}", workspace.getId(), subject.key(),
                seeds.size());
        if (seeds.size() < 2) {
            return ConsolidationPlan.empty(workspace, subject);
        }

        Set<ObservationId> consumed = new HashSet<>();
        List<ObservationCluster> clusters = new ArrayList<>();

        for (Observation seed : seeds) {
            if (consumed.contains(seed.getId())) {
                continue;
            }
            List<Observation> neighbors = observationStore.semanticSearch(subject, seed.getContent(), neighborTopK)
                    .stream().filter(o -> !o.getId().equals(seed.getId())).filter(o -> !consumed.contains(o.getId()))
                    .toList();
            if (neighbors.isEmpty()) {
                continue;
            }

            List<Observation> redundant = new ArrayList<>();
            for (Observation candidate : neighbors) {
                double surprisal = surprisalScorer.score(candidate, List.of(seed));
                if (surprisal < surprisalThreshold) {
                    redundant.add(candidate);
                }
            }
            if (redundant.isEmpty()) {
                continue;
            }

            Observation winner = pickWinner(seed, redundant);
            List<Observation> losers = new ArrayList<>(redundant.size());
            for (Observation r : redundant) {
                if (!r.getId().equals(winner.getId())) {
                    losers.add(r);
                }
            }
            if (winner != seed) {
                losers.add(seed);
            }
            if (losers.isEmpty()) {
                continue;
            }

            Observation merged = mergeWithLlm(winner, losers);
            if (merged == null) {
                continue;
            }

            clusters.add(ObservationCluster.builder().winner(winner).losers(losers).merged(merged).build());
            consumed.add(winner.getId());
            for (Observation l : losers) {
                consumed.add(l.getId());
            }
            log.debug("RandomWalk cluster planned: subject={}, winner={}, losers={}", subject.key(), winner.getId(),
                    losers.size());
        }

        log.debug("RandomWalk plan finished: workspace={}, subject={}, clusters={}", workspace.getId(), subject.key(),
                clusters.size());
        return ConsolidationPlan.builder().workspace(workspace).subject(subject).clusters(clusters).build();
    }

    @Override
    public ConsolidationResult apply(ConsolidationPlan plan) {
        Objects.requireNonNull(plan, "plan cannot be null");
        if (plan.isEmpty()) {
            return ConsolidationResult.empty();
        }
        ConsolidationResult total = ConsolidationResult.empty();
        for (ObservationCluster cluster : plan.getClusters()) {
            total = total.plus(applyCluster(cluster));
        }
        return total;
    }

    /**
     * Applies a single cluster's consolidation. The winner absorbs the cluster's merged
     * content via a single {@code merge()} call against the first loser (which the merge soft-deletes
     * with an audit entry). Remaining losers are retired via {@code softDelete()} — not the hard
     * {@code delete()} — so every absorbed observation keeps its 30-day audit window (design §5.2);
     * routing them through {@code merge()} again would re-write the winner and create N redundant
     * audit rows pointing at the same winner.
     *
     * @return the actual work this cluster performed (observations removed, applied flag, failures)
     */
    private ConsolidationResult applyCluster(ObservationCluster cluster) {
        List<Observation> losers = cluster.getLosers();
        if (losers.isEmpty()) {
            return ConsolidationResult.empty();
        }
        ObservationId winnerId = cluster.getWinner().getId();
        Observation merged = cluster.getMerged();

        Observation primaryLoser = losers.get(0);
        try {
            observationStore.merge(winnerId, primaryLoser.getId(), merged);
            log.debug("RandomWalk merged cluster: winner={}, primaryLoser={}, additionalLosers={}", winnerId,
                    primaryLoser.getId(), losers.size() - 1);
        } catch (RuntimeException e) {
            log.warn("merge failed for winner={} loser={}: {}", winnerId, primaryLoser.getId(), e.getMessage());
            // If the primary merge failed the winner's content was not updated; abandoning
            // the rest of the cluster avoids retiring losers whose content is now lost.
            return new ConsolidationResult(0, 0, 1);
        }

        int removed = 1; // the primary loser soft-deleted by merge()
        int failures = 0;
        for (int i = 1; i < losers.size(); i++) {
            Observation extra = losers.get(i);
            try {
                observationStore.softDelete(extra.getId());
                removed++;
            } catch (RuntimeException e) {
                log.warn("soft-delete (post-merge) failed for winner={} loser={}: {}", winnerId, extra.getId(),
                        e.getMessage());
                failures++;
            }
        }
        return new ConsolidationResult(removed, 1, failures);
    }

    private static Observation pickWinner(Observation seed, List<Observation> redundant) {
        Observation winner = seed;
        for (Observation candidate : redundant) {
            if (candidate.getConfidence() > winner.getConfidence()) {
                winner = candidate;
            }
        }
        return winner;
    }

    private Observation mergeWithLlm(Observation winner, List<Observation> losers) {
        StringBuilder prompt = new StringBuilder("Observations to consolidate:\n");
        prompt.append("1. ").append(winner.getContent()).append('\n');
        int n = 2;
        for (Observation l : losers) {
            prompt.append(n++).append(". ").append(l.getContent()).append('\n');
        }

        try {
            LlmModel modelConfig = LlmModel.builder().name(llmModelName).build();
            LlmResponse response = llmClient.sendMessage(MERGE_SYSTEM_PROMPT, List.of(Message.user(prompt.toString())),
                    List.of(), modelConfig);
            String text = response.getTextContent();
            if (text == null || text.isBlank()) {
                log.warn("Empty LLM response while merging cluster led by {}", winner.getId());
                return null;
            }

            JsonNode node = OBJECT_MAPPER.readTree(CodeFences.strip(text));
            if (!node.isObject()) {
                log.warn("Merge response was not a JSON object (was {}); dropping", node.getNodeType());
                return null;
            }
            JsonNode contentNode = node.get("content");
            if (contentNode == null || !contentNode.isTextual()) {
                log.warn("Merge response missing textual 'content'; dropping");
                return null;
            }
            String content = contentNode.asText().trim();
            if (content.isEmpty()) {
                log.warn("Merge response had blank 'content'; dropping");
                return null;
            }
            double confidence = parseConfidence(node.get("confidence"), winner.getConfidence());

            return rebuildAsMerged(winner, losers, content, confidence);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse merge response as JSON: {}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("LLM merge failed for cluster led by {}: {}", winner.getId(), e.getMessage());
            return null;
        }
    }

    private static Observation rebuildAsMerged(Observation winner, List<Observation> losers, String content,
            double confidence) {
        Set<String> sourceIds = new java.util.LinkedHashSet<>(winner.getSourceMessageIds());
        for (Observation l : losers) {
            sourceIds.addAll(l.getSourceMessageIds());
        }
        return Observation.builder().id(winner.getId()).subject(winner.getSubject()).observer(winner.getObserver())
                .content(content).type(winner.getType()).sourceMessageIds(List.copyOf(sourceIds))
                .createdAt(Instant.now()).confidence(confidence).metadata(winner.getMetadata()).build();
    }

    private static double parseConfidence(JsonNode node, double fallback) {
        if (node == null || !node.isNumber()) {
            return fallback;
        }
        double raw = node.asDouble();
        if (!Double.isFinite(raw)) {
            return fallback;
        }
        return Math.max(0.0d, Math.min(1.0d, raw));
    }

    /** Read-only snapshot of the strategy's tuning, useful for telemetry. */
    public String describe() {
        return String.format(Locale.ROOT, "RandomWalkDreamer{threshold=%.2f, seeds=%d, neighbors=%d, model=%s}",
                surprisalThreshold, walkSeedCount, neighborTopK, llmModelName);
    }
}
