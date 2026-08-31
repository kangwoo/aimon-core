package at.aimon.core.subagent.parser;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses subagent content including YAML frontmatter and markdown body.
 *
 * <p>
 * Extracts and parses YAML frontmatter enclosed in {@code ---} delimiters at the beginning of markdown files, and
 * separates the markdown body. Supports parsing of subagent metadata including:
 *
 * <ul>
 * <li>description: A human-readable subagent description (should include when to use)
 * <li>when-to-use: Optional trigger conditions for selecting this subagent
 * <li>allowed-tools: List or comma-separated string of allowed tools
 * <li>model: Model to use (sonnet, haiku, opus)
 * <li>max-iterations: Optional positive integer cap on the ReAct loop (defaults applied downstream)
 * </ul>
 *
 * <p>
 * Expected format:
 *
 * <pre>
 * {@code
 * ---
 * name: code-reviewer
 * description: Expert code reviewer. Use when reviewing code for quality, security, and best practices.
 * when-to-use: When you need code review or quality analysis
 * allowed-tools: Read, Grep, Glob, Bash
 * model: sonnet
 * max-iterations: 50
 * ---
 *
 * You are an expert code reviewer...
 * }
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
 *     String fileContent = """
 *             ---
 *             description: Code reviewer. Use when reviewing code for quality and security.
 *             allowed-tools: Read, Grep
 *             model: sonnet
 *             ---
 *
 *             You are an expert code reviewer...
 *             """;
 *
 *     SubagentContentParser parser = new SubagentContentParser();
 *     SubagentContentResult result = parser.parse(fileContent);
 *
 *     String systemPrompt = result.getSystemPrompt();
 *     String description = result.getDescription();
 *     List<String> tools = result.getTools();
 * }
 * </pre>
 */
public class SubagentContentParser {
    // Matches YAML frontmatter between --- delimiters
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\n(.*?)\n---\\s*\n(.*)$",
            Pattern.DOTALL);

    /**
     * A parser for one call and one call only.
     *
     * <p>
     * {@code Yaml} carries parse state — {@code loadFromReader} publishes each call's {@code Composer} onto the shared
     * {@code BaseConstructor} and reads it straight back — so an instance held in a field silently mixes concurrent
     * parses together. Constructing one costs far less than the parse it serves.
     *
     * <p>
     * {@code SafeConstructor} is not what stops deserialization gadgets here, contrary to what the comment on this
     * line used to say; since snakeyaml 2.0 the default {@code UnTrustedTagInspector} already rejects custom global
     * tags at the compose stage, before any constructor runs. It is carried for uniformity across this repository's
     * parsers and as a second line should a consumer ever resolve snakeyaml below 2.0. The {@link LoaderOptions} must
     * stay at its defaults: tightening one (duplicate keys, nesting depth, code-point limit) would change what this
     * repository's existing subagent files parse to.
     */
    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    /**
     * Parses frontmatter and markdown content from a subagent file.
     *
     * <p>
     * If no frontmatter is found, returns empty metadata and treats the entire content as system prompt.
     *
     * @param content
     *            The file content to parse (must not be null)
     * @return A SubagentContentResult containing metadata and system prompt
     * @throws NullPointerException
     *             if content is null
     * @throws SubagentParseException
     *             if YAML parsing fails or a field has an invalid value
     */
    public SubagentContentResult parse(String content) {
        Objects.requireNonNull(content, "Content cannot be null");

        final Matcher matcher = FRONTMATTER_PATTERN.matcher(content);

        if (!matcher.matches()) {
            // No frontmatter, treat entire content as system prompt
            return new SubagentContentResult(null, null, List.of(), null, null, content);
        }

        final String yamlContent = matcher.group(1);
        final String systemPrompt = matcher.group(2);

        try {
            final Map<String, Object> yamlData = newYaml().load(yamlContent);
            return parseMetadata(yamlData, systemPrompt);
        } catch (SubagentParseException e) {
            // Preserve the specific field-level message instead of masking it with the generic wrapper.
            throw e;
        } catch (Exception e) {
            throw new SubagentParseException("Failed to parse YAML frontmatter", e);
        }
    }

    /** Parses subagent metadata from YAML data. */
    private SubagentContentResult parseMetadata(Map<String, Object> yamlData, String systemPrompt) {
        if (yamlData == null) {
            return new SubagentContentResult(null, null, List.of(), null, null, systemPrompt);
        }

        final String description = parseString(yamlData, "description");
        final String whenToUse = parseString(yamlData, "when-to-use");
        final String model = parseString(yamlData, "model");
        final Integer maxIterations = parseMaxIterations(yamlData);

        final Object toolsObj = yamlData.get("allowed-tools");
        final List<String> tools = parseList(toolsObj, "allowed-tools");

        return new SubagentContentResult(description, whenToUse, tools, model, maxIterations, systemPrompt);
    }

    /**
     * Reads a scalar string field, rejecting non-scalar values (e.g. a YAML list or map) with a clear message.
     *
     * @return the trimmed-as-authored string value, or null when the key is absent
     */
    private String parseString(Map<String, Object> yamlData, String fieldName) {
        final Object value = yamlData.get(fieldName);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new SubagentParseException(
                    "Invalid " + fieldName + " format: must be a string, got: " + value.getClass().getSimpleName());
        }
        return s;
    }

    /**
     * Parses the optional {@code max-iterations} field.
     *
     * <p>
     * Mirrors the skill parser contract: the value must be a bare positive integer. A quoted string, boolean, or
     * non-positive value is rejected with a {@link SubagentParseException}. An absent field yields null so the metadata
     * default applies.
     */
    private Integer parseMaxIterations(Map<String, Object> yamlData) {
        final Object value = yamlData.get("max-iterations");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer i)) {
            throw new SubagentParseException("Invalid max-iterations format: must be a positive integer, got: "
                    + value.getClass().getSimpleName());
        }
        if (i <= 0) {
            throw new SubagentParseException("Invalid max-iterations: must be positive, but was: " + i);
        }
        return i;
    }

    /**
     * Parses a field from various formats.
     *
     * <p>
     * Supports:
     *
     * <ul>
     * <li>String: "item1, item2, item3" (comma-separated)
     * <li>List: ["item1", "item2", "item3"]
     * </ul>
     */
    private List<String> parseList(Object obj, String fieldName) {
        if (obj == null) {
            return List.of();
        }

        if (obj instanceof String) {
            // Single string: "item1, item2, item3"
            return Arrays.stream(((String) obj).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        if (obj instanceof List) {
            // List format
            return ((List<?>) obj).stream().map(Object::toString).toList();
        }

        throw new SubagentParseException("Invalid " + fieldName + " format: must be string or list");
    }
}
