package at.aimon.core.base;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The free-form {@code attributes} of an agent or subagent definition: string keys to string values that the framework
 * carries but never reads, for a component it does not know about — an execution environment provider choosing a
 * sandbox slot from {@code sandbox.slot}, say (execution-environment design §5.2).
 *
 * <p>
 * One reading for both definition files, so {@code AGENT.md} and {@code agents/*.md} cannot disagree on what the same
 * block means. Nested maps flatten to dotted keys, so these two spellings are the same attribute:
 *
 * <pre>
 * attributes:            attributes:
 *   sandbox:               sandbox.slot: build
 *     slot: build
 * </pre>
 *
 * <p>
 * Scalar values (text, numbers, booleans) become their text. A list, a null value, an empty nested map, a blank key,
 * the same key written both nested and dotted, and a key that is both a value and a group ({@code sandbox: x} beside
 * {@code sandbox.slot: y}) are errors rather than dropped or kept ambiguous: an attribute is read by something that is
 * not here to
 * complain, so one silently lost would surface as a sandbox picked by default with nobody knowing why. The same key
 * written twice at one level is not caught here — the definition parsers keep YAML's default of letting the last one
 * win.
 *
 * <p>
 * <b>Quote values that are not plain text.</b> The parsers read YAML 1.1, which types unquoted scalars before this
 * class sees them: {@code on} becomes {@code true}, {@code 010} becomes {@code 8}, and a date such as
 * {@code 2026-01-01} is not a scalar this class accepts. {@code "on"}, {@code "010"} and {@code "2026-01-01"} arrive as
 * written.
 *
 * <p>
 * Keys are trimmed when read from frontmatter; a key handed to {@link #copyOf(Map)} with surrounding whitespace is
 * rejected rather than trimmed, so a code-built definition cannot hold a key a file-built one could never produce.
 */
public final class DefinitionAttributes {

    /** The frontmatter key both definition parsers read the block from. */
    public static final String FRONTMATTER_KEY = "attributes";

    private DefinitionAttributes() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Reads the {@code attributes} block of a definition's frontmatter.
     *
     * @param raw
     *            the value under {@link #FRONTMATTER_KEY}, as the YAML parser produced it (null when absent)
     * @return the flattened attributes in the order written (never null; unmodifiable; empty when {@code raw} is null)
     * @throws IllegalArgumentException
     *             if the block is not a map, or any entry breaks the rules in the class description; the message names
     *             the offending key
     */
    public static Map<String, String> fromFrontmatter(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> block)) {
            throw new IllegalArgumentException(
                    "Invalid '" + FRONTMATTER_KEY + "' value: expected a map, got " + raw.getClass().getSimpleName());
        }
        final Map<String, String> flattened = new LinkedHashMap<>();
        flatten("", block, flattened);
        for (String key : flattened.keySet()) {
            for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
                final String group = key.substring(0, dot);
                if (flattened.containsKey(group)) {
                    throw new IllegalArgumentException("Invalid '" + FRONTMATTER_KEY + "' entry: '" + group
                            + "' is both a value and a group (of '" + key + "')");
                }
            }
        }
        return Collections.unmodifiableMap(flattened);
    }

    /**
     * Validates and copies attributes handed to a builder, applying the same key and value rules as
     * {@link #fromFrontmatter(Object)} for a map that is already flat.
     *
     * @param attributes
     *            the attributes (must not be null)
     * @return an unmodifiable copy in iteration order
     * @throws NullPointerException
     *             if the map, a key or a value is null
     * @throws IllegalArgumentException
     *             if a key is blank or has surrounding whitespace
     */
    public static Map<String, String> copyOf(Map<String, String> attributes) {
        Objects.requireNonNull(attributes, "attributes cannot be null");
        final Map<String, String> copy = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            Objects.requireNonNull(key, "attribute key cannot be null");
            Objects.requireNonNull(value, "value of attribute '" + key + "' cannot be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("attribute key cannot be blank");
            }
            if (!key.equals(key.trim())) {
                throw new IllegalArgumentException("attribute key '" + key + "' has surrounding whitespace");
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static void flatten(String prefix, Map<?, ?> block, Map<String, String> into) {
        for (Map.Entry<?, ?> entry : block.entrySet()) {
            final Object rawKey = entry.getKey();
            final String segment = rawKey == null ? "" : rawKey.toString().trim();
            if (segment.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid '" + FRONTMATTER_KEY + "' entry: blank key" + under(prefix));
            }
            final String key = prefix.isEmpty() ? segment : prefix + '.' + segment;
            final Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                if (nested.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Invalid '" + FRONTMATTER_KEY + "' entry: '" + key + "' is an empty map");
                }
                flatten(key, nested, into);
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                if (into.putIfAbsent(key, value.toString()) != null) {
                    throw new IllegalArgumentException("Invalid '" + FRONTMATTER_KEY + "' entry: '" + key
                            + "' is written twice (once nested, once dotted)");
                }
            } else if (value == null) {
                throw new IllegalArgumentException(
                        "Invalid '" + FRONTMATTER_KEY + "' entry: '" + key + "' has no value");
            } else if (value instanceof List<?>) {
                throw new IllegalArgumentException("Invalid '" + FRONTMATTER_KEY + "' entry: '" + key
                        + "' is a list; an attribute value must be a single text, number or boolean");
            } else {
                throw new IllegalArgumentException(
                        "Invalid '" + FRONTMATTER_KEY + "' entry: '" + key + "' has an unsupported value of type "
                                + value.getClass().getSimpleName() + "; quote it to keep it as text");
            }
        }
    }

    private static String under(String prefix) {
        return prefix.isEmpty() ? "" : " under '" + prefix + "'";
    }
}
