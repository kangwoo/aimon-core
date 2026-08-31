package at.aimon.core.agent.tool.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import at.aimon.core.agent.tool.ToolInput;

/**
 * Checks the four mistakes a model actually makes: a missing parameter, a wrong type, a value outside an
 * {@code enum}, and a misspelled name.
 *
 * <p>
 * <b>Not a JSON Schema implementation, and not trying to become one.</b> The scope is drawn where the value stops:
 * these four are the mismatches that every tool would otherwise re-check by hand, in slightly different words each
 * time. Everything else is left alone, and the two boundaries below are the ones worth knowing.
 *
 * <h2>Ignorance never blocks a call</h2>
 *
 * <p>
 * Most schemas that pass through here were not written by us — an MCP server's schema arrives essentially as the
 * server sent it. If this class rejected what it could not parse, connecting one unfamiliar server would reject a
 * flood of perfectly good calls, and the cause would be nowhere near the symptom. So an unfamiliar construct is
 * <em>passed</em>, never failed:
 *
 * <table border="1">
 * <caption>Fallbacks</caption>
 * <tr>
 * <th>Schema</th>
 * <th>Effect</th>
 * </tr>
 * <tr>
 * <td>null, empty, or {@code type} is not {@code object}</td>
 * <td>no checking at all</td>
 * </tr>
 * <tr>
 * <td>no {@code properties}</td>
 * <td>{@code required} is still checked</td>
 * </tr>
 * <tr>
 * <td>a property using {@code $ref} / {@code oneOf} / {@code anyOf} / {@code allOf} / {@code not}</td>
 * <td>that property is skipped, its siblings are not</td>
 * </tr>
 * <tr>
 * <td>a property whose {@code type} is a name we do not know</td>
 * <td>that property is skipped</td>
 * </tr>
 * <tr>
 * <td>a property whose {@code type} is a union such as {@code ["string","null"]}</td>
 * <td>matching any listed type passes</td>
 * </tr>
 * </table>
 *
 * <h2>Shape here, ranges in the tool</h2>
 *
 * <p>
 * {@code minLength}, {@code minItems}, {@code minimum}, {@code maximum} and {@code default} are declared by several
 * in-tree schemas and are all ignored here. That is a decision, not an omission. Tools already handle their own
 * ranges, and they do not all handle them the same way — {@code BashTool} <em>clamps</em> an over-large timeout
 * rather than rejecting it, so enforcing its declared {@code maximum} would turn a call that quietly succeeds today
 * into an error. Two enforcement points would also leave no answer to which one is the truth. The line drawn is:
 * <b>shape here (is it present, is it the right type, is it an allowed value, is the name real), ranges in the
 * tool.</b>
 *
 * <h2>Nesting stops after one level</h2>
 *
 * <p>
 * A property declared {@code object} or {@code array} is checked for being one; what is inside it is not. Recursing
 * means meeting {@code $ref} and {@code oneOf} for real, and at that point this is a JSON Schema implementation.
 * Tools whose nested contract matters are better served by binding their input to a typed object.
 *
 * <p>
 * Stateless and thread-safe.
 */
public class DefaultToolInputSchemaValidator implements ToolInputSchemaValidator {

    private static final String KEY_TYPE = "type";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_REQUIRED = "required";
    private static final String KEY_ENUM = "enum";
    private static final String KEY_ADDITIONAL_PROPERTIES = "additionalProperties";

    private static final String TYPE_OBJECT = "object";

    /**
     * Keywords whose presence means the property's contract is not expressible by a single type check. Their
     * appearance disqualifies the property from checking rather than the whole schema — a sibling that is a plain
     * {@code "type": "string"} is still worth checking.
     */
    private static final Set<String> COMPOSITION_KEYWORDS = Set.of("$ref", "oneOf", "anyOf", "allOf", "not");

    /**
     * The type names this validator can decide. {@code null} is listed so that a union like
     * {@code ["string","null"]} is understood rather than skipped; no value ever matches it, because a JSON
     * {@code null} is dropped before validation and so arrives as an absent parameter.
     */
    private static final Set<String> KNOWN_TYPES = Set.of("string", "number", "integer", "boolean", "array",
            TYPE_OBJECT, "null");

    @Override
    public SchemaValidationResult validate(Map<String, Object> inputSchema, ToolInput input) {
        Objects.requireNonNull(input, "Input cannot be null");

        if (inputSchema == null || inputSchema.isEmpty() || !TYPE_OBJECT.equals(inputSchema.get(KEY_TYPE))) {
            return SchemaValidationResult.ok();
        }

        final Map<String, Object> properties = asMap(inputSchema.get(KEY_PROPERTIES));
        final List<String> violations = new ArrayList<>();

        checkRequired(inputSchema, properties, input, violations);
        if (properties != null) {
            checkDeclaredProperties(properties, input, violations);
            checkUnknownParameters(inputSchema, properties, input, violations);
        }

        return violations.isEmpty() ? SchemaValidationResult.ok() : SchemaValidationResult.violations(violations);
    }

    /**
     * Reports every entry of {@code required} the caller did not supply.
     *
     * <p>
     * Runs even when the schema declares no {@code properties} — {@code required} alone is still a promise the caller
     * can break, and it is the one check that needs nothing else to be understood.
     */
    private static void checkRequired(Map<String, Object> inputSchema, Map<String, Object> properties, ToolInput input,
            List<String> violations) {
        final List<Object> required = asList(inputSchema.get(KEY_REQUIRED));
        if (required == null) {
            return;
        }
        for (Object entry : required) {
            if (entry instanceof String name && !input.has(name)) {
                violations.add(ViolationMessages.missingRequired(name, declaredTypeName(properties, name)));
            }
        }
    }

