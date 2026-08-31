package at.aimon.core.memory.dreamer;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
 * {@link SurprisalScorer} that delegates the redundancy judgement to an
 * {@link LlmClient} — no embedding model required.
 *
 * <p>
 * The scorer asks the LLM to rate how much overlapping information the
 * candidate observation shares with the supplied neighbors as a single
 * similarity score in {@code [0.0, 1.0]}. Surprisal is then derived as
 * {@code 1.0 - similarity} so the value matches the
 * {@link EmbeddingSurprisalScorer} contract: {@code 0.0} = fully redundant,
 * {@code 1.0} = completely novel.
 *
 * <p>
 * <b>Failure mode is fail-closed</b>: a missing/blank LLM response, malformed
 * JSON (including JSON with trailing commentary), and runtime errors during
 * the call all collapse to {@code 1.0}. Finite-but-out-of-range similarities
 * are clamped to {@code [0.0, 1.0]} before inversion, not collapsed to
 * {@code 1.0}. The dreamer treats high surprisal as "novel — leave it alone",
 * so a failed judgement preserves data rather than triggering a spurious
 * merge.
 *
 * <p>
 * Empty neighbor list yields {@code 1.0} per the {@link SurprisalScorer}
 * contract — no LLM call is issued in that case.
 *
 * <p>
 * The judge runs at {@code temperature = 0.0} for determinism. Each evaluation
 * issues exactly one LLM round-trip.
 *
 * <p>
 * Thread-safe as long as the injected {@link LlmClient} is.
 */
public final class LlmJudgeSurprisalScorer implements SurprisalScorer {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeSurprisalScorer.class);

    /**
     * Strict mapper: rejects trailing tokens so a JSON object with appended commentary (e.g. an LLM that follows the
     * required object with a trailing prose paragraph) parses as {@link JsonProcessingException} rather than silently
     * succeeding on the leading object alone. Treated as immutable after class init — do not reconfigure.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    private static final String SYSTEM_PROMPT = """
            You are a redundancy judge for a memory consolidation system.
            Given a CANDIDATE observation and a list of NEIGHBOR observations about the same subject,
            decide how much information the candidate already shares with the neighbors collectively.

            Output ONLY a JSON object (no markdown, no commentary) with these fields:
            - "similarity": overlap score in [0.0, 1.0] (number, required)
                * 0.0 = candidate states something completely new / unrelated
                * 0.5 = candidate partially overlaps with one or more neighbors
                * 1.0 = candidate is fully implied by the neighbors (redundant)
            - "rationale": one short sentence explaining the score (string, optional)

            Example:
            {"similarity": 0.92, "rationale": "candidate restates neighbor 1 with a synonym"}
            """;

    private static final LlmCallMetadata SELF_METADATA = LlmCallMetadata.builder().component("memory")
            .feature("surprisal").build();

    private final LlmClient llmClient;
    private final String llmModelName;

    public LlmJudgeSurprisalScorer(LlmClient llmClient, String llmModelName) {
        this.llmClient = new BoundMetadataLlmClient(Objects.requireNonNull(llmClient, "llmClient must not be null"),
                SELF_METADATA);
        this.llmModelName = Objects.requireNonNull(llmModelName, "llmModelName must not be null");
        if (llmModelName.isBlank()) {
            throw new IllegalArgumentException("llmModelName must not be blank");
        }
    }

    @Override
    public double score(Observation observation, List<Observation> neighbors) {
        Objects.requireNonNull(observation, "observation must not be null");
        Objects.requireNonNull(neighbors, "neighbors must not be null");

        if (neighbors.isEmpty()) {
            return 1.0d;
        }

        final String userPrompt = buildPrompt(observation, neighbors);

        try {
            final LlmModel modelConfig = LlmModel.builder().name(llmModelName).temperature(0.0d).build();
            final LlmResponse response = llmClient.sendMessage(SYSTEM_PROMPT, List.of(Message.user(userPrompt)),
                    List.of(), modelConfig);
            final String text = response.getTextContent();
            if (text == null || text.isBlank()) {
                log.warn("Empty LLM response while scoring observation {}; treating as novel", observation.getId());
                return 1.0d;
            }

            final JsonNode node = OBJECT_MAPPER.readTree(CodeFences.strip(text));
            if (!node.isObject()) {
                log.warn("Judge response was not a JSON object (was {}); treating as novel", node.getNodeType());
                return 1.0d;
            }
            final JsonNode similarityNode = node.get("similarity");
            if (similarityNode == null || !similarityNode.isNumber()) {
                log.warn("Judge response missing numeric 'similarity'; treating as novel");
                return 1.0d;
            }
            final double similarity = similarityNode.asDouble();
            if (!Double.isFinite(similarity)) {
                log.warn("Judge response had non-finite 'similarity' ({}); treating as novel", similarity);
                return 1.0d;
            }

            final double surprisal = 1.0d - clamp01(similarity);
            log.debug("LlmJudge surprisal: observation={}, neighbors={}, similarity={}, surprisal={}",
                    observation.getId(), neighbors.size(), similarity, surprisal);
            return surprisal;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse judge response as JSON: {}; treating as novel", e.getMessage());
            return 1.0d;
        } catch (RuntimeException e) {
            log.warn("LLM judge call failed for observation {}: {}; treating as novel", observation.getId(),
                    e.getMessage());
            return 1.0d;
        }
    }

    private static String buildPrompt(Observation candidate, List<Observation> neighbors) {
        final StringBuilder sb = new StringBuilder();
        sb.append("CANDIDATE:\n").append(candidate.getContent()).append("\n\nNEIGHBORS:\n");
        int n = 1;
        for (Observation neighbor : neighbors) {
            sb.append(n++).append(". ").append(neighbor.getContent()).append('\n');
        }
        return sb.toString();
    }

    private static double clamp01(double v) {
        if (v < 0.0d) {
            return 0.0d;
        }
        if (v > 1.0d) {
            return 1.0d;
        }
        return v;
    }
}
