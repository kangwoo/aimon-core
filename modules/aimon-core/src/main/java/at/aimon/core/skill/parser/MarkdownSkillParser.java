package at.aimon.core.skill.parser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.exception.SkillParseException;
import at.aimon.core.skill.hook.SkillHookSet;
import at.aimon.core.skill.render.ShellArgumentTokenizer;

/**
 * Markdown-based implementation of SkillParser.
 *
 * <p>
 * Parses SKILL.md files following the Agent Skills standard format:
 *
 * <pre>
 * ---
 * name: skill-name
 * description: Skill description
 * license: MIT                    (optional)
 * compatibility: Requires...      (optional)
 * metadata:                       (optional)
 *   author: example
 *   version: 1.0.0
 * allowed-tools: Read Grep        (optional)
 * ---
 *
 * # Skill Title
 * Skill instructions...
 * </pre>
 *
 * <p>
 * Thread-safe and stateless.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillParser parser = new MarkdownSkillParser();
 *
 *     String content = Files.readString(Path.of("skills/my-skill/SKILL.md"));
 *     Skill skill = parser.parse("my-skill", content);
 * }
 * </pre>
 */
public class MarkdownSkillParser implements SkillParser {

    private final ShellArgumentTokenizer tokenizer;
    private final SkillHookSetParser hookSetParser;

    /**
     * Creates a parser using a default {@link ShellArgumentTokenizer} and a {@link SkillHookSetParser} with shell
     * actions disabled.
     *
     * <p>
     * Skills that declare {@code hooks.<event>.action.type: shell} will fail at parse time. Use
     * {@link #MarkdownSkillParser(ShellArgumentTokenizer, SkillHookSetParser)} with a parser wired to a
     * {@link at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor} to enable them.
     */
    public MarkdownSkillParser() {
        this(new ShellArgumentTokenizer(), new SkillHookSetParser());
    }

    /**
     * Creates a parser using the given tokenizer and a default {@link SkillHookSetParser} with shell actions disabled.
     *
     * @param tokenizer
     *            The tokenizer used to split string-form {@code arguments} declarations (must not be null)
     */
    public MarkdownSkillParser(ShellArgumentTokenizer tokenizer) {
        this(tokenizer, new SkillHookSetParser());
    }