    /**
     * Checks the type and {@code enum} of every declared property the caller actually supplied.
     *
     * <p>
     * A property that fails its type check is not also checked against its {@code enum}: a value of the wrong type is
     * necessarily outside the allowed set, and reporting both makes the model believe it made two mistakes.
     */
    private static void checkDeclaredProperties(Map<String, Object> properties, ToolInput input,
            List<String> violations) {
        for (Map.Entry<String, Object> declared : properties.entrySet()) {
            final String name = declared.getKey();
            if (!input.has(name)) {
                continue;
            }
            final Map<String, Object> propertySchema = asMap(declared.getValue());
            if (propertySchema == null || usesComposition(propertySchema)) {
                continue;
            }
            final List<String> types = declaredTypes(propertySchema.get(KEY_TYPE));
            if (types == null) {
                continue;
            }
            final Object value = input.get(name);
            if (!types.isEmpty() && types.stream().noneMatch(type -> matchesType(type, value))) {
                violations.add(ViolationMessages.typeMismatch(name, String.join(" or ", types)));
                continue;
            }
            final List<Object> allowed = asList(propertySchema.get(KEY_ENUM));
            if (allowed != null && !allowed.isEmpty()
                    && allowed.stream().noneMatch(candidate -> Objects.equals(candidate, value))) {
                violations.add(ViolationMessages.notInEnum(name, allowed, value));
            }
        }
    }

    /**
     * Reports supplied parameters the schema does not declare — but only for a schema that asked for it.
     *
     * <p>
     * JSON Schema's default is to allow undeclared keys, and that default is kept for anything that does not say
     * otherwise. Our own tools opt in with {@code additionalProperties: false}; a third-party schema that never heard
     * of us stays permissive. That is what lets one gate serve both without an "is this ours?" list, which could not
     * be written honestly anyway — built-in tools live in more than one module, and an MCP tool is wrapped by a class
     * of ours.
     */
    private static void checkUnknownParameters(Map<String, Object> inputSchema, Map<String, Object> properties,
            ToolInput input, List<String> violations) {
        if (!Boolean.FALSE.equals(inputSchema.get(KEY_ADDITIONAL_PROPERTIES))) {
            return;
        }
        for (String supplied : input.keys()) {
            if (!properties.containsKey(supplied)) {
                violations.add(ViolationMessages.unknownParameter(supplied, properties.keySet()));
            }
        }
    }

    /**
     * Renders a declared property's type for the "is required" message, or null when there is nothing sensible to
     * say.
     */
    private static String declaredTypeName(Map<String, Object> properties, String name) {
        if (properties == null) {
            return null;
        }
        final Map<String, Object> propertySchema = asMap(properties.get(name));
        if (propertySchema == null) {
            return null;
        }
        final List<String> types = declaredTypes(propertySchema.get(KEY_TYPE));
        return types == null || types.isEmpty() ? null : String.join(" or ", types);
    }

    /**
     * Extracts the declared type names.
     *
     * @param declared
     *            the raw {@code type} value — a string, a list of strings, or something else entirely
     * @return the names to check against; empty when no type is declared; <b>null when the declaration was not
     *         understood</b>, which the caller must read as "skip this property" rather than as "no type"
     */
    private static List<String> declaredTypes(Object declared) {
        if (declared == null) {
            return List.of();
        }
        if (declared instanceof String name) {
            return KNOWN_TYPES.contains(name) ? List.of(name) : null;
        }
        final List<Object> union = asList(declared);
        if (union == null || union.isEmpty()) {
            return null;
        }
        final List<String> names = new ArrayList<>(union.size());
        for (Object member : union) {
            if (!(member instanceof String name) || !KNOWN_TYPES.contains(name)) {
                return null;
            }
            names.add(name);
        }
        return names;
    }

    /**
     * Decides whether a value satisfies one JSON Schema type name.
     */
    private static boolean matchesType(String type, Object value) {
        switch (type) {
            case "string" :
                return value instanceof String;
            case "number" :
                return value instanceof Number;
            case "integer" :
                return isIntegral(value);
            case "boolean" :
                return value instanceof Boolean;
            case "array" :
                return value instanceof Collection || (value != null && value.getClass().isArray());
            case TYPE_OBJECT :
                return value instanceof Map;
            default :
                // "null" — unreachable in practice: a null value is dropped before it reaches a ToolInput, so the
                // parameter would be absent rather than present-and-null.
                return false;
        }
    }

    /**
     * Decides whether a number is a whole one.
     *
     * <p>
     * A JSON {@code 3} parses to an {@code Integer} and a JSON {@code 3.0} to a {@code Double}, but both denote the
     * same integer, and rejecting the second would be a distinction the model has no way to control. What is rejected
     * is a genuine fractional part.
     */
    private static boolean isIntegral(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        if (value instanceof Number number) {
            final double raw = number.doubleValue();
            return Double.isFinite(raw) && raw == Math.rint(raw);
        }
        return false;
    }

    private static boolean usesComposition(Map<String, Object> propertySchema) {
        return propertySchema.keySet().stream().anyMatch(COMPOSITION_KEYWORDS::contains);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }
}
