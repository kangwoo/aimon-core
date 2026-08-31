package at.aimon.core.agent.tool.permission;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import at.aimon.core.agent.tool.exception.InvalidToolSpecException;

/**
 * Immutable representation of a tools permission with optional pattern matching.
 *
 * <p>
 * An allowed tools specifies which tools can be used during command execution, optionally with pattern restrictions for
 * fine-grained control.
 *
 * <p>
 * Supports two formats:
 *
 * <ul>
 * <li><b>Simple tools</b>: {@code "Read"}, {@code "Edit"}, {@code "Grep"}
 * <li><b>Tool with pattern</b>: {@code "Bash(git add:*)"}, {@code "Bash(npm install)"}, {@code "Read(/tmp/**)"}
 * </ul>
 *
 * <p>
 * The spec does not say what kind of value its pattern is meant to match, and it cannot: the parser splits on
 * parentheses and never inspects the body. So a pattern is compiled <b>both</b> ways — as a {@link ToolPattern} for
 * command subjects and as a {@link PathPattern} for path subjects — and the matcher is chosen when a tool presents a
 * {@link PermissionSubject}, whose {@link PermissionSubject.Kind} names the one that applies.
 *
 * <p>
 * Immutable and thread-safe value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Parse simple tools
 *     AllowedTool read = AllowedTool.parse("Read");
 *     // read.getToolName() == "Read"
 *     // read.hasPattern() == false
 *
 *     // Parse tools with wildcard pattern
 *     AllowedTool bash = AllowedTool.parse("Bash(git add:*)");
 *     // bash.getToolName() == "Bash"
 *     // bash.getPattern().get().matches("git add .") == true
 *
 *     // Parse tools with exact pattern
 *     AllowedTool npm = AllowedTool.parse("Bash(npm install)");
 *     // npm.getPattern().get().matches("npm install") == true
 *     // npm.getPattern().get().matches("npm install --save") == false
 * }
 * </pre>
 */
public final class AllowedTool {
    private final String toolName;
    private final Optional<ToolPattern> pattern;
    private final Optional<PathPattern> pathPattern;

    private AllowedTool(String toolName, ToolPattern pattern) {
        this.toolName = Objects.requireNonNull(toolName, "Tool name cannot be null");
        this.pattern = Optional.ofNullable(pattern);
        this.pathPattern = this.pattern.map(ToolPattern::getPattern).flatMap(PathPattern::tryCompile);
        validateToolName(toolName);
    }

    /**
     * Parses an allowed tools specification from a frontmatter string.
     *
     * <p>
     * Supported formats:
     *
     * <ul>
     * <li>{@code "Read"} - Simple tools without pattern
     * <li>{@code "Bash(git add:*)"} - Tool with wildcard pattern
     * <li>{@code "Bash(npm install)"} - Tool with exact pattern
     * </ul>
     *
     * <p>
     * The pattern body runs from the <b>first</b> {@code (} to the <b>last</b> {@code )}, so a pattern may contain
     * parentheses of its own — {@code Read(/tmp/report(1)/**)} is a path pattern with a bracketed directory name, not a
     * malformed spec. Closing on the first {@code )} instead would truncate it silently, which is worse than either
     * accepting or rejecting it.
     *
     * @param spec
     *            The tools specification string (must not be null)
     * @return A new AllowedTool instance
     * @throws NullPointerException
     *             if spec is null
     * @throws InvalidToolSpecException
     *             if spec is malformed
     */
    public static AllowedTool parse(String spec) {
        Objects.requireNonNull(spec, "Tool spec cannot be null");

        int openParen = spec.indexOf('(');
        if (openParen == -1) {
            if (spec.indexOf(')') != -1) {
                throw new InvalidToolSpecException("Malformed tool spec: closing parenthesis without opening: " + spec);
            }
            // Simple tools: "Read"
            return new AllowedTool(spec.trim(), null);
        }

        // Tool with pattern: "Bash(git add:*)"
        String toolName = spec.substring(0, openParen).trim();
        int closeParen = spec.lastIndexOf(')');
        if (closeParen < openParen) {
            throw new InvalidToolSpecException("Malformed tool spec: missing closing parenthesis: " + spec);
        }
        if (!spec.substring(closeParen + 1).isBlank()) {
            throw new InvalidToolSpecException(
                    "Malformed tool spec: trailing characters after closing parenthesis: " + spec);
        }

        String patternStr = spec.substring(openParen + 1, closeParen).trim();
        if (patternStr.isEmpty()) {
            throw new InvalidToolSpecException("Malformed tool spec: empty pattern: " + spec);
        }

        ToolPattern pattern = ToolPattern.of(patternStr);

        return new AllowedTool(toolName, pattern);
    }

