package at.aimon.core.agent.definition.parser;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
import at.aimon.core.llm.ReasoningEffort;

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
 *   reasoningEffort: high
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

        // The guards prove the defaults these calls used to carry were unreachable except when the key was
        // written with nothing after it, which is where a bare `name:` silently became gpt5.1.
        if (configMap.containsKey("name")) {
            builder.name(requireString(configMap, "model.name"));
        }
        if (configMap.containsKey("temperature")) {
            builder.temperature(requireDouble(configMap, "model.temperature"));
        }
        if (configMap.containsKey("maxTokens")) {
            builder.maxTokens(requireInt(configMap, "model.maxTokens"));
        }
        if (configMap.containsKey("topP")) {
            builder.topP(requireDouble(configMap, "model.topP"));
        }
        if (configMap.containsKey("reasoningEffort")) {
            builder.reasoningEffort(extractReasoningEffort(configMap));
        }

        return builder.build();
    }

    /**
     * Reads {@code model.reasoningEffort} onto the neutral enum, ignoring case.
     *
     * <p>
     * All four keys in this block are now read the same way — a value the parser cannot use is an error naming the
     * key and the value, never a silently substituted default. This one carries the extra thing an enum needs: the
     * accepted spellings, listed in the message, because a rung nobody recognises would otherwise reach the provider
     * as "no effort configured" and the operator would read the absence as their setting being honoured. (Until #74
     * its three neighbours did substitute their default, so {@code temperature: "hot"} silently became {@code 1.0}
     * and nobody heard about it.)
     *
     * <p>
     * The case tolerance is the one {@code CliConfigLoader} already applies to the same enum on the yaml surface, for
     * the same reason: an operator writes {@code high}, not {@code HIGH}.
     *
     * @param configMap
     *            the {@code model} block
     * @return the matching constant
     * @throws AgentDefinitionParseException
     *             naming the key and every accepted spelling when nothing matches
     */
    private ReasoningEffort extractReasoningEffort(Map<String, Object> configMap) {
        final Object value = configMap.get("reasoningEffort");
        final String written = value == null ? "" : value.toString().trim();
        for (ReasoningEffort candidate : ReasoningEffort.values()) {
            if (candidate.name().equalsIgnoreCase(written)) {
                return candidate;
            }
        }
        final StringBuilder accepted = new StringBuilder();
        for (ReasoningEffort candidate : ReasoningEffort.values()) {
            accepted.append(accepted.length() == 0 ? "" : ", ").append(candidate.name().toLowerCase(Locale.ROOT));
        }
        throw new AgentDefinitionParseException(
                "Invalid model.reasoningEffort: " + value + ". Accepted values: " + accepted + ".");
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
     * Reads an optional text value.
     *
     * <p>
     * {@code label} is the key as an operator wrote it, qualified where the key lives in a block
     * ({@code model.name}); the map key is derived from it, so there is no second string to drift out of step with
     * the message. Absence takes the default; a key written with nothing after it is an error, because
     * {@code map.get(key) == null} cannot tell "not written" from "written empty" and substituting a default for the
     * second is the defect this method used to have.
     *
     * @param map
     *            The map to extract from
     * @param label
     *            The qualified key name, used both to look the value up and to name it in a failure
     * @param defaultValue
     *            The value to use when the key is absent
     * @return The extracted string value
     */
    private String extractString(Map<String, Object> map, String label, String defaultValue) {
        return map.containsKey(leafKey(label)) ? requireString(map, label) : defaultValue;
    }

    /** Reads an optional whole number, by the rule {@link #extractString} states. */
    private int extractInt(Map<String, Object> map, String label, int defaultValue) {
        return map.containsKey(leafKey(label)) ? requireInt(map, label) : defaultValue;
    }

    private String extractStringOrElseThrow(Map<String, Object> map, String label) {
        final Object value = map.get(leafKey(label));
        if (value != null) {
            return value.toString();
        }
        throw new AgentDefinitionParseException("Required key '" + label + "' not found in metadata.");
    }

    /**
     * Reads a text value the caller has already established is present.
     *
     * <p>
     * Anything with a {@code toString()} is text, as it always was — the one value this refuses is no value at all.
     */
    private String requireString(Map<String, Object> map, String label) {
        final Object value = map.get(leafKey(label));
        if (value != null) {
            return value.toString();
        }
        throw new AgentDefinitionParseException("Invalid " + label + ": no value. Expected a text value.");
    }

    /**
     * Reads a whole number the caller has already established is present.
     *
     * <p>
     * A whole-valued {@code Double} is accepted and a real fraction is not, which is the answer
     * {@code ToolInputBinder.isWholeNumber} already gives on the tool surface, and which
     * {@code docs/features/tool/tool-development-guide.md} already documents for {@code integer} parameters —
     * {@code 3.0} passes, a real fractional part does not. Reading through {@code intValue()} instead, which is
     * what this did, truncated {@code 4096.5} to {@code 4096} and narrowed {@code 9999999999} to
     * {@code 1410065407}, both without a word.
     */
    private int requireInt(Map<String, Object> map, String label) {
        final Object value = map.get(leafKey(label));
        if (value instanceof Number number) {
            try {
                final BigDecimal decimal = new BigDecimal(number.toString());
                if (decimal.stripTrailingZeros().scale() <= 0) {
                    return decimal.intValueExact();
                }
            } catch (NumberFormatException | ArithmeticException e) {
                // NumberFormatException: `.inf` and `.nan` have no decimal form at all. ArithmeticException: a whole
                // number too large for 32 bits. Both are the same answer to the operator, so both fall through.
            }
        }
        throw new AgentDefinitionParseException(
                "Invalid " + label + ": " + value + ". Expected a whole number that fits in a 32-bit integer.");
    }

    /**
     * Reads a number the caller has already established is present.
     *
     * <p>
     * A numeric-looking {@code String} is <em>not</em> coerced: {@code temperature: "0.7"} is an error rather than
     * {@code 0.7}, matching {@code ToolInputBinder.toDecimal}, and the message already tells the author what to
     * write. {@code 1e-3} — the case that would have argued for coercion — arrives from snakeyaml as a
     * {@code Double} anyway. Range is not this method's question: {@link LlmModel} owns it.
     */
    private double requireDouble(Map<String, Object> map, String label) {
        final Object value = map.get(leafKey(label));
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new AgentDefinitionParseException("Invalid " + label + ": " + value + ". Expected a number.");
    }

    /** The map key inside a qualified label: {@code model.temperature} is stored under {@code temperature}. */
    private String leafKey(String label) {
        final int lastDot = label.lastIndexOf('.');
        return lastDot < 0 ? label : label.substring(lastDot + 1);
    }
}
