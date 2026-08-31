package at.aimon.core.skill.hook.declarative.predicate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

/**
 * {@link ToolInputPredicate} that matches a path / file_path argument of a tool against a glob pattern (AIMON
 * extension).
 *
 * <p>
 * Use this predicate to encode patterns like {@code Edit(*.ts)}, {@code Write(.env)} or {@code Read(secrets.*)}. The
 * predicate looks up the path-shaped argument for the bound tool, applies a NameOnly-style glob to it and returns
 * {@code true} on match.
 *
 * <h2>Tool → field mapping</h2>
 *
 * <p>
 * Different tools store their path-shaped argument under different keys. The predicate ships with a built-in lookup
 * table covering the canonical core-tool names; unknown tools fall back to probing the conventional fields
 * {@code file_path}, {@code path} and {@code pattern} in order.
 *
 * <table>
 * <caption>Built-in path field lookup</caption>
 * <tr>
 * <th>Tool</th>
 * <th>Path field(s)</th>
 * </tr>
 * <tr>
 * <td>{@code Read}, {@code Edit}, {@code Write}, {@code MultiEdit}</td>
 * <td>{@code file_path}</td>
 * </tr>
 * <tr>
 * <td>{@code Glob}</td>
 * <td>{@code pattern}, {@code path}</td>
 * </tr>
 * <tr>
 * <td>{@code Grep}</td>
 * <td>{@code path}, {@code pattern}</td>
 * </tr>
 * <tr>
 * <td>{@code LS}</td>
 * <td>{@code path}</td>
 * </tr>
 * <tr>
 * <td>{@code NotebookEdit}</td>
 * <td>{@code notebook_path}</td>
 * </tr>
 * </table>
 *
 * <h2>Glob grammar</h2>
 *
 * <p>
 * The glob follows the same rules as {@link NameOnlyPredicate}: {@code *} expands to "zero or more arbitrary
 * characters", every other character matches itself literally. The pattern is matched against the raw path string
 * via {@link Pattern#matches}; supply patterns with leading wildcards (e.g. {@code "*.ts"}) when the path may contain
 * directory components.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class PathGlobPredicate implements ToolInputPredicate {

    private static final Map<String, List<String>> TOOL_PATH_FIELDS = Map.of("Read", List.of("file_path"), "Edit",
            List.of("file_path"), "Write", List.of("file_path"), "MultiEdit", List.of("file_path"), "Glob",
            List.of("pattern", "path"), "Grep", List.of("path", "pattern"), "LS", List.of("path"), "NotebookEdit",
            List.of("notebook_path"));

    private static final List<String> FALLBACK_PATH_FIELDS = List.of("file_path", "path", "pattern");

    private final String boundToolName;
    private final String pattern;
    private final Pattern compiledGlob;

    private PathGlobPredicate(String boundToolName, String pattern, Pattern compiledGlob) {
        this.boundToolName = boundToolName;
        this.pattern = pattern;
        this.compiledGlob = compiledGlob;
    }

    /**
     * Builds a predicate bound to a specific tool.
     *
     * @param boundToolName
     *            The tool whose input is consulted (must not be null or blank). Examples: {@code "Edit"},
     *            {@code "Write"}, {@code "Read"}.
     * @param pattern
     *            The path glob (must not be null or blank). Examples: {@code "*.ts"}, {@code ".env"},
     *            {@code "secrets.*"}.
     * @return The predicate (never null)
     */
    public static PathGlobPredicate of(String boundToolName, String pattern) {
        Objects.requireNonNull(boundToolName, "Tool name cannot be null");
        Objects.requireNonNull(pattern, "Pattern cannot be null");
        if (boundToolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be blank");
        }
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("Pattern cannot be blank");
        }
        return new PathGlobPredicate(boundToolName, pattern, NameOnlyPredicate.compileGlob(pattern));
    }

    @Override
    public boolean test(String toolName, ToolInput input) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        Objects.requireNonNull(input, "Input cannot be null");
        if (!boundToolName.equals(toolName)) {
            return false;
        }
        for (String field : pathFieldsFor(toolName)) {
            final Object raw = input.get(field);
            if (raw instanceof String s && !s.isEmpty() && compiledGlob.matcher(s).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the bound tool name. Intended for diagnostics / logging only.
     *
     * @return The tool name (never null)
     */
    public String getBoundToolName() {
        return boundToolName;
    }

    /**
     * Returns the configured glob pattern. Intended for diagnostics / logging only.
     *
     * @return The pattern (never null)
     */
    public String getPattern() {
        return pattern;
    }

    private static List<String> pathFieldsFor(String toolName) {
        return TOOL_PATH_FIELDS.getOrDefault(toolName, FALLBACK_PATH_FIELDS);
    }

    @Override
    public String toString() {
        return "PathGlobPredicate{" + boundToolName + "(" + pattern + ")}";
    }
}
