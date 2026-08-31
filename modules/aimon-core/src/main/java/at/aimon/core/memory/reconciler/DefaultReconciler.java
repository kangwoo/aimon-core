/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.memory.reconciler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/**
 * Default {@link Reconciler} per design doc §6.4.
 *
 * <p>
 * Two-tier policy:
 *
 * <ol>
 * <li><b>Heuristic fast path</b> — empty conflict list → {@link
 * ReconcileDecision.Accept}. Exact content match (case-insensitive, trimmed)
 * with any conflict → {@link ReconcileDecision.Reject} with the matching id in
 * the reason. These cases are settled without spending an LLM call.</li>
 * <li><b>LLM judge</b> — everything else is forwarded to {@link LlmClient}
 * with a strict-JSON system prompt; the response is parsed and validated to be
 * one of the four decision variants. Any malformed output, hallucinated ids
 * outside the conflict set, or LLM error <em>conservatively</em> falls back to
 * {@link ReconcileDecision.Accept} so the caller never loses information.</li>
 * </ol>
 *
 * <p>
 * Side-effect free: implementations of this interface are forbidden from
 * touching the store. The caller (deriver / dreamer) is responsible for
 * acting on the returned decision.
 *
 * <p>
 * Thread-safe as long as the injected {@link LlmClient} is.
 */
public final class DefaultReconciler implements Reconciler {

    private static final Logger log = LoggerFactory.getLogger(DefaultReconciler.class);