    private void validateToolName(String name) {
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool name cannot be empty");
        }
    }

    /**
     * Returns the tools name.
     *
     * @return The tools name (e.g., "Bash", "Read", "Edit")
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Returns the pattern associated with this tools, if any.
     *
     * @return An Optional containing the pattern, or empty if no pattern specified
     */
    public Optional<ToolPattern> getPattern() {
        return pattern;
    }

    /**
     * Returns the same pattern compiled as a path glob, for judging {@link PermissionSubject.Kind#PATH} subjects.
     *
     * <p>
     * A spec does not say which kind it is — {@code Bash(git:*)} and {@code Read(/tmp/**)} are the same shape to
     * {@link #parse} — so the pattern is speculatively compiled both ways and the matcher is chosen at judgement time
     * by the subject's kind. This one is empty when the pattern is not valid glob syntax (a command pattern such as
     * {@code echo &#123;a}, typically); an empty matcher never matches, so a tool presenting a path subject against
     * such a spec is denied.
     *
     * @return An Optional containing the path matcher, or empty if there is no pattern or it is not a valid glob
     */
    public Optional<PathPattern> getPathPattern() {
        return pathPattern;
    }

    /**
     * Returns whether this tools has a pattern restriction.
     *
     * @return true if a pattern is specified, false otherwise
     */
    public boolean hasPattern() {
        return pattern.isPresent();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AllowedTool that = (AllowedTool) o;
        return toolName.equals(that.toolName) && pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolName, pattern);
    }

    /**
     * Formats a list of allowed tools into a human-readable string.
     *
     * <p>
     * This utility method creates a comma-separated string representation of allowed tools. Tools with patterns include
     * the pattern in parentheses. If the list is empty, returns a special message indicating no restrictions.
     *
     * <p>
     * <b>Example outputs:</b>
     *
     * <ul>
     * <li>Empty list: {@code "all tools (no restrictions)"}
     * <li>Simple tools: {@code "Read, Write, Grep"}
     * <li>With patterns: {@code "Read, Bash(git:*), Write"}
     * </ul>
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * {
     *     &#64;code
     *     List<AllowedTool> tools = List.of(AllowedTool.parse("Read"), AllowedTool.parse("Bash(git:*)"),
     *             AllowedTool.parse("Grep"));
     *
     *     String formatted = AllowedTool.formatList(tools);
     *     // Result: "Read, Bash(git:*), Grep"
     *
     *     List<AllowedTool> empty = List.of();
     *     String emptyFormatted = AllowedTool.formatList(empty);
     *     // Result: "all tools (no restrictions)"
     * }
     * </pre>
     *
     * @param allowedTools
     *            List of allowed tools to format (must not be null)
     * @return Formatted string representation of the allowed tools list
     * @throws NullPointerException
     *             if allowedTools is null
     */
    public static String formatList(List<AllowedTool> allowedTools) {
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");

        if (allowedTools.isEmpty()) {
            return "all tools (no restrictions)";
        }

        return allowedTools.stream().map(AllowedTool::toString).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        if (hasPattern()) {
            return toolName + "(" + pattern.get() + ")";
        }
        return toolName;
    }
}
