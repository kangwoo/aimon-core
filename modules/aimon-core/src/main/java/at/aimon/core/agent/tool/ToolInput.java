package at.aimon.core.agent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import at.aimon.core.base.NullSafeMaps;

/**
 * Type-safe wrapper for tool input parameters.
 *
 * <p>
 * This class provides validated, type-safe access to parameters passed from the LLM to tools. It wraps the raw input
 * map and provides convenient methods for extracting parameters with proper type checking and error handling.
 *
 * <h2>Design Principles</h2>
 *
 * <p>
 * This class follows several key design principles:
 *
 * <ul>
 * <li><b>Immutability:</b> Once created, the input cannot be modified (Value Object pattern)
 * <li><b>Type Safety:</b> Provides type-safe accessors instead of raw Object casting
 * <li><b>Null Safety:</b> Clear distinction between required and optional parameters
 * <li><b>Fail Fast:</b> Throws clear exceptions for missing or invalid parameters
 * </ul>
 *
 * <h2>JSON {@code null} Means Absent</h2>
 *
 * <p>
 * Every factory drops entries whose value is {@code null} ({@link at.aimon.core.base.NullSafeMaps}), so a key the
 * model sent as {@code null} behaves exactly like a key it never sent. Concretely, that makes the accessors agree
 * with each other for such a key: {@link #has(String)} is {@code false}, {@code getXxx(key, default)} returns the
 * default, {@code getXxxOrNull(key)} returns {@code null}, and {@code getRequiredXxx(key)} reports it as missing
 * rather than as the wrong type.
 *
 * <p>
 * A tool therefore cannot tell an explicitly-null parameter from an omitted one. No tool needs to today; one that
 * did would have to be handed the raw map alongside this wrapper.
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Required Parameters</h3>
 *
 * <p>
 * Use {@code getRequiredXxx()} methods for parameters that must be present:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolInput input = ToolInput.of(Map.of("file_path", "/path/to/file"));
 *     String filePath = input.getRequiredString("file_path"); // "/path/to/file"
 *     String missing = input.getRequiredString("missing"); // throws IllegalArgumentException
 * }
 * </pre>
 *
 * <h3>Optional Parameters with Defaults</h3>
 *
 * <p>
 * Use {@code getXxx(key, defaultValue)} methods for optional parameters:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolInput input = ToolInput.of(Map.of("timeout", 5000));
 *     int timeout = input.getInteger("timeout", 2000); // 5000
 *     int retries = input.getInteger("retries", 3); // 3 (default)
 * }
 * </pre>
 *
 * <h3>Optional Parameters (Nullable)</h3>
 *
 * <p>
 * Use {@code getXxxOrNull()} methods when null is a valid value:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolInput input = ToolInput.of(Map.of("description", "test"));
 *     String desc = input.getStringOrNull("description"); // "test"
 *     String missing = input.getStringOrNull("missing"); // null
 * }
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * This class is immutable and thread-safe. Multiple threads can safely share the same ToolInput instance.
 *
 * @see Tool
 * @see ToolContext
 */
public final class ToolInput {
    private final Map<String, Object> data;

    /**
     * Creates a new ToolInput with the given data.
     *
     * <p>
     * The data map is copied to ensure immutability. Changes to the original map will not affect this ToolInput.
     * {@code null}-valued entries are dropped by the copy — see the class javadoc.
     *
     * @param data
     *            the input parameter map (must not be null; its values may be)
     * @throws NullPointerException
     *             if data is null
     */
    private ToolInput(Map<String, Object> data) {
        // The sole normalization point: every factory below routes through here, so no path can produce a ToolInput
        // holding a null value. Tools upstream of this one also normalize (ToolUse), but this is the line that makes
        // the guarantee for anyone calling ToolInput.of(...) directly.
        this.data = NullSafeMaps.withoutNullValues(Objects.requireNonNull(data, "Input data cannot be null"));
    }

    /**
     * Creates a new ToolInput from the given data map.
     *
     * <p>
     * The data map is copied to ensure immutability, dropping any {@code null}-valued entries.
     *
     * @param data
     *            the input parameter map (must not be null; its values may be)
     * @return a new ToolInput instance (never null)
     * @throws NullPointerException
     *             if data is null
     */
    public static ToolInput of(Map<String, Object> data) {
        return new ToolInput(data);
    }