    /**
     * Creates a parser using the given tokenizer and hook-set parser.
     *
     * @param tokenizer
     *            The tokenizer used to split string-form {@code arguments} declarations (must not be null)
     * @param hookSetParser
     *            The parser used to convert the {@code hooks:} subtree into a {@link SkillHookSet} (must not be null;
     *            wire with a {@link at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor} to allow
     *            shell actions)
     */
    public MarkdownSkillParser(ShellArgumentTokenizer tokenizer, SkillHookSetParser hookSetParser) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "Tokenizer cannot be null");
        this.hookSetParser = Objects.requireNonNull(hookSetParser, "Hook set parser cannot be null");
    }

    @Override
    public Skill parse(String skillName, String content) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");

        try {
            // Parse frontmatter and body
            final SkillContentResult result = SkillContentParser.parse(content);
            final Map<String, Object> frontmatter = result.getFrontmatter();
            final String body = result.getBody();

            // Extract required fields
            final String name = extractRequiredString(frontmatter, "name", skillName);
            final String description = extractRequiredString(frontmatter, "description", skillName);

            // Validate name matches
            if (!skillName.equals(name)) {
                throw new SkillParseException(skillName, String
                        .format("Skill name mismatch: expected '%s' but found '%s' in frontmatter", skillName, name));
            }

            // Extract optional fields
            final String license = extractOptionalString(frontmatter, "license");
            final String compatibility = extractOptionalString(frontmatter, "compatibility");
            final Map<String, String> metadata = extractMetadata(frontmatter);
            final String allowedTools = extractOptionalString(frontmatter, "allowed-tools");
            final List<String> argumentNames = extractArgumentNames(frontmatter);
            final InvokePolicy invokePolicy = extractInvokePolicy(frontmatter);
            final Integer maxIterations = extractMaxIterations(frontmatter);
            final SkillHookSet hooks = hookSetParser.parse(skillName, frontmatter.get("hooks"));

            // Build metadata
            final SkillMetadata.Builder metadataBuilder = SkillMetadata.builder().name(name).description(description)
                    .license(license).compatibility(compatibility).metadata(metadata).allowedTools(allowedTools)
                    .argumentNames(argumentNames).invokePolicy(invokePolicy).maxIterations(maxIterations).hooks(hooks);
            applyExecution(frontmatter, metadataBuilder);
            final SkillMetadata skillMetadata = metadataBuilder.build();

            // Build content
            final SkillContent skillContent = SkillContent.of(body);

            // Build skill
            return Skill.builder().name(skillName).metadata(skillMetadata).content(skillContent).build();

        } catch (SkillParseException e) {
            // Already named the skill and the field. SkillParseException is not an IllegalArgumentException, so
            // without this it fell to the blanket catch below and every "Missing required field: description" and
            // "Skill name mismatch" reached the author as "Unexpected error during parsing" instead.
            throw e;
        } catch (IllegalArgumentException e) {
            throw new SkillParseException(skillName, e.getMessage(), e);
        } catch (Exception e) {
            throw new SkillParseException(skillName, "Unexpected error during parsing", e);
        }
    }

    private String extractRequiredString(Map<String, Object> frontmatter, String key, String skillName) {
        final Object value = frontmatter.get(key);
        if (value == null) {
            throw new SkillParseException(skillName, String.format("Missing required field: %s", key));
        }
        if (!(value instanceof String)) {
            throw new SkillParseException(skillName,
                    String.format("Field '%s' must be a string, got: %s", key, value.getClass().getSimpleName()));
        }
        return (String) value;
    }

    private String extractOptionalString(Map<String, Object> frontmatter, String key) {
        final Object value = frontmatter.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    String.format("Field '%s' must be a string, got: %s", key, value.getClass().getSimpleName()));
        }
        return (String) value;
    }

    private List<String> extractArgumentNames(Map<String, Object> frontmatter) {
        final Object value = frontmatter.get("arguments");
        if (value == null) {
            return List.of();
        }
        if (value instanceof String s) {
            if (s.isBlank()) {
                return List.of();
            }
            return tokenizer.tokenize(s);
        }
        if (value instanceof List<?> rawList) {
            for (Object element : rawList) {
                if (!(element instanceof String)) {
                    throw new IllegalArgumentException(String
                            .format("Field 'arguments' list must contain only strings, got: %s", typeOf(element)));
                }
            }
            @SuppressWarnings("unchecked")
            final List<String> typed = (List<String>) rawList;
            return List.copyOf(typed);
        }
        throw new IllegalArgumentException(String.format(
                "Field 'arguments' must be a string or a list of strings, got: %s", value.getClass().getSimpleName()));
    }

    private InvokePolicy extractInvokePolicy(Map<String, Object> frontmatter) {
        final Object value = frontmatter.get("invoke");
        if (value == null) {
            return InvokePolicy.defaults();
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(
                    String.format("Field 'invoke' must be a mapping with 'user' / 'model' booleans, got: %s",
                            value.getClass().getSimpleName()));
        }
        boolean user = InvokePolicy.defaults().isUserInvocable();
        boolean model = InvokePolicy.defaults().isModelInvocable();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "Field 'invoke' keys must be strings, got: " + typeOf(entry.getKey()));
            }
            switch (key) {
                case "user" -> {
                    user = requireBoolean("invoke.user", entry.getValue());
                }
                case "model" -> {
                    model = requireBoolean("invoke.model", entry.getValue());
                }
                default -> throw new IllegalArgumentException(
                        "Field 'invoke' has unknown key: '" + key + "' (allowed: user, model)");
            }
        }
        return InvokePolicy.of(user, model);
    }

    private void applyExecution(Map<String, Object> frontmatter, SkillMetadata.Builder builder) {
        final Object value = frontmatter.get("execution");
        if (value == null) {
            return;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(
                    String.format("Field 'execution' must be a mapping with 'mode' / 'agent' keys, got: %s",
                            value.getClass().getSimpleName()));
        }
        ExecutionMode mode = null;
        String agent = null;
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "Field 'execution' keys must be strings, got: " + typeOf(entry.getKey()));
            }
            switch (key) {
                case "mode" -> {
                    mode = ExecutionMode.parse(requireString("execution.mode", entry.getValue()));
                }
                case "agent" -> {
                    agent = requireString("execution.agent", entry.getValue());
                }
                default -> throw new IllegalArgumentException(
                        "Field 'execution' has unknown key: '" + key + "' (allowed: mode, agent)");
            }
        }
        if (mode != null) {
            builder.executionMode(mode);
        }
        if (agent != null) {
            builder.forkAgentName(agent);
        }
    }

    /**
     * Names a value's type for an error message, without dereferencing it.
     *
     * <p>
     * YAML gives a null for any key written without a value, so a message built from {@code value.getClass()} throws
     * an anonymous NPE on precisely the malformed input it was meant to describe.
     */
    private static String typeOf(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private String requireString(String fieldName, Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(
                    String.format("Field '%s' must be a string, got: %s", fieldName, typeOf(value)));
        }
        return s;
    }

    private boolean requireBoolean(String fieldName, Object value) {
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException(
                    String.format("Field '%s' must be a boolean, got: %s", fieldName, typeOf(value)));
        }
        return b;
    }

    private Integer extractMaxIterations(Map<String, Object> frontmatter) {
        final Object value = frontmatter.get("max-iterations");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer i)) {
            throw new IllegalArgumentException(String.format(
                    "Field 'max-iterations' must be a positive integer, got: %s", value.getClass().getSimpleName()));
        }
        if (i <= 0) {
            throw new IllegalArgumentException("Field 'max-iterations' must be positive, but was: " + i);
        }
        return i;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractMetadata(Map<String, Object> frontmatter) {
        final Object value = frontmatter.get("metadata");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(
                    String.format("Field 'metadata' must be a map, got: %s", value.getClass().getSimpleName()));
        }

        final Map<String, String> result = new HashMap<>();

        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("Metadata keys must be strings, got: " + typeOf(entry.getKey()));
            }
            if (!(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException(
                        String.format("Metadata value for key '%s' must be a string, got: %s", entry.getKey(),
                                typeOf(entry.getValue())));
            }
            result.put((String) entry.getKey(), (String) entry.getValue());
        }

        return result;
    }
}
