package at.aimon.core.memory.deriver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

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
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.reconciler.ReconcileDecision;
import at.aimon.core.memory.reconciler.Reconciler;

/**
 * Single-shot {@link Deriver} that asks an {@link LlmClient} to extract
 * observations from the conversation in one round trip and persists them via
 * an {@link ObservationStore}.
 *
 * <p>
 * The deriver does not run the ReAct loop. It builds a system prompt that
 * pins the response format to a JSON array of {@code {content, type}} pairs,
 * sends the conversation, and parses the result. {@code confidence} is computed
 * by the deriver per design doc §4.3 (base score from {@code type} plus
 * reinforcement/contradiction) — never self-reported by the LLM. Anything the
 * LLM returns that is not parseable JSON or not an array is logged and dropped —
 * the deriver returns {@link DerivationResult#empty()} rather than throwing,
 * since the queue manager treats throws as failed tasks.
 *
 * <p>
 * Stage 2 simplification: every observation is recorded with
 * {@code subject == observer}. Multi-peer subject inference (the agent observes
 * the user) is deferred to stage 3 once the dialectic reader is in place.
 */
public final class LlmDeriver implements Deriver {

    private static final Logger log = LoggerFactory.getLogger(LlmDeriver.class);

    private static final String SYSTEM_PROMPT = """
            You analyze a conversation and extract atomic observations about the speakers.
            Output a JSON array of observations. Each observation must have:
            - "content": one short factual sentence (string, required, non-empty)
            - "type": "EXPLICIT" if the speaker stated it directly, "DEDUCTIVE" if inferred (string, required)

            Classify the type only — do NOT score confidence; the system computes that.

            Return ONLY the JSON array (no markdown fences, no commentary).
            Return an empty array [] if there is nothing to observe.

            Example:
            [
              {"content": "Alice prefers tea over coffee", "type": "EXPLICIT"}
            ]
            """;

    private static final String REPRESENTATION_SUMMARY_PROMPT = """
            You are given a list of observations about a peer. Synthesize a short, factual
            summary (3-6 sentences) that captures the most salient and high-confidence
            insights. Focus on stable traits, preferences, recurring concerns, and
            relationships. Avoid speculation beyond what the observations state.

            Return ONLY the summary text (no headings, no bullet lists, no commentary).
            Return an empty response if the observation list is empty.
            """;

    /** Top-K limit when searching the store for potential conflicts before reconciliation. */
    private static final int RECONCILER_CONFLICT_TOP_K = 5;

    // Confidence computation per design doc §4.3. The deriver — NOT the LLM — computes confidence:
    // base_score(type) + reinforcement(corroborations) - contradiction_penalty, clamped to [0,1].
    // Base scores live on ObservationType#baseConfidence().
    private static final double REINFORCEMENT_PER_CORROBORATION = 0.05d;
    private static final double REINFORCEMENT_CAP = 0.2d;
    private static final double CONTRADICTION_PENALTY = 0.3d;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final LlmCallMetadata SELF_METADATA = LlmCallMetadata.builder().component("memory")
            .feature("derivation").build();

    private final LlmClient llmClient;
    private final ObservationStore observationStore;
    private final RepresentationStore representationStore;
    private final Reconciler reconciler;
    private final String llmModelName;

    public LlmDeriver(LlmClient llmClient, ObservationStore observationStore, String llmModelName) {
        this(llmClient, observationStore, llmModelName, null, null);
    }

    /**
     * Creates a deriver that also produces a {@link Representation} snapshot per derivation.
     *
     * <p>
     * When {@code representationStore} is non-null, after observations are persisted the deriver issues a second LLM
     * call that summarizes them and saves the resulting {@link Representation}. Failures of that second call are
     * logged and dropped — the already-persisted observations remain.
     *
     * @param representationStore
     *            optional store for the Representation snapshot; when {@code null} the deriver behaves as the
     *            observation-only original.
     */
    public LlmDeriver(LlmClient llmClient, ObservationStore observationStore, String llmModelName,
            RepresentationStore representationStore) {
        this(llmClient, observationStore, llmModelName, representationStore, null);
    }

