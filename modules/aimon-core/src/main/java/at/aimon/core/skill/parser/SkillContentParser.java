package at.aimon.core.skill.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parser for SKILL.md content.
 *
 * <p>
 * Parses SKILL.md files with YAML frontmatter and Markdown body:
 *
 * <pre>
 * ---
 * name: skill-name
 * description: Skill description
 * license: MIT
 * ---
 *
 * # Skill Title
 * Skill instructions...
 * </pre>
 *
 * <p>
 * Safe to call from several threads at once: each parse builds its own {@link Yaml}, which snakeyaml documents as not
 * thread-safe. See {@link #newYaml()}.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     String content = "---\nname: my-skill\n---\n# My Skill";
 *     SkillContentResult result = SkillContentParser.parse(content);
 *
 *     Map&lt;String, Object&gt; frontmatter = result.getFrontmatter();
 *     String body = result.getBody();
 * }
 * </pre>
 */
public final class SkillContentParser {

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\n(.*?)\n---\\s*\n(.*)$",
            Pattern.DOTALL);

    /**
     * Parses SKILL.md content into frontmatter and body.
     *
     * <p>
     * A frontmatter key written without a value ({@code license:}) is YAML's null, and is returned as a null map
     * value rather than rejected. Deciding what an empty value means belongs to the caller: {@code MarkdownSkillParser}
     * reads it exactly as it reads an absent key, so an empty required field is reported by name.
     *
     * @param content
     *            The SKILL.md content (must not be null)
     * @return Parsed result with frontmatter and body (never null)
     * @throws NullPointerException
     *             if {@code content} is null
     * @throws IllegalArgumentException
     *             if content has invalid format
     */
    public static SkillContentResult parse(String content) {
        Objects.requireNonNull(content, "Content cannot be null");

        final Matcher matcher = FRONTMATTER_PATTERN.matcher(content);

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid SKILL.md format: missing or malformed frontmatter. "
                    + "Expected format:\n---\nname: skill-name\n---\n\nBody content...");
        }

        final String frontmatterYaml = matcher.group(1);
        final String body = matcher.group(2);

        final Map<String, Object> frontmatter = parseFrontmatter(frontmatterYaml);

        return SkillContentResult.of(frontmatter, body);
    }

    // Private constructor to prevent instantiation
    private SkillContentParser() {
    }

    /**
     * A parser for one call and one call only.
     *
     * <p>
     * {@code Yaml} carries parse state — {@code loadFromReader} publishes each call's {@code Composer} onto the shared
     * {@code BaseConstructor} and reads it straight back — so an instance held in a field silently mixes concurrent
     * parses together. Constructing one costs far less than the parse it serves.
     *
     * <p>
     * {@code SafeConstructor} is not what stops deserialization gadgets here; since snakeyaml 2.0 the default
     * {@code UnTrustedTagInspector} already rejects custom global tags at the compose stage, before any constructor
     * runs. It is carried for uniformity across this repository's parsers and as a second line should a consumer ever
     * resolve snakeyaml below 2.0. The {@link LoaderOptions} must stay at its defaults: tightening one (duplicate
     * keys, nesting depth, code-point limit) would change what this repository's existing skill files parse to.
     */
    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFrontmatter(String yaml) {
        try {
            final Object parsed = newYaml().load(yaml);
            if (parsed instanceof Map) {
                return new HashMap<>((Map<String, Object>) parsed);
            } else {
                throw new IllegalArgumentException("Frontmatter must be a YAML map, got: "
                        + (parsed != null ? parsed.getClass().getSimpleName() : "null"));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse YAML frontmatter: " + e.getMessage(), e);
        }
    }
}
