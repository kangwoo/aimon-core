package at.aimon.core.agent.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the programmatic tool calling keys — {@code programmatic-tools}, {@code programmatic-only},
 * {@code programmatic-engine} and {@code shell-engine-tool-scope} — out of a definition's frontmatter
 * ({@code docs/design/tool/programmatic-tool-calling.md} §7.2). Agent, subagent and skill definitions take the same
 * four keys, so they share one reading of them.
 *
 * <p>
 * The keys are kebab-case like {@code allowed-tools}, and for the same reason the camelCase spellings are refused by
 * name rather than ignored. What can be checked without the tools — the exact-name form and
 * {@code programmatic-only ⊆ programmatic-tools} — is refused here, at load time.
 *
 * <p>
 * Errors are {@link IllegalArgumentException}s whose message is fit to show as is; each parser wraps it in its own
 * parse exception, so this package does not depend on any parser's exception type.
 */
public final class ProgrammaticFrontmatter {

    private ProgrammaticFrontmatter() {
    }

    /**
     * Reads the four keys from {@code frontmatter}.
     *
     * @param frontmatter
     *            the parsed frontmatter (must not be null)
     * @return the settings (never null; {@link ProgrammaticToolSettings#none()} without {@code programmatic-tools})
     * @throws IllegalArgumentException
     *             if a key is spelled in camelCase, a value has the wrong shape, a key is set without
     *             {@code programmatic-tools}, or the settings are inconsistent
     */
    public static ProgrammaticToolSettings parse(Map<String, Object> frontmatter) {
        Objects.requireNonNull(frontmatter, "frontmatter cannot be null");
        for (String camel : List.of("programmaticTools", "programmaticOnly", "programmaticEngine",
                "shellEngineToolScope")) {
            if (frontmatter.containsKey(camel)) {
                throw new IllegalArgumentException("Unknown key '" + camel
                        + "'. The programmatic tool calling keys are spelled in kebab-case, like 'allowed-tools'.");
            }
        }
        final List<String> tools = nameList(frontmatter, "programmatic-tools");
        final List<String> codeOnly = nameList(frontmatter, "programmatic-only");
        final String engine = string(frontmatter, "programmatic-engine");
        final String scope = string(frontmatter, "shell-engine-tool-scope");
        if (tools.isEmpty()) {
            if (!codeOnly.isEmpty() || engine != null || scope != null) {
                throw new IllegalArgumentException("'programmatic-only', 'programmatic-engine' and "
                        + "'shell-engine-tool-scope' need 'programmatic-tools'");
            }
            return ProgrammaticToolSettings.none();
        }
        try {
            final ProgrammaticToolSettings.Builder builder = ProgrammaticToolSettings.builder().tools(tools)
                    .codeOnly(codeOnly);
            if (engine != null) {
                builder.engine(ProgrammaticEngineChoice.parse(engine));
            }
            if (scope != null) {
                builder.shellEngineToolScope(ShellEngineToolScope.parse(scope));
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid programmatic tool settings: " + e.getMessage(), e);
        }
    }

    /** A string value, or null when the key is absent. */
    private static String string(Map<String, Object> frontmatter, String key) {
        final Object raw = frontmatter.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException(
                    "Invalid '" + key + "' value: expected a string, got " + raw.getClass().getName());
        }
        return text;
    }

    /** A YAML list or a comma-separated string of names; empty when the key is absent. */
    private static List<String> nameList(Map<String, Object> frontmatter, String key) {
        final Object raw = frontmatter.get(key);
        if (raw == null) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        if (raw instanceof String text) {
            Arrays.stream(text.split(",")).map(String::trim).filter(name -> !name.isEmpty()).forEach(names::add);
        } else if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof String text)) {
                    throw new IllegalArgumentException("Invalid '" + key + "' entry: expected a string, got "
                            + (element == null ? "null" : element.getClass().getName()));
                }
                if (!text.isBlank()) {
                    names.add(text.trim());
                }
            }
        } else {
            throw new IllegalArgumentException("Invalid '" + key
                    + "' value: expected a YAML list or a comma-separated string, got " + raw.getClass().getName());
        }
        return names;
    }
}
