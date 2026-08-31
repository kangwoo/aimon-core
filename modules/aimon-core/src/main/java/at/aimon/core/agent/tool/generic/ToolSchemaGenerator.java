package at.aimon.core.agent.tool.generic;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Derives a tool's JSON Schema from the record it binds its input to.
 *
 * <p>
 * The schema and the code that consumes it are the same declaration read twice, so they cannot drift apart the way a
 * hand-written {@code createInputSchema()} and a hand-written parameter extraction can. Adding a component adds a
 * property; deleting one deletes a property; renaming a type changes a type.
 *
 * <h2>What is derived and what is not</h2>
 *
 * <table border="1">
 * <caption>Sources</caption>
 * <tr>
 * <th>Schema key</th>
 * <th>Comes from</th>
 * </tr>
 * <tr>
 * <td>{@code type}, {@code items}, nested {@code properties}</td>
 * <td>the component's type</td>
 * </tr>
 * <tr>
 * <td>property name</td>
 * <td>{@link ToolParam#name()}, or the component name when it is not declared — never a case conversion</td>
 * </tr>
 * <tr>
 * <td>{@code required}</td>
 * <td>{@link ToolParam#required()}, plus every primitive component</td>
 * </tr>
 * <tr>
 * <td>{@code description}, {@code enum}</td>
 * <td>{@link ToolParam}, except that a Java {@code enum} component supplies its own constants</td>
 * </tr>
 * <tr>
 * <td>{@code additionalProperties: false}</td>
 * <td>always, for the input record and every nested record</td>
 * </tr>
 * </table>
 *
 * <p>
 * {@code additionalProperties: false} is not optional here, and that is the point of binding: a record has exactly the
 * components it has, so an undeclared key is a mistake with no reading under which the tool could honour it. Schemas
 * we do not own (MCP) keep JSON Schema's permissive default; schemas we generate do not.
 *
 * <h2>Type mapping</h2>
 *
 * <table border="1">
 * <caption>Java to JSON Schema</caption>
 * <tr>
 * <th>Java</th>
 * <th>JSON Schema</th>
 * </tr>
 * <tr>
 * <td>{@code String}, {@code enum}</td>
 * <td>{@code string} (an {@code enum} also contributes its constants)</td>
 * </tr>
 * <tr>
 * <td>{@code int}, {@code long}, {@code Integer}, {@code Long}, {@code BigInteger}</td>
 * <td>{@code integer}</td>
 * </tr>
 * <tr>
 * <td>{@code double}, {@code float}, {@code Double}, {@code Float}, {@code BigDecimal}</td>
 * <td>{@code number}</td>
 * </tr>
 * <tr>
 * <td>{@code boolean}, {@code Boolean}</td>
 * <td>{@code boolean}</td>
 * </tr>
 * <tr>
 * <td>{@code List<T>}, {@code Set<T>}, {@code T[]}</td>
 * <td>{@code array} with a derived {@code items}</td>
 * </tr>
 * <tr>
 * <td>{@code Map<String, ?>}</td>
 * <td>{@code object} with no declared properties — the one open door, for genuinely free-form input</td>
 * </tr>
 * <tr>
 * <td>a nested {@code record}</td>
 * <td>{@code object}, derived the same way</td>
 * </tr>
 * <tr>
 * <td>{@code Optional<T>}</td>
 * <td>T's mapping, and never required</td>
 * </tr>
 * </table>
 *
 * <p>
 * Anything else is rejected when the tool is constructed rather than mapped to a guess. A type this class cannot name
 * is one the model cannot be told about either, so failing at construction — in a test, at startup — is strictly
 * better than shipping a tool whose schema quietly omits a parameter.
 *
 * <p>
 * Stateless and thread-safe. Generation happens once per tool instance, in its constructor.
 *
 * @see ToolParam
 * @see GenericTool
 */
public final class ToolSchemaGenerator {

    private static final String TYPE = "type";
    private static final String TYPE_OBJECT = "object";
    private static final String TYPE_ARRAY = "array";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_NUMBER = "number";
    private static final String TYPE_BOOLEAN = "boolean";

    private static final Set<Class<?>> INTEGER_TYPES = Set.of(int.class, long.class, short.class, byte.class,
            Integer.class, Long.class, Short.class, Byte.class, BigInteger.class);
    private static final Set<Class<?>> NUMBER_TYPES = Set.of(double.class, float.class, Double.class, Float.class,
            BigDecimal.class);
    private static final Set<Class<?>> BOOLEAN_TYPES = Set.of(boolean.class, Boolean.class);

    private ToolSchemaGenerator() {
        throw new UnsupportedOperationException("Utility class must not be instantiated");
    }

    /**
     * Derives the input schema for a tool that binds its parameters to the given record.
     *
     * @param inputType
     *            the record type holding the tool's parameters (must not be null)
     * @return the JSON Schema, as the {@code Map} shape the rest of the tool layer speaks (never null)
     * @throws IllegalArgumentException
     *             if the type is not a record, declares a component this class cannot map, or nests itself
     */
    public static Map<String, Object> generate(Class<?> inputType) {
        Objects.requireNonNull(inputType, "Input type cannot be null");
        if (!inputType.isRecord()) {
            throw new IllegalArgumentException(
                    "Tool input type must be a record, but " + inputType.getName() + " is not");
        }
        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, TYPE_OBJECT);
        schema.putAll(objectBody(inputType, new LinkedHashSet<>()));
        return Collections.unmodifiableMap(schema);
    }

    /**
     * Returns the name a component carries on the wire.
     *
     * <p>
     * The binder reads parameters by this name and the generator declares them under it, so both go through here
     * rather than each deciding for itself.
     *
     * @param component
     *            the record component (must not be null)
     * @return the declared {@link ToolParam#name()}, or the component's own name
     */
    static String wireName(RecordComponent component) {
        final ToolParam meta = component.getAnnotation(ToolParam.class);
        return meta == null || meta.name().isBlank() ? component.getName() : meta.name();
    }

    /**
     * Decides whether a component must be supplied.
     *
     * <p>
     * A primitive is required whatever the annotation says: it has no value that could stand for "absent", so binding
     * one that was not supplied would have to invent a zero and pass it off as the caller's intent. An
     * {@code Optional} is never required, for the mirror-image reason.
     *
     * @param component
     *            the record component (must not be null)
     * @return true if a call omitting this parameter should be rejected
     */
    static boolean isRequired(RecordComponent component) {
        if (component.getType() == Optional.class) {
            return false;
        }
        if (component.getType().isPrimitive()) {
            return true;
        }
        final ToolParam meta = component.getAnnotation(ToolParam.class);
        return meta != null && meta.required();
    }

    /**
     * Returns the JSON Schema type name a component is declared as.
     *
     * <p>
     * The binder quotes this in its violation sentences, so the name a model is told to supply is the same one the
     * schema showed it rather than a Java type name it never saw.
     *
     * @param type
     *            the declared component type, generics intact (must not be null)
     * @param path
     *            how to name this position if the type turns out to be unmappable
     * @return one of the JSON Schema type names
     */
    static String jsonTypeName(Type type, String path) {
        return jsonTypeOf(rawTypeOf(unwrapOptional(type, path), path), path);
    }

    /**
     * Builds the {@code properties} / {@code required} / {@code additionalProperties} part shared by the input record
     * and every record nested inside it.
     */
    private static Map<String, Object> objectBody(Class<?> recordType, Set<Class<?>> enclosing) {
        if (!enclosing.add(recordType)) {
            throw new IllegalArgumentException(
                    "Tool input type " + recordType.getName() + " nests itself; a schema for it would not terminate");
        }
        try {
            final Map<String, Object> properties = new LinkedHashMap<>();
            final List<String> required = new ArrayList<>();
            for (RecordComponent component : recordType.getRecordComponents()) {
                final String name = wireName(component);
                properties.put(name, propertySchema(component.getGenericType(),
                        component.getAnnotation(ToolParam.class), qualify(recordType, name), enclosing));
                if (isRequired(component)) {
                    required.add(name);
                }
            }
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("properties", Collections.unmodifiableMap(properties));
            if (!required.isEmpty()) {
                body.put("required", List.copyOf(required));
            }
            body.put("additionalProperties", false);
            return body;
        } finally {
            enclosing.remove(recordType);
        }
    }

    /**
     * Builds the schema for one parameter, or for one element of a repeated parameter.
     *
     * @param type
     *            the declared type, generics intact
     * @param meta
     *            the parameter's annotation, or null for an element type (an element has no annotation of its own)
     * @param path
     *            how to name this position in an error message
     * @param enclosing
     *            the record types currently being derived, used to catch self-nesting
     */
    private static Map<String, Object> propertySchema(Type type, ToolParam meta, String path, Set<Class<?>> enclosing) {
        final Type unwrapped = unwrapOptional(type, path);
        final Class<?> raw = rawTypeOf(unwrapped, path);
        final String jsonType = jsonTypeOf(raw, path);

        final Map<String, Object> property = new LinkedHashMap<>();
        property.put(TYPE, jsonType);
        if (meta != null && !meta.description().isBlank()) {
            property.put("description", meta.description());
        }
        final List<String> allowed = allowedValues(raw, meta);
        if (!allowed.isEmpty()) {
            property.put("enum", allowed);
        }
        if (TYPE_ARRAY.equals(jsonType)) {
            property.put("items", propertySchema(elementTypeOf(unwrapped, path), null, path + "[]", enclosing));
        } else if (raw.isRecord()) {
            property.putAll(objectBody(raw, enclosing));
        }
        return Collections.unmodifiableMap(property);
    }

    /**
     * Returns the closed set of values a parameter accepts, whether it was declared or comes from an {@code enum}.
     */
    private static List<String> allowedValues(Class<?> raw, ToolParam meta) {
        if (meta != null && meta.allowed().length > 0) {
            return List.of(meta.allowed());
        }
        if (raw.isEnum()) {
            return Arrays.stream(raw.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
        }
        return List.of();
    }

    /**
     * Strips one layer of {@code Optional}, which affects requiredness but not the value's shape.
     */
    private static Type unwrapOptional(Type type, String path) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == Optional.class) {
            return typeArgument(parameterized, path);
        }
        return type;
    }

    private static Class<?> rawTypeOf(Type type, String path) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        throw new IllegalArgumentException(
                "Parameter '" + path + "' has type " + type + ", which cannot be mapped to a JSON Schema type");
    }

    private static String jsonTypeOf(Class<?> raw, String path) {
        if (raw == String.class || raw == Character.class || raw == char.class || raw.isEnum()) {
            return TYPE_STRING;
        }
        if (INTEGER_TYPES.contains(raw)) {
            return TYPE_INTEGER;
        }
        if (NUMBER_TYPES.contains(raw)) {
            return TYPE_NUMBER;
        }
        if (BOOLEAN_TYPES.contains(raw)) {
            return TYPE_BOOLEAN;
        }
        if (raw.isArray() || Collection.class.isAssignableFrom(raw)) {
            return TYPE_ARRAY;
        }
        if (Map.class.isAssignableFrom(raw) || raw.isRecord()) {
            return TYPE_OBJECT;
        }
        throw new IllegalArgumentException("Parameter '" + path + "' has type " + raw.getName()
                + ", which has no JSON Schema mapping. Use a supported type or a nested record.");
    }

    private static Type elementTypeOf(Type type, String path) {
        if (type instanceof GenericArrayType arrayType) {
            return arrayType.getGenericComponentType();
        }
        if (type instanceof Class<?> clazz && clazz.isArray()) {
            return clazz.getComponentType();
        }
        if (type instanceof ParameterizedType parameterized) {
            return typeArgument(parameterized, path);
        }
        throw new IllegalArgumentException(
                "Parameter '" + path + "' is a raw collection. Declare the element type, for example List<String>.");
    }

    /**
     * Reads the single type argument of a parameterized type, following a wildcard to its upper bound.
     */
    private static Type typeArgument(ParameterizedType type, String path) {
        final Type[] arguments = type.getActualTypeArguments();
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Parameter '" + path + "' has type " + type
                    + ", which takes more than one type argument and cannot be mapped");
        }
        final Type argument = arguments[0];
        if (argument instanceof WildcardType wildcard && wildcard.getUpperBounds().length == 1) {
            return wildcard.getUpperBounds()[0];
        }
        return argument;
    }

    private static String qualify(Class<?> recordType, String name) {
        return recordType.getSimpleName() + '.' + name;
    }
}
