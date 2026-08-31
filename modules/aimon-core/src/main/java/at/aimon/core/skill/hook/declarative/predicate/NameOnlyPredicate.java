package at.aimon.core.skill.hook.declarative.predicate;

import java.util.Objects;
import java.util.regex.Pattern;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

/**
 * {@link ToolInputPredicate} that matches purely on the tool name.
 *
 * <p>
 * Three forms are supported:
 * <ul>
 * <li>{@link #ANY} — matches any tool name. Produced from the bare wildcard pattern {@code "*"}, an empty pattern,
 * a blank pattern, or a {@code null} pattern.
 * <li>Exact-name predicate — produced from a non-blank pattern containing no {@code *}; matches only when the tool
 * name compares equal.
 * <li>Glob predicate — produced from a pattern that contains one or more {@code *} characters mixed with literal
 * text. {@code *} expands to "zero or more arbitrary characters"; every other character matches itself literally
 * (including regex metacharacters such as {@code .} or {@code (}). Examples: {@code "Read*"} matches {@code Read},
 * {@code ReadTool}, {@code Readme}; {@code "*Tool"} matches {@code Tool}, {@code BashTool}.
 * </ul>
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class NameOnlyPredicate implements ToolInputPredicate {

    /** Singleton predicate that accepts every tool name. */
    public static final NameOnlyPredicate ANY = new NameOnlyPredicate(null, null);

    private final String pattern;
    private final Pattern compiledGlob;

    private NameOnlyPredicate(String pattern, Pattern compiledGlob) {
        this.pattern = pattern;
        this.compiledGlob = compiledGlob;
    }

    /**
     * Parses a name-only matcher pattern.
     *
     * @param pattern
     *            The raw pattern. {@code null}, blank, or {@code "*"} all resolve to {@link #ANY}. Patterns containing
     *            {@code *} other than the bare wildcard compile to a glob predicate (see class Javadoc); any other
     *            value is treated as a literal tool name.
     * @return The predicate (never null)
     */
    public static NameOnlyPredicate of(String pattern) {
        if (pattern == null || pattern.isBlank() || "*".equals(pattern)) {
            return ANY;
        }
        if (pattern.indexOf('*') >= 0) {
            return new NameOnlyPredicate(pattern, compileGlob(pattern));
        }
        return new NameOnlyPredicate(pattern, null);
    }

    @Override
    public boolean test(String toolName, ToolInput input) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        // input may be ignored — name-only predicates do not consult tool arguments.
        if (pattern == null) {
            return true;
        }
        if (compiledGlob != null) {
            return compiledGlob.matcher(toolName).matches();
        }
        return pattern.equals(toolName);
    }

    /**
     * Returns the original pattern, or {@code "*"} for {@link #ANY}. Intended for diagnostics / logging only.
     *
     * @return The pattern string (never null)
     */
    public String getPattern() {
        return pattern == null ? "*" : pattern;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NameOnlyPredicate that)) {
            return false;
        }
        return Objects.equals(pattern, that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(pattern);
    }

    @Override
    public String toString() {
        return "NameOnlyPredicate{" + getPattern() + '}';
    }

    /**
     * Compiles a tool-name glob (where {@code *} means "zero or more arbitrary characters") into a regex
     * {@link Pattern}. Visible for reuse from sibling predicates (e.g. {@code PathGlobPredicate}).
     *
     * @param glob
     *            The glob pattern (must not be null)
     * @return The compiled {@link Pattern}, anchored implicitly via {@link java.util.regex.Matcher#matches()}
     */
    public static Pattern compileGlob(String glob) {
        Objects.requireNonNull(glob, "Glob cannot be null");
        final StringBuilder regex = new StringBuilder(glob.length() + 8);
        int literalStart = 0;
        for (int i = 0; i < glob.length(); i++) {
            if (glob.charAt(i) == '*') {
                if (i > literalStart) {
                    regex.append(Pattern.quote(glob.substring(literalStart, i)));
                }
                regex.append(".*");
                literalStart = i + 1;
            }
        }
        if (literalStart < glob.length()) {
            regex.append(Pattern.quote(glob.substring(literalStart)));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