    /**
     * Creates a deriver that runs every freshly extracted observation through {@code reconciler} before persisting it.
     *
     * <p>
     * For each candidate, {@link ObservationStore#semanticSearch} provides the conflict set; the reconciler decides
     * whether the candidate is accepted, rejected, replaces a single existing observation, or is merged with one. A
     * {@code null} reconciler preserves the legacy "save every parsed candidate" behavior — this matches the contract
     * of the simpler constructors above.
     *
     * <p>
     * Reconciler exceptions are caught and logged; the candidate is then accepted as a conservative fallback so a
     * transient judge failure does not silently drop derived knowledge.
     */
    public LlmDeriver(LlmClient llmClient, ObservationStore observationStore, String llmModelName,
            RepresentationStore representationStore, Reconciler reconciler) {
        this.llmClient = new BoundMetadataLlmClient(Objects.requireNonNull(llmClient, "llmClient cannot be null"),
                SELF_METADATA);
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore cannot be null");
        this.llmModelName = Objects.requireNonNull(llmModelName, "llmModelName cannot be null");
        if (llmModelName.isBlank()) {
            throw new IllegalArgumentException("llmModelName cannot be blank");
        }
        this.representationStore = representationStore;
        this.reconciler = reconciler;
    }

    @Override
    public DerivationResult derive(DerivationContext ctx) {
        Objects.requireNonNull(ctx, "ctx cannot be null");

        log.info("LlmDeriver starting derivation: observer={}, messages={}, tokenBudget={}", ctx.getObserver().key(),
                ctx.getMessages().size(), ctx.getTokenBudget());
        try {
            LlmModel modelConfig = LlmModel.builder().name(llmModelName).maxTokens(ctx.getTokenBudget()).build();
            LlmResponse response = llmClient.sendMessage(SYSTEM_PROMPT, ctx.getMessages(), List.of(), modelConfig);
            String text = response.getTextContent();
            if (text == null || text.isBlank()) {
                log.info("LlmDeriver got empty response: observer={}", ctx.getObserver().key());
                return DerivationResult.empty();
            }
            log.debug("LlmDeriver received raw response: observer={}, chars={}", ctx.getObserver().key(),
                    text.length());

            List<Observation> created = parseAndPersist(ctx, text);
            int totalTokens = response.getTokenUsage().getTotalTokens();

            if (representationStore != null && !created.isEmpty()) {
                totalTokens += synthesizeAndSaveRepresentation(ctx, created, modelConfig);
            }

            log.info("LlmDeriver finished: observer={}, created={}, tokens={}", ctx.getObserver().key(), created.size(),
                    totalTokens);
            return DerivationResult.of(created, List.of(), totalTokens);
        } catch (RuntimeException e) {
            log.error("LlmDeriver failed for {}: {}", ctx.getObserver().key(), e.getMessage(), e);
            return DerivationResult.empty();
        }
    }

    private int synthesizeAndSaveRepresentation(DerivationContext ctx, List<Observation> observations,
            LlmModel modelConfig) {
        try {
            String prompt = renderObservationsForSummary(observations);
            LlmResponse response = llmClient.sendMessage(REPRESENTATION_SUMMARY_PROMPT, List.of(Message.user(prompt)),
                    List.of(), modelConfig);
            String summary = response.getTextContent();
            if (summary == null) {
                summary = "";
            }
            summary = summary.trim();
            int summaryTokens = response.getTokenUsage().getTotalTokens();

            Representation representation = Representation.builder().subject(ctx.getObserver())
                    .observer(ctx.getObserver()).sessionId(ctx.getSessionId()).observations(observations)
                    .summary(summary).generatedAt(Instant.now())
                    .tokenCount(estimateRepresentationTokenCount(summary, observations)).build();
            representationStore.save(representation);
            log.debug("LlmDeriver saved representation: observer={}, summaryChars={}, tokens={}",
                    ctx.getObserver().key(), summary.length(), summaryTokens);
            return summaryTokens;
        } catch (RuntimeException e) {
            log.warn("LlmDeriver representation synthesis failed for {}: {}", ctx.getObserver().key(), e.getMessage());
            return 0;
        }
    }

