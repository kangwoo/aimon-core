package at.aimon.core.memory.redaction;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Outcome of applying a {@link RedactionPolicy} to a message.
 *
 * <p>
 * Carries the post-redaction text, the list of {@link RedactionMatch matches}
 * applied (in the order they were produced), and a {@code modified} flag that
 * is {@code true} iff at least one match was recorded.
 *
 * <p>
 * Immutable value object — the matches list is defensively copied via
 * {@link List#copyOf(java.util.Collection)}.
 */
public final class RedactionResult {

    private final String redactedContent;
    private final List<RedactionMatch> matches;
    private final boolean modified;

    private RedactionResult(String redactedContent, List<RedactionMatch> matches) {
        this.redactedContent = Objects.requireNonNull(redactedContent, "redactedContent cannot be null");
        this.matches = List.copyOf(Objects.requireNonNull(matches, "matches cannot be null"));
        this.modified = !this.matches.isEmpty();
    }

    /**
     * Creates a result for an unchanged input — no matches were found.
     *
     * @param original
     *            the unmodified content (must not be null)
     * @return a result with {@code modified=false} and an empty matches list
     * @throws NullPointerException
     *             if {@code original} is null
     */
    public static RedactionResult unchanged(String original) {
        return new RedactionResult(Objects.requireNonNull(original, "original cannot be null"),
                Collections.emptyList());
    }

    /**
     * Creates a result for a redacted input. {@link #isModified()} is computed
     * as {@code !matches.isEmpty()}.
     *
     * @param redacted
     *            the post-redaction content (must not be null)
     * @param matches
     *            the matches that were applied (must not be null; defensively
     *            copied)
     * @return a new {@link RedactionResult}
     * @throws NullPointerException
     *             if any argument is null
     */
    public static RedactionResult of(String redacted, List<RedactionMatch> matches) {
        return new RedactionResult(redacted, matches);
    }

    /**
     * @return the post-redaction message content (never null)
     */
    public String getRedactedContent() {
        return redactedContent;
    }

    /**
     * @return immutable list of matches in the order they were applied
     */
    public List<RedactionMatch> getMatches() {
        return matches;
    }

    /**
     * @return {@code true} iff at least one match was applied
     */
    public boolean isModified() {
        return modified;
    }

    /**
     * Distinct, alphabetically sorted set of pattern categories present in
     * {@link #getMatches()}.
     *
     * @return immutable sorted set (never null; empty when no matches)
     */
    public Set<String> getCategories() {
        if (matches.isEmpty()) {
            return Collections.emptySet();
        }
        TreeSet<String> categories = matches.stream().map(RedactionMatch::getPattern)
                .collect(Collectors.toCollection(TreeSet::new));
        return Collections.unmodifiableSet(categories);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RedactionResult that = (RedactionResult) o;
        return modified == that.modified && redactedContent.equals(that.redactedContent)
                && matches.equals(that.matches);
    }

    @Override
    public int hashCode() {
        return Objects.hash(redactedContent, matches, modified);
    }

    @Override
    public String toString() {
        return "RedactionResult{modified=" + modified + ", matches=" + matches.size() + ", redactedContent='"
                + redactedContent + "'}";
    }
}
