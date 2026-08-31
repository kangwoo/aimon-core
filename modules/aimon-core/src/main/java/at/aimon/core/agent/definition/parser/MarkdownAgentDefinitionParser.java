package at.aimon.core.agent.definition.parser;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import at.aimon.core.agent.Version;
import at.aimon.core.agent.definition.AgentDefinition;
import at.aimon.core.agent.definition.exception.AgentDefinitionParseException;
import at.aimon.core.llm.LlmModel;

/**
 * Parses agent loader files with YAML frontmatter.
 *
 * <p>
 * Reuses existing MarkdownCommandParser pattern for consistency.
 *
 * <p>
 * Example loader format:
 *
 * <pre>
 * ---
 * name: coding-agent
 * maxIterations: 50
 * model:
 *   name: gpt5.1
 *   temperature: 0.7
 * tags:
 *   - coding
 *   - java
 * variables:
 *   language: Java
 * ---
 * You are a {{language}} expert...
 * </pre>
 */
public final class MarkdownAgentDefinitionParser implements AgentDefinitionParser {
    private static final Version DEFAULT_VERSION = new Version(1, 0, 0);

    private static final String FRONTMATTER_DELIMITER = "---";

    /**
     * A parser for one call and one call only.
     *
     * <p>
     * {@code Yaml} carries parse state — {@code loadFromReader} publishes each call's {@code Composer} onto the shared
     * {@code BaseConstructor} and reads it straight back — so an instance held in a field silently mixes concurrent
     * parses together. Constructing one costs far less than the parse it serves. No in-tree caller shares one of these
     * parsers across threads today, but {@code AgentBundleLoader} is public API and nothing stops an embedder.
     *
     * <p>
     * {@code SafeConstructor} is not what stops deserialization gadgets here; since snakeyaml 2.0 the default
     * {@code UnTrustedTagInspector} already rejects custom global tags at the compose stage, before any constructor
     * runs. It is carried for uniformity across this repository's parsers and as a second line should a consumer ever
     * resolve snakeyaml below 2.0. The {@link LoaderOptions} must stay at its defaults: tightening one (duplicate
     * keys, nesting depth, code-point limit) would change what this repository's existing agent definitions parse to.
     */
    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    @Override
    public AgentDefinition parse(InputStream input) {
        Objects.requireNonNull(input, "Input stream cannot be null");

        try {
            final String loadedContent = new String(input.readAllBytes());
            // Split frontmatter and body
            final String[] parts = loadedContent.split(FRONTMATTER_DELIMITER, 3);
            if (parts.length < 3) {
                throw new AgentDefinitionParseException("Invalid loader format: missing frontmatter delimiters");
            }

            // Parse YAML frontmatter
            final Map<String, Object> frontmatter = newYaml().load(parts[1]);
            if (frontmatter == null) {
                throw new AgentDefinitionParseException("Invalid loader format: empty frontmatter");
            }

            final String body = parts[2].trim();

            // Extract metadata
            final String name = extractStringOrElseThrow(frontmatter, "name");
            final Version version = extractVersion(frontmatter);
            final int maxIterations = extractInt(frontmatter, "maxIterations", Integer.MAX_VALUE);

            // Extract model config
            final LlmModel model = extractModel(frontmatter);

            // Extract tags
            final Set<String> tags = extractTags(frontmatter);

            // Extract variables
            @SuppressWarnings("unchecked")
            final Map<String, Object> variables = (Map<String, Object>) frontmatter.getOrDefault("variables", Map.of());

            return AgentDefinition.builder().name(name).version(version).model(model).maxIterations(maxIterations)
                    .systemPrompt(body).tags(tags).variables(variables).build();
        } catch (AgentDefinitionParseException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentDefinitionParseException("Failed to parse agent definition", e);
        }
    }

    /**
     * Extracts version from frontmatter.
     *
     * @param frontmatter
     *            The frontmatter map
     * @return Version instance
     * @throws AgentDefinitionParseException
     *             if version format is invalid
     */
    private Version extractVersion(Map<String, Object> frontmatter) {
        final String versionString = extractString(frontmatter, "version", null);
        if (versionString == null) {
            return DEFAULT_VERSION;
        }

        try {
            return Version.parse(versionString);
        } catch (IllegalArgumentException e) {
            throw new AgentDefinitionParseException("Invalid version format: " + versionString, e);
        }
    }

    /**
     * Extracts model configuration from frontmatter.
     *
     * @param frontmatter
     *            The frontmatter map
     * @return LlmModel instance
     */
    private LlmModel extractModel(Map<String, Object> frontmatter) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> configMap = (Map<String, Object>) frontmatter.get("model");

        if (configMap == null) {
            return LlmModel.builder().build();
        }

        final LlmModel.Builder builder = LlmModel.builder();

        if (configMap.containsKey("name")) {
            builder.name(extractString(configMap, "name", "gpt5.1"));
        }
        if (configMap.containsKey("temperature")) {
            builder.temperature(extractDouble(configMap, "temperature", 1.0));
        }
        if (configMap.containsKey("maxTokens")) {
            builder.maxTokens(extractInt(configMap, "maxTokens", 4096));
        }
        if (configMap.containsKey("topP")) {
            builder.topP(extractDouble(configMap, "topP", 1.0));
        }

        return builder.build();
    }

    /**
     * Extracts tags from frontmatter.
     *
     * <p>
     * Expects a YAML list (e.g. {@code tags: [a, b]} or {@code - a\n- b}). Blank and null elements
     * are ignored.
     *
     * @param frontmatter
     *            The frontmatter map
     * @return An insertion-ordered set of tags (never null, may be empty)
     * @throws AgentDefinitionParseException
     *             if the tags value is not a list
     */
    private Set<String> extractTags(Map<String, Object> frontmatter) {
        final Object raw = frontmatter.get("tags");
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new AgentDefinitionParseException(
                    "Invalid 'tags' value: expected a YAML list, got " + raw.getClass().getName());
        }

        final Set<String> tags = new LinkedHashSet<>();
        for (Object element : list) {
            if (element == null) {
                continue;
            }
            final String tag = element.toString().trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /**
     * Extracts a string value from map.
     *
     * @param map
     *            The map to extract from
     * @param key
     *            The key to look up
     * @param defaultValue
     *            The default value if key not found
     * @return The extracted string value
     */
    private String extractString(Map<String, Object> map, String key, String defaultValue) {
        final Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private String extractStringOrElseThrow(Map<String, Object> map, String key) {
        final Object value = map.get(key);
        if (value != null) {
            return value.toString();
        }
        throw new AgentDefinitionParseException("Required key '" + key + "' not found in metadata.");
    }

    /**
     * Extracts an integer value from map.
     *
     * @param map
     *            The map to extract from
     * @param key
     *            The key to look up
     * @param defaultValue
     *            The default value if key not found
     * @return The extracted integer value
     */
    private int extractInt(Map<String, Object> map, String key, int defaultValue) {
        final Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * Extracts a double value from map.
     *
     * @param map
     *            The map to extract from
     * @param key
     *            The key to look up
     * @param defaultValue
     *            The default value if key not found
     * @return The extracted double value
     */
    private double extractDouble(Map<String, Object> map, String key, double defaultValue) {
        final Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }
}
