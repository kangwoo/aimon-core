package at.aimon.core.workflow.impl;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.workflow.StepOutcome;

/**
 * Versioned JSON codec for {@link StepOutcome}, used by {@link VfsStepResultCache} to persist a cached step outcome as
 * one object (design §5.3).
 *
 * <p>
 * Unlike {@code JsonSessionSnapshotCodec} (which hand-serializes the non-Jackson {@code Message} graph), a
 * {@link StepOutcome} is composed entirely of Jackson-friendly value types (text, a {@code Map<String,Object>}, two
 * {@code long}s, an enum, a hash string), so this codec is a thin, explicit, versioned envelope. {@link #decode} throws
 * {@link IllegalArgumentException} on malformed or unsupported-version input; the caller ({@code VfsStepResultCache})
 * treats that as a cache miss, so a corrupt object degrades to re-execution rather than failing the run.
 */
final class StepOutcomeCodec {

    /**
     * Format version stamped into every encoded object; {@link #decode} rejects any other value. Bumped to 2
     * for the {@code structureFingerprint} field — a persisted v1 object fails the version check and degrades to a
     * cache
     * miss (re-execution), so there is no legacy fingerprint-less replay window (design §6.5).
     */
    static final int FORMAT_VERSION = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String FIELD_VERSION = "fv";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_STRUCTURED = "structured";
    private static final String FIELD_TOTAL_TOKENS = "totalTokens";
    private static final String FIELD_COST_MICROS = "costMicros";
    private static final String FIELD_COMPLETION_REASON = "completionReason";
    private static final String FIELD_INPUT_HASH = "inputHash";
    private static final String FIELD_STRUCTURE_FINGERPRINT = "structureFingerprint";

    /**
     * Serializes an outcome to a self-describing JSON object.
     *
     * @param outcome
     *            the outcome to encode (must not be null)
     * @return the JSON string
     */
    String encode(StepOutcome outcome) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put(FIELD_VERSION, FORMAT_VERSION);
        node.put(FIELD_TEXT, outcome.text());
        outcome.structured().ifPresent(s -> node.set(FIELD_STRUCTURED, MAPPER.valueToTree(s)));
        node.put(FIELD_TOTAL_TOKENS, outcome.totalTokens());
        node.put(FIELD_COST_MICROS, outcome.costMicros());
        node.put(FIELD_COMPLETION_REASON, outcome.completionReason().name());
        node.put(FIELD_INPUT_HASH, outcome.inputHash());
        node.put(FIELD_STRUCTURE_FINGERPRINT, outcome.structureFingerprint());
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Serializing a plain ObjectNode does not fail in practice; surface it as unchecked if it ever does.
            throw new IllegalStateException("Failed to encode StepOutcome", e);
        }
    }

    /**
     * Parses a JSON string previously produced by {@link #encode}.
     *
     * @param json
     *            the JSON string (must not be null)
     * @return the decoded outcome
     * @throws IllegalArgumentException
     *             if the JSON is malformed, is the wrong version, or is missing required fields
     */
    StepOutcome decode(String json) {
        final JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed StepOutcome JSON: " + e.getMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("StepOutcome JSON is not an object");
        }
        final int version = node.path(FIELD_VERSION).asInt(-1);
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported StepOutcome format version: " + version);
        }
        final JsonNode textNode = node.get(FIELD_TEXT);
        final JsonNode reasonNode = node.get(FIELD_COMPLETION_REASON);
        final JsonNode inputHashNode = node.get(FIELD_INPUT_HASH);
        final JsonNode fingerprintNode = node.get(FIELD_STRUCTURE_FINGERPRINT);
        if (textNode == null || !textNode.isTextual() || reasonNode == null || !reasonNode.isTextual()
                || inputHashNode == null || !inputHashNode.isTextual() || fingerprintNode == null
                || !fingerprintNode.isTextual()) {
            throw new IllegalArgumentException(
                    "StepOutcome JSON is missing required text/reason/inputHash/structureFingerprint fields");
        }
        final CompletionReason reason;
        try {
            reason = CompletionReason.valueOf(reasonNode.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown completionReason: " + reasonNode.asText(), e);
        }
        final JsonNode structuredNode = node.get(FIELD_STRUCTURED);
        final Map<String, Object> structured = structuredNode == null || structuredNode.isNull()
                ? null
                : MAPPER.convertValue(structuredNode, MAP_TYPE);
        return StepOutcome.builder().text(textNode.asText()).structured(structured)
                .totalTokens(node.path(FIELD_TOTAL_TOKENS).asLong(0)).costMicros(node.path(FIELD_COST_MICROS).asLong(0))
                .completionReason(reason).inputHash(inputHashNode.asText())
                .structureFingerprint(fingerprintNode.asText()).build();
    }
}
