package at.aimon.core.skill.parser;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of parsing SKILL.md content.
 *
 * <p>
 * Contains the separated frontmatter (YAML) and body (Markdown) sections.
 *
 * <p>
 * Frontmatter <em>values</em> may be null. YAML reads a key written with no value ({@code license:}) as a null
 * mapping value, and this type carries that through rather than rejecting it, so that consumers can apply their own
 * reading of "declared but empty". Every consumer in this repository reads a null value the same way it reads an
 * absent key — see {@code MarkdownSkillParser}, where an empty required field therefore reports
 * {@code Missing required field: <name>} instead of failing anonymously.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillContentResult result = SkillContentParser.parse(rawContent);
 *
 *     Map&lt;String, Object&gt; frontmatter = result.getFrontmatter();
 *     String body = result.getBody();
 *
 *     String name = (String) frontmatter.get("name");
 *     String description = (String) frontmatter.get("description");
 * }
 * </pre>
 */
public final class SkillContentResult {

    /**
     * Creates a new SkillContentResult.
     *
     * @param frontmatter
     *            The frontmatter map itself must not be null; its values may be (see the class documentation)
     * @param body
     *            The body content (must not be null)
     * @return A new SkillContentResult instance (never null)
     * @throws NullPointerException
     *             if {@code frontmatter} or {@code body} is null
     */
    public static SkillContentResult of(Map<String, Object> frontmatter, String body) {
        return new SkillContentResult(frontmatter, body);
    }

    private final Map<String, Object> frontmatter;
    private final String body;

    private SkillContentResult(Map<String, Object> frontmatter, String body) {
        // Not Map.copyOf: it rejects null values, which YAML produces for a key written without one. The copy is
        // defensive and never escapes except through the unmodifiable view, so this stays effectively immutable.
        this.frontmatter = Collections
                .unmodifiableMap(new HashMap<>(Objects.requireNonNull(frontmatter, "Frontmatter cannot be null")));
        this.body = Objects.requireNonNull(body, "Body cannot be null");
    }

    /**
     * Gets the frontmatter map.
     *
     * @return Unmodifiable map of frontmatter (never null, may be empty, and individual values may be null when the
     *         key was written without a value)
     */
    public Map<String, Object> getFrontmatter() {
        return frontmatter;
    }

    /**
     * Gets the body content.
     *
     * @return The body content (never null, may be empty)
     */
    public String getBody() {
        return body;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SkillContentResult that = (SkillContentResult) o;
        return Objects.equals(frontmatter, that.frontmatter) && Objects.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frontmatter, body);
    }

    @Override
    public String toString() {
        return "SkillContentResult{" + "frontmatter=" + frontmatter.keySet() + ", bodyLength=" + body.length() + '}';
    }
}
