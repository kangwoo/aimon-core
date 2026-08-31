package at.aimon.core.agent.tool.permission;

import java.util.Objects;

/**
 * Pattern for matching tools invocations against allowed patterns.
 *
 * <p>
 * Tool patterns support two matching modes:
 *
 * <ul>
 * <li><b>Wildcard</b>: Patterns ending with {@code :*} match any invocation with the prefix. Example:
 * {@code "git add:*"} matches {@code "git add ."} and {@code "git add src/"}
 * <li><b>Exact</b>: Patterns without {@code :*} require exact matching. Example: {@code "npm
 *       install"} matches only {@code "npm install"}
 * </ul>
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
 *     ToolPattern pattern = ToolPattern.of("git add:*");
 *     boolean matches1 = pattern.matches("git add ."); // true
 *     boolean matches2 = pattern.matches("git commit"); // false
 *
 *     ToolPattern exact = ToolPattern.of("npm install");
 *     boolean matches3 = exact.matches("npm install"); // true
 *     boolean matches4 = exact.matches("npm install --save"); // false
 * }
 * </pre>
 */
public final class ToolPattern {
    private final String pattern;
    private final boolean isWildcard;
    private final String prefix;

    private ToolPattern(String pattern) {
        this.pattern = Objects.requireNonNull(pattern, "Pattern cannot be null");
        this.isWildcard = pattern.endsWith(":*");
        this.prefix = isWildcard ? pattern.substring(0, pattern.length() - 2).trim() : pattern;
    }

    /**
     * Creates a new ToolPattern from the specified pattern string.
     *
     * @param pattern
     *            The pattern string (e.g., "git add:*" or "npm install")
     * @return A new ToolPattern instance
     * @throws NullPointerException
     *             if pattern is null
     */
    public static ToolPattern of(String pattern) {
        return new ToolPattern(pattern);
    }

    /**
     * Shell metacharacters that indicate command chaining, injection, or redirection attempts.
     *
     * <p>
     * Both wildcard and exact pattern matches check for these characters to prevent command injection via shell
     * metacharacters (e.g., {@code "git status; rm -rf /"}) and output redirection (e.g.,
     * {@code "git log > /tmp/leak"}).
     *
     * <p>
     * Blocked characters:
     * <ul>
     * <li>{@code ;} - command separator
     * <li>{@code |} - pipe
     * <li>{@code &} - background execution / AND operator
     * <li>{@code `} - command substitution (legacy)
     * <li>{@code $} - variable/command expansion
     * <li>{@code >} - output redirection
     * <li>{@code <} - input redirection
     * <li>{@code (} {@code )} - subshell / command grouping
     * <li>{@code \n} {@code \r} - newline injection
     * </ul>
     */
    private static final String SHELL_METACHARACTERS = ";|&`$><()\n\r";

    /**
     * Checks if the actual invocation matches this pattern.
     *
     * <p>
     * For wildcard patterns ({@code :*}), checks if the invocation starts with the prefix and the remainder does not
     * contain shell metacharacters that could indicate command injection. For exact patterns, checks if the invocation
     * exactly equals the pattern. In both modes, the actual invocation is rejected if it contains shell metacharacters
     * ({@code ; | & ` $ > < ( ) \n \r}).
     *
     * @param actualInvocation
     *            The actual tools invocation to match (must not be null)
     * @return true if the invocation matches this pattern, false otherwise
     * @throws NullPointerException
     *             if actualInvocation is null
     */
    public boolean matches(String actualInvocation) {
        Objects.requireNonNull(actualInvocation, "Actual invocation cannot be null");

        if (containsShellMetacharacters(actualInvocation)) {
            return false;
        }

        if (isWildcard) {
            return actualInvocation.startsWith(prefix) && (actualInvocation.length() == prefix.length()
                    || !Character.isLetterOrDigit(actualInvocation.charAt(prefix.length())));
        } else {
            return actualInvocation.equals(pattern);
        }
    }

    /**
     * Checks if the given string contains shell metacharacters that could be used for command injection.
     *
     * @param value
     *            The string to check
     * @return true if shell metacharacters are found, false otherwise
     */
    private static boolean containsShellMetacharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (SHELL_METACHARACTERS.indexOf(value.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the original pattern string.
     *
     * @return The pattern string (never null)
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * Returns whether this is a wildcard pattern.
     *
     * @return true if pattern ends with :*, false otherwise
     */
    public boolean isWildcard() {
        return isWildcard;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ToolPattern that = (ToolPattern) o;
        return pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern);
    }

    @Override
    public String toString() {
        return pattern;
    }
}