    private static String renderObservationsForSummary(List<Observation> observations) {
        StringBuilder sb = new StringBuilder("Observations:\n");
        for (Observation obs : observations) {
            sb.append("- [").append(obs.getType()).append(", confidence=").append(obs.getConfidence()).append("] ")
                    .append(obs.getContent()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Heuristic char-to-token estimate for a {@link Representation}'s renderable payload. Honchos's auto-injected
     * memory part includes both the synthesized summary and the per-observation lines when
     * {@link at.aimon.core.memory.MemoryInjectionMode#FULL} is used, so the budget needs to reflect both — counting
     * the summary alone underestimates and lets large observation sets slip past {@code maxTokens} guards.
     *
     * <p>
     * The per-observation overhead approximates the formatting injected by the renderer (id bracket, confidence
     * suffix); it is conservative on purpose so the budget cuts before a real LLM tokenizer would.
     */
    private static int estimateRepresentationTokenCount(String summary, List<Observation> observations) {
        int totalChars = summary == null ? 0 : summary.length();
        for (Observation obs : observations) {
            totalChars += obs.getContent().length() + OBSERVATION_FORMATTING_OVERHEAD_CHARS;
        }
        if (totalChars == 0) {
            return 0;
        }
        return Math.max(1, totalChars / CHARS_PER_TOKEN);
    }

    private static final int CHARS_PER_TOKEN = 4;
    private static final int OBSERVATION_FORMATTING_OVERHEAD_CHARS = 32;

    private List<Observation> parseAndPersist(DerivationContext ctx, String text) {
        String cleaned = CodeFences.strip(text);
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(cleaned);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM response as JSON: {}", e.getMessage());
            return List.of();
        }
        if (!root.isArray()) {
            log.warn("LLM response was not a JSON array (was {}); dropping", root.getNodeType());
            return List.of();
        }
        List<Observation> result = new ArrayList<>();
        for (JsonNode node : root) {
            Observation obs = buildObservation(ctx, node);
            if (obs != null) {
                Observation persisted = persistWithReconciliation(obs);
                if (persisted != null) {
                    result.add(persisted);
                }
            }
        }
        return List.copyOf(result);
    }

    private Observation persistWithReconciliation(Observation candidate) {
        if (reconciler == null) {
            // No conflict set available — confidence stays at base_score(type) (design §4.3).
            return observationStore.save(candidate);
        }

        List<Observation> conflicts = observationStore.semanticSearch(candidate.getSubject(), candidate.getContent(),
                RECONCILER_CONFLICT_TOP_K);
        ReconcileDecision decision;
        try {
            decision = reconciler.evaluate(candidate, conflicts);
        } catch (RuntimeException e) {
            log.warn("Reconciler.evaluate failed for candidate {}: {} — accepting as fallback", candidate.getId(),
                    e.getMessage());
            return observationStore.save(candidate);
        }
        // §4.3: refine confidence now that we have the corroboration/contradiction signal. Semantically
        // similar priors corroborate (reinforcement); a Replace means the candidate supersedes a
        // conflicting prior, i.e. a contradiction was detected in memory (penalty).
        boolean contradicted = decision instanceof ReconcileDecision.Replace;
        Observation scored = withConfidence(candidate,
                computeConfidence(candidate.getType(), conflicts.size(), contradicted));
        return applyReconcileDecision(scored, decision);
    }

    private Observation applyReconcileDecision(Observation candidate, ReconcileDecision decision) {
        if (decision instanceof ReconcileDecision.Accept) {
            return observationStore.save(candidate);
        }
        if (decision instanceof ReconcileDecision.Reject reject) {
            log.debug("Reconciler rejected candidate {}: {}", candidate.getId(), reject.getReason());
            return null;
        }
        if (decision instanceof ReconcileDecision.Replace replace) {
            Observation saved = observationStore.save(candidate);
            observationStore.delete(replace.getSupersededId());
            log.debug("Reconciler replaced {} with candidate {}", replace.getSupersededId(), candidate.getId());
            return saved;
        }
        if (decision instanceof ReconcileDecision.Merge merge) {
            Observation merged = merge.getMerged();
            // Reconciler may produce a merged observation whose id matches either the candidate (candidate-wins) or
            // the conflicting peer (other-wins). Either case maps to a single store.save(merged); when the candidate
            // wins we additionally drop the existing peer, but when the existing peer wins the save overwrites it
            // and the candidate (never persisted) simply isn't written.
            if (merged.getId().equals(candidate.getId())) {
                Observation saved = observationStore.save(merged);
                observationStore.delete(merge.getOtherId());
                log.debug("Reconciler merged existing {} into candidate {}", merge.getOtherId(), candidate.getId());
                return saved;
            }
            Observation saved = observationStore.save(merged);
            log.debug("Reconciler merged candidate {} into existing {}", candidate.getId(), merged.getId());
            return saved;
        }
        log.warn("Unknown ReconcileDecision type {}; saving candidate as fallback", decision);
        return observationStore.save(candidate);
    }

    private Observation buildObservation(DerivationContext ctx, JsonNode node) {
        if (!node.isObject()) {
            log.debug("Skipping non-object node in LLM response: {}", node.getNodeType());
            return null;
        }
        JsonNode contentNode = node.get("content");
        if (contentNode == null || !contentNode.isTextual()) {
            log.debug("Skipping observation without textual content");
            return null;
        }
        String content = contentNode.asText().trim();
        if (content.isEmpty()) {
            return null;
        }

        ObservationType type = parseType(node.get("type"));
        // §4.3: confidence is computed, not self-reported. Start from base_score(type); the
        // reinforcement/contradiction terms are layered in persistWithReconciliation when a conflict
        // set is available.
        double confidence = type.baseConfidence();

        ObservationId id = ObservationId.of(ctx.getWorkspace(), UUID.randomUUID().toString());
        return Observation.builder().id(id).subject(ctx.getObserver()).observer(ctx.getObserver()).content(content)
                .type(type).confidence(confidence).createdAt(Instant.now()).build();
    }

    /**
     * Computes confidence per design doc §4.3:
     * {@code clamp(0,1, base_score(type) + reinforcement - contradiction)} where reinforcement is
     * {@code +0.05} per corroborating prior observation (capped at {@code +0.2}) and contradiction is
     * {@code -0.3} when a conflicting prior was detected.
     */
    private static double computeConfidence(ObservationType type, int corroborations, boolean contradicted) {
        double reinforcement = Math.min(corroborations * REINFORCEMENT_PER_CORROBORATION, REINFORCEMENT_CAP);
        double contradiction = contradicted ? CONTRADICTION_PENALTY : 0.0d;
        return Math.max(0.0d, Math.min(1.0d, type.baseConfidence() + reinforcement - contradiction));
    }

    private static Observation withConfidence(Observation o, double confidence) {
        return Observation.builder().id(o.getId()).subject(o.getSubject()).observer(o.getObserver())
                .content(o.getContent()).type(o.getType()).sourceMessageIds(o.getSourceMessageIds())
                .createdAt(o.getCreatedAt()).confidence(confidence).metadata(o.getMetadata()).build();
    }

    private static ObservationType parseType(JsonNode typeNode) {
        if (typeNode != null && typeNode.isTextual()) {
            try {
                return ObservationType.valueOf(typeNode.asText().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.debug("Unknown observation type '{}'; defaulting to DEDUCTIVE", typeNode.asText());
            }
        }
        return ObservationType.DEDUCTIVE;
    }
}
