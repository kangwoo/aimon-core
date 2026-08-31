package at.aimon.core.agent.tool.generic;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
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

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.schema.ViolationMessages;

/**
 * Turns a {@code ToolInput} map into the typed record a {@link GenericTool} declares, or into the sentences explaining
 * why it could not.
 *
 * <p>
 * This is the half of {@code GenericTool} that replaces the {@code getRequiredString} / {@code getInteger} block at the
 * top of a hand-written {@code execute()}. The gain is not brevity: it is that the checks now come from the same
 * declaration the schema came from, so a parameter cannot be advertised and then read under a different name, or
 * declared required and then defaulted.
 *
 * <h2>Every violation, not the first</h2>
 *
 * <p>
 * A hand-written extraction fails on the first bad parameter because it has no way not to — {@code getRequiredString}
 * throws. Binding sees all the parameters at once, so it reports all the mismatches at once. A model that supplied two
 * wrong arguments then fixes both in one retry instead of discovering the second only after fixing the first.
 *
 * <h2>Relationship to the executor's schema gate</h2>
 *
 * <p>
 * The two overlap, deliberately. {@code DefaultToolExecutor}'s gate covers every tool including MCP ones, but only on
 * the path through the executor; binding covers only migrated tools, but on every path — and six in-tree call sites
 * reach a tool without going through the executor. Neither subsumes the other, and both speak
 * {@link ViolationMessages}, so the overlap costs a model nothing: the second check on the same call produces the same
 * words as the first.
 *
 * <p>
 * Stateless after construction and thread-safe. One instance per tool, built in the tool's constructor.
 *
 * @param <I>
 *            the tool's input record type
 * @see GenericTool
 * @see BindResult
 */
public final class ToolInputBinder<I> {

    /**
     * Creates a binder for a tool's input record.
     *
     * @param inputType
     *            the record type holding the tool's parameters (must not be null)
     * @param <I>
     *            the input record type
     * @return a binder for it (never null)
     * @throws IllegalArgumentException
     *             if the type is not a record
     */
    public static <I> ToolInputBinder<I> forType(Class<I> inputType) {
        Objects.requireNonNull(inputType, "Input type cannot be null");
        if (!inputType.isRecord()) {
            throw new IllegalArgumentException(
                    "Tool input type must be a record, but " + inputType.getName() + " is not");
        }
        return new ToolInputBinder<>(inputType);
    }

    private final Class<I> inputType;

    private ToolInputBinder(Class<I> inputType) {
        this.inputType = inputType;
    }

    /**
     * Binds the supplied parameters to the input record.
     *
     * @param input
     *            the tool call's parameters (must not be null)
     * @return the constructed record, or the violations that stopped it (never null)
     */
    public BindResult<I> bind(ToolInput input) {
        Objects.requireNonNull(input, "Input cannot be null");
        final List<String> violations = new ArrayList<>();
        final Object value = bindRecord(inputType, input.toMap(), "", violations);
        if (!violations.isEmpty()) {
            return BindResult.violations(violations);
        }
        return BindResult.bound(inputType.cast(value));
    }