    private static final String JUDGE_SYSTEM_PROMPT = """
            You are a memory reconciler. Given one CANDIDATE observation and a list of CONFLICT observations
            about the same subject, decide what should happen to the candidate.

            Choose ONE of:
              - "accept"  : candidate adds new information, keep all observations
              - "reject"  : candidate is a duplicate or strictly weaker version of a conflict; discard
              - "replace" : candidate is a strictly better version of EXACTLY ONE conflict; supersede that one
              - "merge"   : candidate and EXACTLY ONE conflict cover overlapping ground; produce a merged statement

            Output ONLY a JSON object (no markdown, no commentary) with these fields:
              {
                "decision":          "accept" | "reject" | "replace" | "merge",
                "target_local_id":   string  (required when decision is "replace" or "merge",
                                              must equal one of the CONFLICT ids; otherwise omit),
                "merged_content":    string  (required when decision is "merge", non-empty),
                "merged_confidence": number  (required when decision is "merge", in [0.0, 1.0]),
                "reason":            string  (required when decision is "reject", short audit phrase)
              }

            Examples:
              {"decision": "accept"}
              {"decision": "reject", "reason": "duplicate of obs-7"}
              {"decision": "replace", "target_local_id": "obs-7"}
              {"decision": "merge", "target_local_id": "obs-7",
               "merged_content": "Alice prefers tea with milk", "merged_confidence": 0.9}
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final LlmCallMetadata SELF_METADATA = LlmCallMetadata.builder().component("memory")
            .feature("reconciliation").build();

    private final LlmClient llmClient;
    private final String llmModelName;

    public DefaultReconciler(LlmClient llmClient, String llmModelName) {
        this.llmClient = new BoundMetadataLlmClient(Objects.requireNonNull(llmClient, "llmClient cannot be null"),
                SELF_METADATA);
        this.llmModelName = Objects.requireNonNull(llmModelName, "llmModelName cannot be null");
        if (llmModelName.isBlank()) {
            throw new IllegalArgumentException("llmModelName cannot be blank");
        }
    }

    @Override
    public ReconcileDecision evaluate(Observation candidate, List<Observation> conflicts) {
        Objects.requireNonNull(candidate, "candidate cannot be null");
        Objects.requireNonNull(conflicts, "conflicts cannot be null");

        if (conflicts.isEmpty()) {
            log.debug("Reconciler short-circuit accept (no conflicts): candidate={}", candidate.getId());
            return ReconcileDecision.Accept.instance();
        }

        Observation duplicate = findExactDuplicate(candidate, conflicts);
        if (duplicate != null) {
            log.debug("Reconciler short-circuit reject (exact duplicate): candidate={}, duplicateOf={}",
                    candidate.getId(), duplicate.getId());
            return new ReconcileDecision.Reject("duplicate of " + duplicate.getId().getLocalId());
        }

        ReconcileDecision judged = judgeWithLlm(candidate, conflicts);
        if (judged == null) {
            log.warn("LLM judge failed for candidate={}, falling back to Accept", candidate.getId());
            return ReconcileDecision.Accept.instance();
        }
        log.debug("Reconciler decision: candidate={}, conflicts={}, decision={}", candidate.getId(), conflicts.size(),
                judged.getClass().getSimpleName());
        return judged;
    }

    private static Observation findExactDuplicate(Observation candidate, List<Observation> conflicts) {
        String candidateNorm = normalize(candidate.getContent());
        for (Observation c : conflicts) {
            if (normalize(c.getContent()).equals(candidateNorm)) {
                return c;
            }
        }
        return null;
    }

    private static String normalize(String content) {
        return content.trim().toLowerCase(Locale.ROOT);
    }

    private ReconcileDecision judgeWithLlm(Observation candidate, List<Observation> conflicts) {
        Map<String, Observation> conflictsById = new LinkedHashMap<>();
        for (Observation c : conflicts) {
            conflictsById.put(c.getId().getLocalId(), c);
        }

        StringBuilder prompt = new StringBuilder("CANDIDATE:\n");
        prompt.append("- id: ").append(candidate.getId().getLocalId()).append('\n');
        prompt.append("- content: ").append(candidate.getContent()).append('\n');
        prompt.append("- confidence: ").append(candidate.getConfidence()).append('\n');
        prompt.append("\nCONFLICTS:\n");
        for (Observation c : conflicts) {
            prompt.append("- id: ").append(c.getId().getLocalId()).append('\n');
            prompt.append("  content: ").append(c.getContent()).append('\n');
            prompt.append("  confidence: ").append(c.getConfidence()).append('\n');
        }

        try {
            LlmModel modelConfig = LlmModel.builder().name(llmModelName).build();
            LlmResponse response = llmClient.sendMessage(JUDGE_SYSTEM_PROMPT, List.of(Message.user(prompt.toString())),
                    List.of(), modelConfig);
            String text = response.getTextContent();
            if (text == null || text.isBlank()) {
                log.warn("Empty LLM judge response for candidate {}", candidate.getId());
                return null;
            }

            JsonNode node = OBJECT_MAPPER.readTree(CodeFences.strip(text));
            if (!node.isObject()) {
                log.warn("Judge response was not a JSON object (was {}); dropping", node.getNodeType());
                return null;
            }
            JsonNode decisionNode = node.get("decision");
            if (decisionNode == null || !decisionNode.isTextual()) {
                log.warn("Judge response missing textual 'decision'; dropping");
                return null;
            }
            String decision = decisionNode.asText().trim().toLowerCase(Locale.ROOT);

            return parseDecision(decision, node, candidate, conflictsById);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse judge response as JSON: {}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("LLM judge call failed for candidate {}: {}", candidate.getId(), e.getMessage());
            return null;
        }
    }

    private static ReconcileDecision parseDecision(String decision, JsonNode node, Observation candidate,
            Map<String, Observation> conflictsById) {
        return switch (decision) {
            case "accept" -> ReconcileDecision.Accept.instance();
            case "reject" -> {
                String reason = textOrNull(node.get("reason"));
                if (reason == null || reason.isBlank()) {
                    reason = "rejected by judge";
                }
                yield new ReconcileDecision.Reject(reason);
            }
            case "replace" -> {
                Observation target = resolveTarget(node, conflictsById);
                yield target == null ? null : new ReconcileDecision.Replace(target.getId());
            }
            case "merge" -> {
                Observation target = resolveTarget(node, conflictsById);
                if (target == null) {
                    yield null;
                }
                String content = textOrNull(node.get("merged_content"));
                if (content == null || content.isBlank()) {
                    log.warn("Judge merge missing merged_content; dropping");
                    yield null;
                }
                double confidence = parseConfidence(node.get("merged_confidence"),
                        Math.max(candidate.getConfidence(), target.getConfidence()));
                Observation merged = buildMergedObservation(candidate, target, content.trim(), confidence);
                yield new ReconcileDecision.Merge(target.getId(), merged);
            }
            default -> {
                log.warn("Unknown judge decision '{}'; dropping", decision);
                yield null;
            }
        };
    }

    private static Observation resolveTarget(JsonNode node, Map<String, Observation> conflictsById) {
        String targetLocalId = textOrNull(node.get("target_local_id"));
        if (targetLocalId == null || targetLocalId.isBlank()) {
            log.warn("Judge decision requires target_local_id but none was provided");
            return null;
        }
        Observation target = conflictsById.get(targetLocalId.trim());
        if (target == null) {
            log.warn("Judge target_local_id '{}' is not in the conflict set; treating as hallucination", targetLocalId);
            return null;
        }
        return target;
    }

    private static Observation buildMergedObservation(Observation candidate, Observation other, String content,
            double confidence) {
        Set<String> sourceIds = new LinkedHashSet<>(candidate.getSourceMessageIds());
        sourceIds.addAll(other.getSourceMessageIds());
        Observation winner = candidate.getConfidence() >= other.getConfidence() ? candidate : other;
        return Observation.builder().id(winner.getId()).subject(winner.getSubject()).observer(winner.getObserver())
                .content(content).type(winner.getType()).sourceMessageIds(new ArrayList<>(sourceIds))
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

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return node.asText();
    }

    /** Telemetry-friendly summary of this reconciler's tuning. */
    public String describe() {
        return String.format(Locale.ROOT, "DefaultReconciler{model=%s}", llmModelName);
    }
}
