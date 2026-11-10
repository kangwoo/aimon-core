package at.aimon.core.llms.anthropic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Reads the top-level {@code input_transformations} array a response carries when the request sent the
 * {@value AnthropicThinkingResolution#BLOCK_BINDING_BETA} beta header, and reports the thinking blocks the server
 * dropped.
 *
 * <p>
 * SDK 3.3.0 does not model the field, so it is read from the additional properties of the blocking
 * {@code Message} or of a streaming {@code message_start} / {@code message_delta} event. Not every drop is a prompt
 * prefix edit: the vendor also drops blocks another model produced (a model switch) and blocks another account
 * produced ({@code organization_binding_mismatch}). So drops are grouped by each entry's {@code reason}, one signature
 * per reason, and only the prefix reason gets a cause hint.
 *
 * <p>
 * Reporting must never fail a request: an absent, empty or unparseable value says nothing.
 *
 * <p>
 * Stateless.
 */
final class AnthropicInputTransformations {

    /** The response property, as the vendor spells it. */
    static final String PROPERTY = "input_transformations";

    private static final String UNSPECIFIED_REASON = "unspecified";

    private static final TypeReference<List<Map<String, Object>>> ENTRIES = new TypeReference<>() {
    };

    private AnthropicInputTransformations() {
    }

    /**
     * Reports the drops listed in {@code properties}, if any.
     *
     * @param properties
     *            the additional properties of a response or stream event
     * @param modelName
     *            the model the response names, for the signature and the text
     * @param recurring
     *            the client's recurring-divergence reporter (1st, 10th, 100th … occurrence per signature)
     * @return whether a non-empty array was found and reported, so a stream reports it once
     */
    static boolean report(Map<String, JsonValue> properties, String modelName, AnthropicDivergenceReporter recurring) {
        try {
            final JsonValue value = properties == null ? null : properties.get(PROPERTY);
            if (value == null) {
                return false;
            }
            final List<Map<String, Object>> entries = value.convert(ENTRIES);
            if (entries == null || entries.isEmpty()) {
                return false;
            }
            final Map<String, Integer> countByReason = new LinkedHashMap<>();
            for (Map<String, Object> entry : entries) {
                final Object reason = entry == null ? null : entry.get("reason");
                countByReason.merge(reason instanceof String text && !text.isEmpty() ? text : UNSPECIFIED_REASON, 1,
                        Integer::sum);
            }
            final String model = modelName == null || modelName.isEmpty() ? "unknown-model" : modelName;
            countByReason.forEach((reason, count) -> recurring.report("thinkingBlocksDropped@" + model + "@" + reason,
                    "{} thinking block(s) replayed to {} were dropped by the server (reason: {}); the model re-derives "
                            + "that reasoning.{}",
                    count, model, reason, causeHint(reason)));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String causeHint(String reason) {
        if (reason.contains("prefix")) {
            return " The prompt prefix changed since the blocks were produced: a system prompt re-rendered on a new "
                    + "turn, compaction, or the rolling context window dropping old messages.";
        }
        return "";
    }
}
