package at.aimon.core.agent.compact;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable description of a single redaction rule used by {@link SensitivePatternRedactor}.
 *
 * <p>
 * A pattern carries a logical {@code name} (used in logs and the replacement template), a compiled regular expression,
 * and a {@code replacement} string substituted for every match. The default replacement is
 * {@code "[REDACTED:&lt;name&gt;]"}, which lets summaries remain self-describing without leaking the original value.
 *
 * <p>
 * Thread-safe.
 */
public final class RedactionPattern {

    public static final String DEFAULT_REPLACEMENT_PREFIX = "[REDACTED:";
    public static final String DEFAULT_REPLACEMENT_SUFFIX = "]";

    private final String name;
    private final Pattern pattern;
    private final String replacement;

    private RedactionPattern(String name, Pattern pattern, String replacement) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        this.pattern = Objects.requireNonNull(pattern, "pattern cannot be null");
        this.replacement = Objects.requireNonNull(replacement, "replacement cannot be null");
    }

    /**
     * Creates a pattern with the standard {@code [REDACTED:&lt;name&gt;]} replacement.
     *
     * @param name
     *            Logical name (must not be null/blank)
     * @param pattern
     *            Compiled regex (must not be null)
     * @return A new RedactionPattern
     */
    public static RedactionPattern of(String name, Pattern pattern) {
        Objects.requireNonNull(name, "name cannot be null");
        return new RedactionPattern(name, pattern, DEFAULT_REPLACEMENT_PREFIX + name + DEFAULT_REPLACEMENT_SUFFIX);
    }

    /**
     * Creates a pattern with a custom replacement string. Use {@code $0}, {@code $1}, etc. to reference capturing
     * groups
     * (standard {@link java.util.regex.Matcher#replaceAll(String)} semantics).
     *
     * @param name
     *            Logical name (must not be null/blank)
     * @param pattern
     *            Compiled regex (must not be null)
     * @param replacement
     *            Replacement template (must not be null)
     * @return A new RedactionPattern
     */
    public static RedactionPattern of(String name, Pattern pattern, String replacement) {
        return new RedactionPattern(name, pattern, replacement);
    }

    public String getName() {
        return name;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public String getReplacement() {
        return replacement;
    }

    @Override
    public String toString() {
        return "RedactionPattern{name=" + name + ", pattern=" + pattern.pattern() + "}";
    }
}
