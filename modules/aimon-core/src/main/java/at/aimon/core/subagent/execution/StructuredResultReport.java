package at.aimon.core.subagent.execution;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import at.aimon.core.base.JsonValues;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.subagent.SubagentExecutionPolicy;

/**
 * The structured result channel of a fork whose execution policy has a
 * {@linkplain SubagentExecutionPolicy#getResultSchema() result schema}: the {@value #TOOL_NAME} tool the fork is
 * offered, how a call to it is judged, and how an accepted payload is written out.
 *
 * <p>
 * The tool is not a registered {@code Tool}. {@link DefaultSubagentExecutor} offers its definition beside the fork's
 * tools and answers its calls itself, so no hook, permission prompt or approval sees them; the policy that asks for it
 * is trusted code, and the call has no effect beyond the fork.
 *
 * <p>
 * <b>What is checked.</b> The schema is checked by the shared {@link ToolInputSchemaValidator}, which stops after one
 * level; this class applies it again at every object and array it can reach through {@code properties} and
 * {@code items}, so a nested {@code required}, {@code type}, {@code enum} or {@code additionalProperties: false} is
 * enforced too, and each violation names where it is ({@code findings[2]}). A {@code type} may be a name or a list of
 * names ({@code ["object", "null"]}); a schema with no {@code type} but with {@code properties} or {@code items} is
 * looked into all the same. Ranges, {@code pattern}, {@code format} and the composition keywords are not checked — the
 * validator's own line (shape here, ranges in the consumer) — and a property using a composition keyword is skipped.
 * The payload's length as compact JSON is bounded by {@link SubagentExecutionPolicy#getResultPayloadMaxChars()}.
 *
 * <p>
 * <b>Nulls.</b> A {@code null} object member is absent, as everywhere on the tool path: it is dropped, and a required
 * one is reported missing. A {@code null} array item is kept in place and rejected unless the item schema's type lists
 * {@code "null"}.
 *
 * <p>
 * <b>Numbers.</b> Before it is checked, the payload is copied with its numbers normalised to the schema: a number that
 * numerically equals one of an {@code enum}'s values becomes that value ({@code 1.0} matches {@code 1}), and a
 * whole-valued number where the schema says {@code integer} becomes an integer ({@code 3.0} becomes {@code 3}). What is
 * delivered is that copy.
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
    private static final String KEY_REQUIRED = "required";
    private static final String KEY_ENUM = "enum";
    private static final String TYPE_OBJECT = "object";
    private static final String TYPE_ARRAY = "array";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_NULL = "null";
    private static final Set<String> COMPOSITION_KEYWORDS = Set.of("$ref", "oneOf", "anyOf", "allOf", "not");
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

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
        return ToolDefinition.of(TOOL_NAME, DESCRIPTION, schemaOf(policy));
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
        final Map<String, Object> schema = schemaOf(policy);
        final Map<String, Object> payload = normalise(schema, toolUse.getInput());
        final List<String> violations = validate(schema, payload);
        if (!violations.isEmpty()) {
            final StringBuilder message = new StringBuilder("Not recorded: the result does not fit this task's "
                    + "schema. Fix the following and call " + TOOL_NAME + " again with the whole result:");
            violations.forEach(violation -> message.append("\n- ").append(violation));
            return new Outcome(ToolUseResult.error(toolUse.getId(), message.toString()), null, violations.size());
        }
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

    private static Map<String, Object> schemaOf(SubagentExecutionPolicy policy) {
        return policy.getResultSchema()
                .orElseThrow(() -> new IllegalArgumentException("the policy asks for no structured result"));
    }

    /**
     * Checks {@code value} against {@code schema} at every level the schema describes. Numbers are compared as they
     * are; {@link #judge} normalises them first.
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
        // The shared validator checks only a schema whose type is exactly "object"; a union or an absent type that
        // still describes an object is handed to it in that form.
        final Map<String, Object> objectSchema = TYPE_OBJECT.equals(schema.get(KEY_TYPE))
                ? schema
                : withType(schema, TYPE_OBJECT);
        VALIDATOR.validate(objectSchema, ToolInput.of(value)).getViolations()
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
     * value's own type was checked by the level above; a value of a type the schema does not look into is left alone.
     */
    private static void validateNested(String path, Map<String, Object> schema, Object value, List<String> violations) {
        if (usesComposition(schema)) {
            return;
        }
        final Set<String> types = typesOf(schema);
        if (value instanceof Map<?, ?> map && describes(types, TYPE_OBJECT, schema, KEY_PROPERTIES)) {
            validateObject(path, schema, asMap(map), violations);
        } else if (value instanceof List<?> list && describes(types, TYPE_ARRAY, schema, KEY_ITEMS)) {
            final Map<String, Object> itemSchema = asMap(schema.get(KEY_ITEMS));
            if (itemSchema == null || usesComposition(itemSchema)) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                validateItem(path + "[" + i + "]", itemSchema, list.get(i), violations);
            }
        }
    }

    private static void validateItem(String itemPath, Map<String, Object> itemSchema, Object item,
            List<String> violations) {
        if (item == null) {
            if (!typesOf(itemSchema).contains(TYPE_NULL)) {
                violations.add("Item '" + itemPath + "' is null, which its schema does not allow.");
            }
            return;
        }
        // The item's own type and enum, through the same validator: a one-property object whose property is the item,
        // named by its path so the sentence says which item.
        final Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(KEY_TYPE, TYPE_OBJECT);
        wrapper.put(KEY_PROPERTIES, Map.of(itemPath, itemSchema));
        wrapper.put(KEY_REQUIRED, List.of(itemPath));
        violations.addAll(VALIDATOR.validate(wrapper, ToolInput.of(Map.of(itemPath, item))).getViolations());
        validateNested(itemPath, itemSchema, item, violations);
    }

    /** Whether a schema describes values of {@code type}: it names the type, or names none and has {@code key}. */
    private static boolean describes(Set<String> types, String type, Map<String, Object> schema, String key) {
        return types.contains(type) || types.isEmpty() && schema.containsKey(key);
    }

    /** The type names a schema declares, a single name or a list of them; empty when it declares none. */
    private static Set<String> typesOf(Map<String, Object> schema) {
        final Object declared = schema.get(KEY_TYPE);
        final Set<String> types = new LinkedHashSet<>();
        if (declared instanceof String name) {
            types.add(name);
        } else if (declared instanceof List<?> names) {
            names.stream().filter(String.class::isInstance).map(String.class::cast).forEach(types::add);
        }
        return types;
    }

    private static Map<String, Object> withType(Map<String, Object> schema, String type) {
        final Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put(KEY_TYPE, type);
        return copy;
    }

    /**
     * Copies {@code value} deeply and unmodifiably, with its numbers normalised to {@code schema}: a number equal to an
     * {@code enum} value becomes that value, a whole-valued number where an {@code integer} is declared becomes an
     * integer. Null members are dropped and null items kept ({@link JsonValues}).
     */
    static Map<String, Object> normalise(Map<String, Object> schema, Map<String, Object> value) {
        return JsonValues.immutableCopy(asMap(normaliseValue(schema, value)));
    }

    private static Object normaliseValue(Map<String, Object> schema, Object value) {
        if (value == null || schema == null || usesComposition(schema)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> properties = asMap(schema.get(KEY_PROPERTIES));
            final Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, member) -> copy.put(String.valueOf(key),
                    normaliseValue(properties == null ? null : asMap(properties.get(String.valueOf(key))), member)));
            return copy;
        }
        if (value instanceof List<?> list) {
            final Map<String, Object> itemSchema = asMap(schema.get(KEY_ITEMS));
            final List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(normaliseValue(itemSchema, item)));
            return copy;
        }
        if (value instanceof Number number) {
            return normaliseNumber(schema, number);
        }
        return value;
    }

    private static Object normaliseNumber(Map<String, Object> schema, Number number) {
        final BigDecimal decimal = decimalOf(number);
        if (decimal == null) {
            return number;
        }
        final List<?> allowed = schema.get(KEY_ENUM) instanceof List<?> list ? list : List.of();
        for (Object candidate : allowed) {
            if (candidate instanceof Number allowedNumber) {
                final BigDecimal allowedDecimal = decimalOf(allowedNumber);
                if (allowedDecimal != null && allowedDecimal.compareTo(decimal) == 0) {
                    return candidate;
                }
            }
        }
        if (typesOf(schema).contains(TYPE_INTEGER) && decimal.stripTrailingZeros().scale() <= 0) {
            final BigInteger whole = decimal.toBigIntegerExact();
            if (decimal.compareTo(LONG_MIN) < 0 || decimal.compareTo(LONG_MAX) > 0) {
                return whole;
            }
            final long asLong = whole.longValue();
            return asLong == (int) asLong ? (Object) (int) asLong : (Object) asLong;
        }
        return number;
    }

    /** The exact value of a number, or null for one that has none (NaN, infinity, an unknown Number type's oddity). */
    private static BigDecimal decimalOf(Number number) {
        try {
            if (number instanceof BigDecimal decimal) {
                return decimal;
            }
            if (number instanceof BigInteger integer) {
                return new BigDecimal(integer);
            }
            if (number instanceof Double || number instanceof Float) {
                final double raw = number.doubleValue();
                return Double.isFinite(raw) ? BigDecimal.valueOf(raw) : null;
            }
            return BigDecimal.valueOf(number.longValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean usesComposition(Map<String, Object> schema) {
        return schema.keySet().stream().anyMatch(COMPOSITION_KEYWORDS::contains);
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
            this.payload = payload == null ? null : Collections.unmodifiableMap(payload);
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
