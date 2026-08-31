package at.aimon.core.workflow.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.base.text.CodeFences;

/**
 * Prompt-and-parse structured output for workflow {@code agent()} calls.
 *
 * <p>
 * When an {@code AgentTask} carries a result schema, {@link #augmentGoal} appends a JSON-emit instruction to the goal,
 * and after the subagent finishes {@link #parse} strips any markdown fences, parses the final answer as a JSON object,
 * and validates it against the schema (a minimal, lenient JSON-Schema subset: {@code type}, {@code required},
 * {@code properties}, {@code items}, {@code enum}). This keeps structured output entirely in the workflow layer —
 * no subagent-executor or LLM-client changes — at the cost of relying on the model to emit well-formed JSON (guarded by
 * validation).
 */
final class StructuredOutputSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private StructuredOutputSupport() {
    }

    /**
     * Appends a strict JSON-emit instruction plus the serialized schema to the goal.
     *
     * @param goal
     *            the original goal
     * @param schema
     *            the JSON Schema (nested map)
     * @return the augmented goal (or the original goal unchanged if the schema could not be serialized)
     */
    static String augmentGoal(String goal, Map<String, Object> schema) {
        final String schemaJson;
        try {
            schemaJson = MAPPER.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            return goal;
        }
        return goal + "\n\nRespond with ONLY a single JSON object that conforms to the JSON Schema below. Do not "
                + "include any prose, explanation, or markdown code fences — output the raw JSON object and nothing "
                + "else.\n\nJSON Schema:\n" + schemaJson;
    }

    /**
     * Strips code fences, parses {@code text} as a JSON object, and validates it against {@code schema}.
     *
     * @param text
     *            the subagent's final answer (nullable)
     * @param schema
     *            the JSON Schema to validate against
     * @return the parsed, validated object; empty when {@code text} is null, is not a JSON object, or fails validation
     */
    static Optional<Map<String, Object>> parse(String text, Map<String, Object> schema) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            final JsonNode node = MAPPER.readTree(CodeFences.strip(text));
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            final Map<String, Object> value = MAPPER.convertValue(node, MAP_TYPE);
            // validate() is kept INSIDE the try on purpose: a malformed schema or value must degrade to empty, never
            // crash the workflow run.
            return validate(value, schema) ? Optional.of(value) : Optional.empty();
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean validate(Object value, Map<String, Object> schema) {
        final Object typeObj = schema.get("type");
        if (typeObj instanceof String type) {
            if (!typeMatches(type, value)) {
                return false;
            }
            if ("object".equals(type) && !validateObject((Map<String, Object>) value, schema)) {
                return false;
            }
            if ("array".equals(type) && !validateArray((List<Object>) value, schema)) {
                return false;
            }
        }
        final Object allowed = schema.get("enum");
        // Null-guard: an immutable List.of(...) enum throws on contains(null); a null value is simply not a member.
        return !(allowed instanceof List<?> options) || (value != null && options.contains(value));
    }

    private static boolean typeMatches(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue());
            case "number" -> value instanceof Number;
            case "null" -> value == null;
            default -> true; // unknown type keyword → lenient
        };
    }

    @SuppressWarnings("unchecked")
    private static boolean validateObject(Map<String, Object> value, Map<String, Object> schema) {
        final Object required = schema.get("required");
        if (required instanceof List<?> requiredKeys) {
            for (final Object key : requiredKeys) {
                if (!value.containsKey(key)) {
                    return false;
                }
            }
        }
        final Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> props) {
            for (final Map.Entry<?, ?> entry : props.entrySet()) {
                // Validate a property whenever it is PRESENT (even if its value is JSON null) so a null value is
                // checked
                // against its declared type rather than silently accepted.
                if (value.containsKey(entry.getKey()) && entry.getValue() instanceof Map<?, ?> propSchema
                        && !validate(value.get(entry.getKey()), (Map<String, Object>) propSchema)) {
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean validateArray(List<Object> value, Map<String, Object> schema) {
        if (schema.get("items") instanceof Map<?, ?> itemSchema) {
            for (final Object item : value) {
                if (!validate(item, (Map<String, Object>) itemSchema)) {
                    return false;
                }
            }
        }
        return true;
    }
}