    /**
     * Constructs one record from a map of values, collecting every mismatch rather than stopping at the first.
     *
     * @param recordType
     *            the record to construct
     * @param values
     *            the supplied values, keyed by wire name
     * @param prefix
     *            what to prepend to a parameter name in a violation — empty at the top level, {@code "parent."} inside
     *            a nested record
     * @param violations
     *            the collector every failure appends to
     * @return the constructed record, or null when anything was appended to violations
     */
    private static Object bindRecord(Class<?> recordType, Map<String, Object> values, String prefix,
            List<String> violations) {
        final RecordComponent[] components = recordType.getRecordComponents();
        final int before = violations.size();
        final Object[] arguments = new Object[components.length];
        final Set<String> declared = new LinkedHashSet<>();

        for (int i = 0; i < components.length; i++) {
            final RecordComponent component = components[i];
            final String name = ToolSchemaGenerator.wireName(component);
            final String reported = prefix + name;
            declared.add(name);

            final Object raw = values.get(name);
            if (raw == null) {
                if (ToolSchemaGenerator.isRequired(component)) {
                    violations.add(ViolationMessages.missingRequired(reported,
                            ToolSchemaGenerator.jsonTypeName(component.getGenericType(), reported)));
                }
                arguments[i] = absentValue(component.getType());
                continue;
            }
            arguments[i] = convert(component.getGenericType(), raw, reported, violations);
            checkAllowed(component, raw, reported, violations);
        }

        for (String supplied : values.keySet()) {
            if (!declared.contains(supplied)) {
                // Both sides carry the prefix, or neither does. Comparing a reported "parent.file_paht" against a bare
                // "file_path" puts every candidate past the edit-distance cut-off, which silently turned the typo
                // suggestion off for every nested record — the one place a path is most useful to hand back.
                violations.add(ViolationMessages.unknownParameter(prefix + supplied,
                        declared.stream().map(declaredName -> prefix + declaredName).toList()));
            }
        }

        if (violations.size() != before) {
            return null;
        }
        try {
            return instantiate(recordType, components, arguments);
        } catch (IllegalArgumentException e) {
            // A compact constructor rejected the combination. That rule is the tool's own, so its wording is the
            // useful one; only the closing sentence is added, to say the same thing every other violation says.
            violations.add(e.getMessage() + ' ' + ViolationMessages.NOT_EXECUTED);
            return null;
        }
    }

    /**
     * Enforces a {@link ToolParam#allowed()} set, so a call that skipped the executor's gate is still held to the
     * constraint the schema advertised.
     *
     * <p>
     * Only a declared set needs this. A component typed as a Java {@code enum} carries the same constraint in its
     * type, and {@link #convert} already reports a value outside it — checking here as well would say it twice.
     */
    private static void checkAllowed(RecordComponent component, Object raw, String reported, List<String> violations) {
        final ToolParam meta = component.getAnnotation(ToolParam.class);
        if (meta == null || meta.allowed().length == 0 || !(raw instanceof String supplied)) {
            return;
        }
        final List<String> allowed = List.of(meta.allowed());
        if (!allowed.contains(supplied)) {
            violations.add(ViolationMessages.notInEnum(reported, allowed, supplied));
        }
    }

