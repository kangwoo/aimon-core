package at.aimon.core.environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one rule that turns a run's execution attributes into {@link EnvironmentRequest#requestAttributes()} (issue
 * #252).
 *
 * <p>
 * Only entries whose key starts with {@link #PREFIX} are forwarded, and the prefix is removed: a host's
 * {@code executionAttribute("environment.sandbox.workspace", "ws-7")} reaches the provider as
 * {@code requestAttributes().get("sandbox.workspace")}, the same spelling a provider reads from
 * {@link EnvironmentRequest#definitionAttributes()}. Values must be {@code String}s, so providers never depend on a
 * caller's classes; anything else is refused rather than dropped or converted, because a dropped placement input
 * (such as "refuse if nothing is attached") fails open.
 *
 * <p>
 * Every executor that fills the field calls {@link #extract(Map)}, so a main turn and a routine cannot read the same
 * map two ways — the role {@code DefinitionAttributes} plays for definition files.
 */
public final class EnvironmentRequestAttributes {

    /** The reserved key prefix whose entries are placement input for the environment provider. */
    public static final String PREFIX = "environment.";

    private EnvironmentRequestAttributes() {
    }

    /**
     * Extracts the {@link #PREFIX} subset of a run's execution attributes, prefix removed.
     *
     * <p>
     * Error messages name the key and the value's class, never the value: values may be tenant or workspace
     * identifiers, and the caller logs the message.
     *
     * @param executionAttributes
     *            the run's execution attributes, or null
     * @return the subset (never null; unmodifiable; empty when nothing is prefixed)
     * @throws IllegalArgumentException
     *             if a prefixed entry's value is not a {@code String} (null included), or a key is exactly the prefix
     */
    public static Map<String, String> extract(Map<String, Object> executionAttributes) {
        if (executionAttributes == null || executionAttributes.isEmpty()) {
            return Map.of();
        }
        final Map<String, String> extracted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : executionAttributes.entrySet()) {
            final String key = entry.getKey();
            if (key == null || !key.startsWith(PREFIX)) {
                continue;
            }
            final String name = key.substring(PREFIX.length());
            if (name.isEmpty()) {
                throw new IllegalArgumentException(
                        "execution attribute '" + key + "' names no environment attribute after the prefix");
            }
            final Object value = entry.getValue();
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("execution attribute '" + key + "' must be a String, but was "
                        + (value == null ? "null" : value.getClass().getName()));
            }
            extracted.put(name, text);
        }
        return extracted.isEmpty() ? Map.of() : Map.copyOf(extracted);
    }
}
