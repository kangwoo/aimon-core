package at.aimon.core.knowledge.wiki;

import java.util.Locale;
import java.util.Objects;

/**
 * Semantic classification of a wiki page.
 *
 * <p>
 * Each type represents a distinct role a page plays in the LLM-maintained wiki layer. The type influences file-name
 * conventions (prefix on disk), how the LLM treats the page during ingest (create vs. merge), and how the index groups
 * pages for display and drill-down search.
 *
 * <p>
 * See {@code docs/references/llm-wiki.md} for the conceptual model. The types here correspond to the page kinds
 * described in that document:
 * <ul>
 * <li>{@link #SUMMARY} — a one-to-one summary of a raw source document
 * <li>{@link #ENTITY} — a concrete named thing (e.g., "Kubernetes Pod", "PostgreSQL 16")
 * <li>{@link #CONCEPT} — an abstract idea or pattern (e.g., "Eventual Consistency")
 * <li>{@link #COMPARISON} — a side-by-side analysis of two or more subjects
 * <li>{@link #OVERVIEW} — a high-level map of a domain or topic
 * <li>{@link #SYNTHESIS} — a cross-source insight not contained in any single source
 * <li>{@link #ANSWER} — a filed query answer ({@link FiledAnswer}), historically stored under {@code answer-*.md}
 * </ul>
 *
 * <p>
 * {@link #SUMMARY} is the default used when no explicit type is present in a page's frontmatter — this preserves
 * backward compatibility with wiki content written before the type system was introduced.
 */
public enum WikiPageType {

    SUMMARY("summary"), ENTITY("entity"), CONCEPT("concept"), COMPARISON("comparison"), OVERVIEW("overview"), SYNTHESIS(
            "synthesis"), ANSWER("answer");

    /**
     * The default page type used when a page has no explicit {@code type:} frontmatter field. Kept explicit (rather
     * than relying on enum ordering) so the intent is visible to readers.
     */
    public static final WikiPageType DEFAULT = SUMMARY;

    private final String prefix;

    WikiPageType(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Returns the file-name prefix used on disk for pages of this type (e.g., {@code "summary"} for files named
     * {@code summary-<slug>.md}). The prefix is always lowercase and does not include the trailing hyphen.
     *
     * @return the file-name prefix (never null)
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Returns the canonical token used in frontmatter {@code type:} fields. Equivalent to the enum name in lowercase
     * (e.g., {@code "entity"} for {@link #ENTITY}).
     *
     * @return the frontmatter token (never null)
     */
    public String getToken() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a frontmatter token into a {@link WikiPageType}, returning {@link #DEFAULT} when the token is null, blank,
     * or not recognized. Matching is case-insensitive and trims surrounding whitespace.
     *
     * <p>
     * This method never throws: unknown tokens degrade to the default type so that a malformed page does not break
     * ingest or search. Callers that need strict validation should use {@link #strictParse(String)}.
     *
     * @param token
     *            the frontmatter token (may be null)
     * @return the matching type, or {@link #DEFAULT} if none matches
     */
    public static WikiPageType fromToken(String token) {
        if (token == null) {
            return DEFAULT;
        }
        final String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return DEFAULT;
        }
        for (WikiPageType type : values()) {
            if (type.getToken().equals(normalized)) {
                return type;
            }
        }
        return DEFAULT;
    }

    /**
     * Parses a frontmatter token strictly, throwing {@link IllegalArgumentException} on null, blank, or unknown
     * values. Intended for tests and configuration loading where silent fallback would mask errors.
     *
     * @param token
     *            the frontmatter token
     * @return the matching type (never null)
     * @throws IllegalArgumentException
     *             if the token is null, blank, or not a known type
     */
    public static WikiPageType strictParse(String token) {
        Objects.requireNonNull(token, "token must not be null");
        final String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        for (WikiPageType type : values()) {
            if (type.getToken().equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown wiki page type: " + token);
    }

    /**
     * Derives the type from a page file name by inspecting its prefix. For example, {@code "entity-kubernetes.md"}
     * returns {@link #ENTITY}. Returns {@link #DEFAULT} when the file name does not match any known prefix — this
     * preserves backward compatibility with wiki files created before the type system was introduced.
     *
     * @param fileName
     *            the bare file name (e.g., {@code "summary-foo.md"}, no directory component)
     * @return the type inferred from the prefix, or {@link #DEFAULT} when no prefix matches
     */
    public static WikiPageType fromFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return DEFAULT;
        }
        final String lower = fileName.toLowerCase(Locale.ROOT);
        for (WikiPageType type : values()) {
            if (lower.startsWith(type.prefix + "-")) {
                return type;
            }
        }
        return DEFAULT;
    }
}
