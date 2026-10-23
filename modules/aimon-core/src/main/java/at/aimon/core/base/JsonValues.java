package at.aimon.core.base;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deep, unmodifiable copies of JSON-like values: maps, lists and the scalars inside them.
 *
 * <p>
 * The {@code null} rule follows {@link NullSafeMaps}: a map entry whose value is {@code null} is <b>absent</b> and is
 * dropped, at every depth. A {@code null} inside a list is kept — a list is positional, so dropping it would shift the
 * items after it; whether a {@code null} item is acceptable is the business of whoever checked the value.
 *
 * <p>
 * Key order is kept ({@link LinkedHashMap}), and keys are turned into strings, as JSON object keys are. Scalars are
 * kept as they are.
 */
public final class JsonValues {

    private JsonValues() {
        throw new UnsupportedOperationException("Utility class must not be instantiated");
    }

    /**
     * Copies a JSON object deeply.
     *
     * @param value
     *            the map to copy (must not be null)
     * @return the copy, unmodifiable all the way down, with null map values dropped
     */
    public static Map<String, Object> immutableCopy(Map<String, ?> value) {
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
}
