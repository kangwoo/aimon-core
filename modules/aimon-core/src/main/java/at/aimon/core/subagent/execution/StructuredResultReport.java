package at.aimon.core.subagent.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.schema.DefaultToolInputSchemaValidator;
import at.aimon.core.agent.tool.schema.ToolInputSchemaValidator;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.subagent.SubagentExecutionPolicy;

/**
 * The structured result channel of a fork whose execution policy has a
 * {@linkplain SubagentExecutionPolicy#getResultSchema() result schema}: the {@value #TOOL_NAME} tool the fork is
 * offered, how a call to it is judged, and how an accepted payload is copied and written out.
 *
 * <p>
 * The tool is not a registered {@code Tool}. {@link DefaultSubagentExecutor} offers its definition beside the fork's
 * tools and answers its calls itself, so no hook, permission prompt or approval sees them; the policy that asks for it
 * is trusted code, and the call has no effect beyond the fork.
 *
 * <p>
 * <b>What is checked.</b> The schema is checked by the shared {@link ToolInputSchemaValidator}, which stops after one
 * level; this class applies it again at every object it can reach through {@code properties} and {@code items}, so a
 * nested {@code required}, {@code type}, {@code enum} or {@code additionalProperties: false} is enforced too, and each
 * violation names where it is ({@code findings[2]}). Ranges, {@code pattern}, {@code format} and the composition
 * keywords are not — the validator's own line (shape here, ranges in the consumer) — and a property using a
 * composition keyword is skipped. The payload's length as compact JSON is bounded by
 * {@link SubagentExecutionPolicy#getResultPayloadMaxChars()}.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class StructuredResultReport {

    /** The name of the tool a fork reports its structured result with. */
    public static final String TOOL_NAME = "ReportResult";

    /** What the tool tells the fork it is for. */
    static final String DESCRIPTION = "Report this task's structured result. This task requires one: call this tool "
            + "with your result, in the shape its input schema declares, before your final answer. A call that does "
            + "not fit the schema is rejected with what to fix, and nothing is recorded until a call fits. You may "
            + "call it again to replace an earlier report; the last accepted one is what is delivered, complete and "
            + "never truncated. Your final answer may still summarise it in prose.";

    /** What a grace round's reminder adds when the fork has this tool. */
    static final String GRACE_ROUND_NOTE = " The one exception is " + TOOL_NAME + ": a call to it in this response "
            + "is still checked and, if it fits the schema, recorded — there is no later turn to fix a rejected one.";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolInputSchemaValidator VALIDATOR = new DefaultToolInputSchemaValidator();

    private static final String KEY_TYPE = "type";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_ITEMS = "items";
    private static final String TYPE_OBJECT = "object";
    private static final String TYPE_ARRAY = "array";
    private static final Set<String> COMPOSITION_KEYWORDS = Set.of("$ref", "oneOf", "anyOf", "allOf", "not");

    private StructuredResultReport() {
    }

    /**
     * The tool definition a fork under {@code policy} is offered.
     *
     * @param policy
     *            the fork's policy (must not be null, and must have a result schema)
     * @return the definition, whose input schema is the policy's result schema
     */
    static ToolDefinition definition(SubagentExecutionPolicy policy) {
        Objects.requireNonNull(policy, "policy cannot be null");
        return ToolDefinition.of(TOOL_NAME, DESCRIPTION, policy.getResultSchema()
                .orElseThrow(() -> new IllegalArgumentException("the policy asks for no structured result")));
    }

    /**
     * Judges one {@value #TOOL_NAME} call.
     *
     * @param policy
     *            the fork's policy (must not be null, and must have a result schema)
     * @param toolUse
     *            the call (must not be null)
     * @return the outcome: the answer for the transcript and, when the call fits, the payload to record
     */
    static Outcome judge(SubagentExecutionPolicy policy, ToolUse toolUse) {
        Objects.requireNonNull(policy, "policy cannot be null");
        Objects.requireNonNull(toolUse, "toolUse cannot be null");
        final Map<String, Object> schema = policy.getResultSchema()
                .orElseThrow(() -> new IllegalArgumentException("the policy asks for no structured result"));
        final List<String> violations = validate(schema, toolUse.getInput());
        if (!violations.isEmpty()) {
            final StringBuilder message = new StringBuilder("Not recorded: the result does not fit this task's "
                    + "schema. Fix the following and call " + TOOL_NAME + " again with the whole result:");
            violations.forEach(violation -> message.append("\n- ").append(violation));
            return new Outcome(ToolUseResult.error(toolUse.getId(), message.toString()), null, violations.size());
        }
        final Map<String, Object> payload = copyOf(toolUse.getInput());
        final int length = toJson(payload).length();
        if (length > policy.getResultPayloadMaxChars()) {
            return new Outcome(ToolUseResult.error(toolUse.getId(),
                    "Not recorded: the result is " + length + " characters as JSON and this task accepts at most "
                            + policy.getResultPayloadMaxChars() + ". Shorten it — quote less, summarise long fields — "
                            + "and call " + TOOL_NAME + " again with the whole result."),
                    null, 1);
        }
        return new Outcome(ToolUseResult.success(toolUse.getId(), "Recorded (" + length + " characters). It will be "
                + "delivered as reported; a later " + TOOL_NAME + " call replaces it."), payload, 0);
    }

    /**
     * Checks {@code value} against {@code schema} at every level the schema describes.
     *
     * @param schema
     *            an object schema (must not be null)
     * @param value
     *            the value to check (must not be null)
     * @return the violation sentences, in the order found; empty when the value fits
     */
    static List<String> validate(Map<String, Object> schema, Map<String, Object> value) {
        Objects.requireNonNull(schema, "schema cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        final List<String> violations = new ArrayList<>();
        validateObject("", schema, value, violations);
        return violations;
    }

    private static void validateObject(String path, Map<String, Object> schema, Map<String, Object> value,
            List<String> violations) {
        VALIDATOR.validate(schema, ToolInput.of(value)).getViolations()
                .forEach(violation -> violations.add(path.isEmpty() ? violation : "In '" + path + "': " + violation));
        final Map<String, Object> properties = asMap(schema.get(KEY_PROPERTIES));
        if (properties == null) {
            return;
        }
        for (Map.Entry<String, Object> declared : properties.entrySet()) {
            final Object child = value.get(declared.getKey());
            final Map<String, Object> childSchema = asMap(declared.getValue());
            if (child != null && childSchema != null) {
                validateNested(path.isEmpty() ? declared.getKey() : path + "." + declared.getKey(), childSchema, child,
                        violations);
            }
        }
    }

    /**
     * Goes one level into {@code value}: an object is checked against its own schema, an array item by item. The
     * value's own type was checked by the level above; a value of another type is not looked into.
     */
    private static void validateNested(String path, Map<String, Object> schema, Object value, List<String> violations) {
        if (schema.keySet().stream().anyMatch(COMPOSITION_KEYWORDS::contains)) {
            return;
        }
        final Object type = schema.get(KEY_TYPE);
        if (TYPE_OBJECT.equals(type) && value instanceof Map<?, ?> map) {
            validateObject(path, schema, asMap(map), violations);
        } else if (TYPE_ARRAY.equals(type) && value instanceof List<?> list) {
            final Map<String, Object> itemSchema = asMap(schema.get(KEY_ITEMS));
            if (itemSchema == null || itemSchema.keySet().stream().anyMatch(COMPOSITION_KEYWORDS::contains)) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                final String itemPath = path + "[" + i + "]";
                // The item's own type and enum, through the same validator: a one-property object whose property is
                // the item, named by its path so the sentence says which item.
                final Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put(KEY_TYPE, TYPE_OBJECT);
                wrapper.put(KEY_PROPERTIES, Map.of(itemPath, itemSchema));
                wrapper.put("required", List.of(itemPath));
                final Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put(itemPath, list.get(i));
                violations.addAll(VALIDATOR.validate(wrapper, ToolInput.of(wrapped)).getViolations());
                if (list.get(i) != null) {
                    validateNested(itemPath, itemSchema, list.get(i), violations);
                }
            }
        }
    }

    /**
     * Copies a JSON-like value deeply into unmodifiable maps and lists, keeping key order. Map entries whose value is
     * null are dropped, as {@link ToolInput} drops them, so what is delivered is what was checked.
     *
     * @param value
     *            the map to copy (must not be null)
     * @return the copy (unmodifiable all the way down)
     */
    public static Map<String, Object> copyOf(Map<String, Object> value) {
        Objects.requireNonNull(value, "value cannot be null");
        return copyMap(value);
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        final Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                copy.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (value instanceof List<?> list) {
            final List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(item == null ? null : copyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * Writes a structured result as compact, single-line JSON — the form its length is bounded in and the form the
     * spawning model reads.
     *
     * @param payload
     *            the payload (must not be null)
     * @return the JSON text (never null)
     * @throws IllegalArgumentException
     *             if the payload holds a value JSON cannot represent
     */
    public static String toJson(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload cannot be null");
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Structured result is not JSON-serializable: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** The judgment of one call: the answer for the transcript, and the payload to record when it was accepted. */
    static final class Outcome {
        private final ToolUseResult answer;
        private final Map<String, Object> payload;
        private final int violations;

        private Outcome(ToolUseResult answer, Map<String, Object> payload, int violations) {
            this.answer = answer;
            this.payload = payload;
            this.violations = violations;
        }

        /** @return the tool result the fork is answered with */
        ToolUseResult answer() {
            return answer;
        }

        /** @return the accepted payload, or empty when the call was rejected */
        Optional<Map<String, Object>> payload() {
            return Optional.ofNullable(payload);
        }

        /** @return how many problems a rejected call had (0 when accepted) */
        int violations() {
            return violations;
        }
    }
}
