package at.aimon.core.memory.redaction;

import java.util.Objects;

/**
 * A single redaction event recorded while applying a {@link RedactionPolicy}.
 *
 * <p>
 * Spans ({@code start}, {@code end}) refer to indices in the <em>original</em>
 * input string passed to {@link RedactionPolicy#redact(String)}, half-open
 * (i.e. {@code [start, end)}). The {@code pattern} is the category name (e.g.
 * {@code "AWS_KEY"}, {@code "JWT"}, {@code "EMAIL"}, {@code "PRIVATE_IP"},
 * {@code "SECRET"}) and {@code replacement} is the literal substitution token
 * inserted in the redacted text.
 *
 * <p>
 * Immutable value object built via {@link #of(String, int, int, String)}.
 */
public final class RedactionMatch {

    private final String pattern;
    private final int start;
    private final int end;
    private final String replacement;

    private RedactionMatch(String pattern, int start, int end, String replacement) {
        this.pattern = Objects.requireNonNull(pattern, "pattern cannot be null");
        this.replacement = Objects.requireNonNull(replacement, "replacement cannot be null");
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("pattern cannot be blank");
        }
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0, got " + start);
        }
        if (end <= start) {
            throw new IllegalArgumentException("end must be > start, got start=" + start + ", end=" + end);
        }
        this.start = start;
        this.end = end;
    }

    /**
     * Creates a new redaction match.
     *
     * @param pattern
     *            category name of the matched pattern (e.g. {@code "AWS_KEY"})
     * @param start
     *            inclusive start index in the original input ({@code >= 0})
     * @param end
     *            exclusive end index in the original input ({@code > start})
     * @param replacement
     *            literal token used to mask the match (e.g.
     *            {@code "[REDACTED:AWS_KEY]"})
     * @return a new {@link RedactionMatch}
     * @throws NullPointerException
     *             if {@code pattern} or {@code replacement} is null
     * @throws IllegalArgumentException
     *             if span is invalid or pattern is blank
     */
    public static RedactionMatch of(String pattern, int start, int end, String replacement) {
        return new RedactionMatch(pattern, start, end, replacement);
    }

    /**
     * @return the category/pattern name of the match (never null or blank)
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * @return inclusive start index in the original input
     */
    public int getStart() {
        return start;
    }

    /**
     * @return exclusive end index in the original input
     */
    public int getEnd() {
        return end;
    }

    /**
     * @return literal replacement token (never null)
     */
    public String getReplacement() {
        return replacement;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RedactionMatch that = (RedactionMatch) o;
        return start == that.start && end == that.end && pattern.equals(that.pattern)
                && replacement.equals(that.replacement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern, start, end, replacement);
    }

    @Override
    public String toString() {
        return "RedactionMatch{pattern='" + pattern + "', start=" + start + ", end=" + end + ", replacement='"
                + replacement + "'}";
    }
}