    /**
     * Converts one supplied value to the declared type, appending a violation and returning null on a mismatch.
     */
    private static Object convert(Type type, Object raw, String name, List<String> violations) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == Optional.class) {
            final Object inner = convert(parameterized.getActualTypeArguments()[0], raw, name, violations);
            return inner == null ? null : Optional.of(inner);
        }
        final Class<?> target = rawTypeOf(type);

        if (raw == null) {
            // A null element inside a supplied array — the record's own components never arrive null, since ToolInput
            // drops null values and bindRecord reads a missing one as absent. Reported as a type mismatch because
            // that is what it is: the declaration says this position holds a value.
            return mismatch(name, ToolSchemaGenerator.jsonTypeName(type, name), violations);
        }
        if (target == String.class) {
            return raw instanceof String value ? value : mismatch(name, "string", violations);
        }
        if (target == char.class || target == Character.class) {
            // The generator advertises a char as "string", so the binder has to accept one. Length is checked here
            // rather than left to truncation: taking the first character of "abc" would be the tool acting on a value
            // the caller did not send.
            return raw instanceof String value && value.length() == 1
                    ? (Object) value.charAt(0)
                    : mismatch(name, "string of exactly one character", violations);
        }
        if (target.isEnum()) {
            return toEnum(target, raw, name, violations);
        }
        if (isIntegerType(target)) {
            return toIntegral(target, raw, name, violations);
        }
        if (isNumberType(target)) {
            return toDecimal(target, raw, name, violations);
        }
        if (target == boolean.class || target == Boolean.class) {
            return raw instanceof Boolean value ? value : mismatch(name, "boolean", violations);
        }
        if (target.isArray() || Collection.class.isAssignableFrom(target)) {
            return toCollection(type, target, raw, name, violations);
        }
        if (target.isRecord()) {
            return raw instanceof Map<?, ?> map
                    ? bindRecord(target, stringKeyed(map), name + '.', violations)
                    : mismatch(name, "object", violations);
        }
        if (Map.class.isAssignableFrom(target)) {
            return raw instanceof Map<?, ?> map
                    ? Collections.unmodifiableMap(stringKeyed(map))
                    : mismatch(name, "object", violations);
        }
        // Unreachable: ToolSchemaGenerator rejected this type when the tool was constructed.
        return mismatch(name, "string", violations);
    }

    private static Object toEnum(Class<?> target, Object raw, String name, List<String> violations) {
        final List<String> constants = Arrays.stream(target.getEnumConstants())
                .map(constant -> ((Enum<?>) constant).name()).toList();
        if (!(raw instanceof String supplied)) {
            return mismatch(name, "string", violations);
        }
        for (Object constant : target.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(supplied)) {
                return constant;
            }
        }
        violations.add(ViolationMessages.notInEnum(name, constants, supplied));
        return null;
    }

    /**
     * Converts a whole number, accepting the {@code 3.0} that a JSON parser hands back for a {@code 3}.
     *
     * <p>
     * The same reading as the executor's gate, and for the same reason: a model has no control over which of the two
     * its transport produced, so rejecting one of them would be a distinction it could not act on.
     */
    private static Object toIntegral(Class<?> target, Object raw, String name, List<String> violations) {
        if (!(raw instanceof Number number) || !isWholeNumber(number)) {
            return mismatch(name, "integer", violations);
        }
        // Widened before it is narrowed. Reading through longValue() first would have made the two widest targets the
        // only ones without a range check: a value past Long.MAX_VALUE saturates on the way in, and the truncated
        // number then passes every test the real one would have failed.
        final BigInteger value = toBigInteger(number);
        if (target == BigInteger.class) {
            return value;
        }
        if (target == long.class || target == Long.class) {
            return withinRange(value, Long.MIN_VALUE, Long.MAX_VALUE)
                    ? (Object) value.longValue()
                    : mismatch(name, "integer", violations);
        }
        if (target == int.class || target == Integer.class) {
            return withinRange(value, Integer.MIN_VALUE, Integer.MAX_VALUE)
                    ? (Object) value.intValue()
                    : mismatch(name, "integer", violations);
        }
        if (target == short.class || target == Short.class) {
            return withinRange(value, Short.MIN_VALUE, Short.MAX_VALUE)
                    ? (Object) value.shortValue()
                    : mismatch(name, "integer", violations);
        }
        return withinRange(value, Byte.MIN_VALUE, Byte.MAX_VALUE)
                ? (Object) value.byteValue()
                : mismatch(name, "integer", violations);
    }

    /**
     * Widens a whole number to {@code BigInteger} without losing anything on the way.
     *
     * <p>
     * Only reached after {@link #isWholeNumber} has agreed there is no fractional part, so the exact conversions here
     * cannot throw — the {@code double} branch covers the {@code 3.0} a JSON parser produces for a {@code 3}.
     */
    private static BigInteger toBigInteger(Number number) {
        if (number instanceof BigInteger value) {
            return value;
        }
        if (number instanceof BigDecimal value) {
            return value.toBigInteger();
        }
        if (number instanceof Double || number instanceof Float) {
            return BigDecimal.valueOf(number.doubleValue()).toBigInteger();
        }
        return BigInteger.valueOf(number.longValue());
    }

    private static Object toDecimal(Class<?> target, Object raw, String name, List<String> violations) {
        if (!(raw instanceof Number number)) {
            return mismatch(name, "number", violations);
        }
        if (target == BigDecimal.class) {
            return new BigDecimal(number.toString());
        }
        if (target == float.class || target == Float.class) {
            return number.floatValue();
        }
        return number.doubleValue();
    }

    /**
     * Converts a repeated parameter, converting each element under the same rules and naming the position of any that
     * fails.
     */
    private static Object toCollection(Type type, Class<?> target, Object raw, String name, List<String> violations) {
        final List<?> supplied;
        if (raw instanceof Collection<?> collection) {
            supplied = new ArrayList<>(collection);
        } else if (raw.getClass().isArray()) {
            supplied = arrayToList(raw);
        } else {
            return mismatch(name, "array", violations);
        }

        final Type elementType = elementTypeOf(type);
        final List<Object> converted = new ArrayList<>(supplied.size());
        boolean clean = true;
        for (int i = 0; i < supplied.size(); i++) {
            final Object element = convert(elementType, supplied.get(i), name + '[' + i + ']', violations);
            if (element == null) {
                clean = false;
            } else {
                converted.add(element);
            }
        }
        if (!clean) {
            return null;
        }
        if (target.isArray()) {
            final Object array = Array.newInstance(target.getComponentType(), converted.size());
            for (int i = 0; i < converted.size(); i++) {
                Array.set(array, i, converted.get(i));
            }
            return array;
        }
        if (Set.class.isAssignableFrom(target)) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(converted));
        }
        return List.copyOf(converted);
    }

    /**
     * Returns what a component holds when the caller did not supply it.
     *
     * <p>
     * An {@code Optional} gets {@code Optional.empty()} — the whole reason to declare one. A primitive gets its zero,
     * which is never observed: a missing primitive already produced a violation, so the record is discarded before it
     * is constructed. Everything else gets null, which is how a tool tells "not supplied" from "supplied".
     */
    private static Object absentValue(Class<?> target) {
        if (target == Optional.class) {
            return Optional.empty();
        }
        if (!target.isPrimitive()) {
            return null;
        }
        if (target == boolean.class) {
            return false;
        }
        if (target == int.class) {
            return 0;
        }
        if (target == long.class) {
            return 0L;
        }
        if (target == double.class) {
            return 0d;
        }
        if (target == float.class) {
            return 0f;
        }
        if (target == short.class) {
            return (short) 0;
        }
        if (target == byte.class) {
            return (byte) 0;
        }
        return '\0';
    }

    private static Object instantiate(Class<?> recordType, RecordComponent[] components, Object[] arguments) {
        final Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            parameterTypes[i] = components[i].getType();
        }
        try {
            final Constructor<?> canonical = recordType.getDeclaredConstructor(parameterTypes);
            canonical.setAccessible(true);
            return canonical.newInstance(arguments);
        } catch (InvocationTargetException e) {
            // A compact constructor rejected the combination. The tool declared that rule, so its message is the
            // useful one; GenericTool turns it into an error result.
            throw new IllegalArgumentException(messageOf(e.getCause()), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot construct tool input record " + recordType.getName(), e);
        }
    }

    private static String messageOf(Throwable cause) {
        return cause == null || cause.getMessage() == null ? "Invalid parameter combination" : cause.getMessage();
    }

    private static Object mismatch(String name, String expectedType, List<String> violations) {
        violations.add(ViolationMessages.typeMismatch(name, expectedType));
        return null;
    }

    private static boolean isWholeNumber(Number number) {
        if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte
                || number instanceof BigInteger) {
            return true;
        }
        if (number instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        final double raw = number.doubleValue();
        return Double.isFinite(raw) && raw == Math.rint(raw);
    }

    private static boolean withinRange(BigInteger value, long min, long max) {
        return value.compareTo(BigInteger.valueOf(min)) >= 0 && value.compareTo(BigInteger.valueOf(max)) <= 0;
    }

    private static boolean isIntegerType(Class<?> target) {
        return target == int.class || target == Integer.class || target == long.class || target == Long.class
                || target == short.class || target == Short.class || target == byte.class || target == Byte.class
                || target == BigInteger.class;
    }

    private static boolean isNumberType(Class<?> target) {
        return target == double.class || target == Double.class || target == float.class || target == Float.class
                || target == BigDecimal.class;
    }

    private static Class<?> rawTypeOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return Object.class;
    }

    private static Type elementTypeOf(Type type) {
        if (type instanceof java.lang.reflect.GenericArrayType arrayType) {
            return arrayType.getGenericComponentType();
        }
        if (type instanceof Class<?> clazz && clazz.isArray()) {
            return clazz.getComponentType();
        }
        if (type instanceof ParameterizedType parameterized) {
            final Type argument = parameterized.getActualTypeArguments()[0];
            if (argument instanceof java.lang.reflect.WildcardType wildcard && wildcard.getUpperBounds().length == 1) {
                return wildcard.getUpperBounds()[0];
            }
            return argument;
        }
        return Object.class;
    }

    private static List<Object> arrayToList(Object array) {
        final int length = Array.getLength(array);
        final List<Object> elements = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            elements.add(Array.get(array, i));
        }
        return elements;
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> map) {
        final Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }
}