    /**
     * Creates a new ToolInput with no parameters.
     *
     * @return an empty ToolInput instance (never null)
     */
    public static ToolInput of() {
        return new ToolInput(Map.of());
    }

    /**
     * Creates a new ToolInput with one parameter. A {@code null} value is dropped, not rejected.
     */
    public static ToolInput of(String k1, Object v1) {
        return new ToolInput(pairs(k1, v1));
    }

    /**
     * Creates a new ToolInput with two parameters. {@code null} values are dropped, not rejected.
     */
    public static ToolInput of(String k1, Object v1, String k2, Object v2) {
        return new ToolInput(pairs(k1, v1, k2, v2));
    }

    /**
     * Creates a new ToolInput with three parameters. {@code null} values are dropped, not rejected.
     */
    public static ToolInput of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        return new ToolInput(pairs(k1, v1, k2, v2, k3, v3));
    }

    /**
     * Creates a new ToolInput with four parameters. {@code null} values are dropped, not rejected.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ToolInput of(String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4) {
        return new ToolInput(pairs(k1, v1, k2, v2, k3, v3, k4, v4));
    }

    /**
     * Creates a new ToolInput with five parameters. {@code null} values are dropped, not rejected.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ToolInput of(String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4,
            String k5, Object v5) {
        return new ToolInput(pairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5));
    }

    /**
     * Creates a new ToolInput with six parameters. {@code null} values are dropped, not rejected.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ToolInput of(String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4,
            String k5, Object v5, String k6, Object v6) {
        return new ToolInput(pairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6));
    }

    /**
     * Creates a new ToolInput with seven parameters. {@code null} values are dropped, not rejected.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ToolInput of(String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4,
            String k5, Object v5, String k6, Object v6, String k7, Object v7) {
        return new ToolInput(pairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7));
    }

    /**
     * Creates a new ToolInput with eight parameters. {@code null} values are dropped, not rejected.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ToolInput of(String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4,
            String k5, Object v5, String k6, Object v6, String k7, Object v7, String k8, Object v8) {
        return new ToolInput(pairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8));
    }

    /**
     * Creates a new ToolInput with nine parameters. {@code null} values are dropped, not rejected.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ToolInput of(String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4,
            String k5, Object v5, String k6, Object v6, String k7, Object v7, String k8, Object v8, String k9,
            Object v9) {
        return new ToolInput(pairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9));
    }

    /**
     * Creates a new ToolInput with ten parameters. {@code null} values are dropped, not rejected.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ToolInput of(String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4,
            String k5, Object v5, String k6, Object v6, String k7, Object v7, String k8, Object v8, String k9,
            Object v9, String k10, Object v10) {
        return new ToolInput(pairs(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10));
    }

    /**
     * Assembles the alternating key/value arguments of the {@code of(k, v, ...)} overloads into a mutable map.
     *
     * <p>
     * These overloads used to call {@code Map.of(...)}, which throws on a {@code null} value — before the constructor
     * runs, so its normalization never got a turn and {@code ToolInput.of("offset", null)} still blew up. The values
     * are passed through untouched here; the constructor is the one place that filters.
     *
     * <p>
     * Null keys and duplicate keys stay rejected. Only the {@code null}-value behaviour is meant to change, and a
     * hand-built map would otherwise have quietly accepted both.
     *
     * @param keysAndValues
     *            alternating keys and values; the arity is fixed by the calling overload's signature
     * @return a mutable, order-preserving map (handed straight to the constructor, never escapes)
     */
    private static Map<String, Object> pairs(Object... keysAndValues) {
        final Map<String, Object> assembled = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            final String key = Objects.requireNonNull((String) keysAndValues[i], "Key cannot be null");
            if (assembled.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate parameter: " + key);
            }
            assembled.put(key, keysAndValues[i + 1]);
        }
        return assembled;
    }

    // ==================== Required Parameters ====================

    /**
     * Gets a required string parameter.
     *
     * @param key
     *            the parameter name (must not be null)
     * @return the parameter value (never null)
     * @throws IllegalArgumentException
     *             if the parameter is missing or not a string
     * @throws NullPointerException
     *             if key is null
     */
    public String getRequiredString(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        Object value = data.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    "Parameter '" + key + "' must be a string, but was: " + value.getClass().getSimpleName());
        }
        return (String) value;
    }

    /**
     * Gets a required integer parameter.
     *
     * @param key
     *            the parameter name (must not be null)
     * @return the parameter value (never null)
     * @throws IllegalArgumentException
     *             if the parameter is missing or not convertible to integer
     * @throws NullPointerException
     *             if key is null
     */
    public Integer getRequiredInteger(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        Object value = data.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return convertToInteger(key, value);
    }

    /**
     * Gets a required boolean parameter.
     *
     * @param key
     *            the parameter name (must not be null)
     * @return the parameter value (never null)
     * @throws IllegalArgumentException
     *             if the parameter is missing or not a boolean
     * @throws NullPointerException
     *             if key is null
     */
    public Boolean getRequiredBoolean(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        Object value = data.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                    "Parameter '" + key + "' must be a boolean, but was: " + value.getClass().getSimpleName());
        }
        return (Boolean) value;
    }

    // ==================== Optional Parameters with Defaults ====================

    /**
     * Gets an optional string parameter with a default value.
     *
     * @param key
     *            the parameter name (must not be null)
     * @param defaultValue
     *            the default value if parameter is missing (may be null)
     * @return the parameter value or defaultValue if missing
     * @throws IllegalArgumentException
     *             if the parameter exists but is not a string
     * @throws NullPointerException
     *             if key is null
     */
    public String getString(String key, String defaultValue) {
        Objects.requireNonNull(key, "Key cannot be null");
        Object value = data.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    "Parameter '" + key + "' must be a string, but was: " + value.getClass().getSimpleName());
        }
        return (String) value;
    }

    /**
     * Gets an optional integer parameter with a default value.
     *
     * @param key
     *            the parameter name (must not be null)
     * @param defaultValue
     *            the default value if parameter is missing (may be null)
     * @return the parameter value or defaultValue if missing
     * @throws IllegalArgumentException
     *             if the parameter exists but is not convertible to integer
     * @throws NullPointerException
     *             if key is null
     */
    public Integer getInteger(String key, Integer defaultValue) {
        Objects.requireNonNull(key, "Key cannot be null");
        Object value = data.get(key);
        if (value == null) {
            return defaultValue;
        }
        return convertToInteger(key, value);
    }

    /**
     * Gets an optional long parameter with a default value.
     *
     * @param key
     *            the parameter name (must not be null)
     * @param defaultValue
     *            the default value if parameter is missing (may be null)
     * @return the parameter value or defaultValue if missing
     * @throws IllegalArgumentException
     *             if the parameter exists but is not convertible to long
     * @throws NullPointerException
     *             if key is null
     */
    public Long getLong(String key, Long defaultValue) {
        Objects.requireNonNull(key, "Key cannot be null");
        Object value = data.get(key);
        if (value == null) {
            return defaultValue;
        }
        return convertToLong(key, value);
    }

    /**
     * Gets an optional boolean parameter with a default value.
     *
     * @param key
     *            the parameter name (must not be null)
     * @param defaultValue
     *            the default value if parameter is missing (may be null)
     * @return the parameter value or defaultValue if missing
     * @throws IllegalArgumentException
     *             if the parameter exists but is not a boolean
     * @throws NullPointerException
     *             if key is null
     */
    public Boolean getBoolean(String key, Boolean defaultValue) {
        Objects.requireNonNull(key, "Key cannot be null");
        Object value = data.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                    "Parameter '" + key + "' must be a boolean, but was: " + value.getClass().getSimpleName());
        }
        return (Boolean) value;
    }

    // ==================== Optional Parameters (Nullable) ====================

    /**
     * Gets an optional string parameter that may be null.
     *
     * @param key
     *            the parameter name (must not be null)
     * @return the parameter value or null if missing
     * @throws IllegalArgumentException
     *             if the parameter exists but is not a string
     * @throws NullPointerException
     *             if key is null
     */
    public String getStringOrNull(String key) {
        return getString(key, null);
    }

    /**
     * Gets an optional integer parameter that may be null.
     *
     * @param key
     *            the parameter name (must not be null)
     * @return the parameter value or null if missing
     * @throws IllegalArgumentException
     *             if the parameter exists but is not convertible to integer
     * @throws NullPointerException
     *             if key is null
     */
    public Integer getIntegerOrNull(String key) {
        return getInteger(key, null);
    }

    /**
     * Gets an optional long parameter that may be null.
     *
     * @param key
     *            the parameter name (must not be null)
     * @return the parameter value or null if missing
     * @throws IllegalArgumentException
     *             if the parameter exists but is not convertible to long
     * @throws NullPointerException
     *             if key is null
     */
    public Long getLongOrNull(String key) {
        return getLong(key, null);
    }

    /**
     * Gets an optional boolean parameter that may be null.
     *
     * @param key
     *            the parameter name (must not be null)
     * @return the parameter value or null if missing
     * @throws IllegalArgumentException
     *             if the parameter exists but is not a boolean
     * @throws NullPointerException
     *             if key is null
     */
    public Boolean getBooleanOrNull(String key) {
        return getBoolean(key, null);
    }

    // ==================== Raw Access ====================

    /**
     * Gets a raw parameter value without type checking.
     *
     * <p>
     * This method should be used sparingly, only when you need to handle complex types (lists, maps, etc.) that don't
     * have dedicated accessor methods. For simple types (String, Integer, Boolean), prefer the type-safe methods.
     *
     * @param key
     *            the parameter name (must not be null)
     * @return the parameter value or null if missing
     * @throws NullPointerException
     *             if key is null
     */
    public Object get(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        return data.get(key);
    }

    // ==================== Validation Helpers ====================

    /**
     * Checks if a parameter exists in the input.
     *
     * <p>
     * A parameter the caller supplied as {@code null} does not exist: the factories drop it, so this returns
     * {@code false} for it just as it would for a key that was never supplied at all.
     *
     * @param key
     *            the parameter name (must not be null)
     * @return true if the parameter exists with a non-null value, false otherwise
     * @throws NullPointerException
     *             if key is null
     */
    public boolean has(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        return data.containsKey(key);
    }

    /**
     * Returns all parameter keys in the input, in the order they were supplied.
     *
     * @return an unmodifiable set of parameter keys (never null)
     */
    public Set<String> keys() {
        return data.keySet();
    }

    /**
     * Returns the number of parameters in the input.
     *
     * @return the parameter count (never negative)
     */
    public int size() {
        return data.size();
    }

    /**
     * Checks if the input has no parameters.
     *
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    // ==================== Conversion ====================

    /**
     * Returns an unmodifiable view of the raw input data.
     *
     * <p>
     * This method is provided for backward compatibility and debugging purposes. Prefer using the type-safe accessor
     * methods instead of working with the raw map.
     *
     * @return an unmodifiable map of the input data (never null)
     */
    public Map<String, Object> toMap() {
        return Collections.unmodifiableMap(data);
    }

    // ==================== Helper Methods ====================

    /**
     * Converts a value to an integer.
     *
     * <p>
     * Supports conversion from Number types and String representation of integers. When converting from a Number type,
     * validates that the value is within {@link Integer#MIN_VALUE} to {@link Integer#MAX_VALUE} range to prevent silent
     * overflow.
     *
     * @param key
     *            the parameter name (for error messages)
     * @param value
     *            the value to convert (must not be null)
     * @return the integer value
     * @throws IllegalArgumentException
     *             if the value cannot be converted to integer or is out of integer range
     */
    private Integer convertToInteger(String key, Object value) {
        if (value instanceof Number) {
            long longValue = ((Number) value).longValue();
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Parameter '" + key + "' value " + longValue + " is out of integer range");
            }
            return (int) longValue;
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Parameter '" + key + "' must be an integer, but was: '" + value + "'", e);
            }
        }
        throw new IllegalArgumentException(
                "Parameter '" + key + "' must be an integer, but was: " + value.getClass().getSimpleName());
    }

    private Long convertToLong(String key, Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Parameter '" + key + "' must be a long, but was: '" + value + "'",
                        e);
            }
        }
        throw new IllegalArgumentException(
                "Parameter '" + key + "' must be a long, but was: " + value.getClass().getSimpleName());
    }
    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ToolInput toolInput = (ToolInput) o;
        return Objects.equals(data, toolInput.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data);
    }

    @Override
    public String toString() {
        return "ToolInput{" + "data=" + data + '}';
    }
}
