package at.aimon.core.skill.hook.declarative.predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

/**
 * {@link ToolInputPredicate} that fires when any sub-command inside a Bash invocation matches a glob pattern (AIMON
 * extension).
 *
 * <p>
 * Use this predicate to encode patterns like {@code Bash(git *)}, {@code Bash(npm install)} or
 * {@code Bash(rm -rf*)}. The predicate inspects the {@code command} argument of the {@code Bash} tool, splits the
 * command string into best-effort sub-commands, and returns {@code true} when any one of them matches the configured
 * glob.
 *
 * <h2>Sub-command splitting</h2>
 *
 * <p>
 * The tokenizer is a deliberately small state machine (no full shell parser) that handles the constructs that show up
 * in the vast majority of agent-issued Bash invocations:
 * <ul>
 * <li>Logical operators {@code &&}, {@code ||}, {@code ;}, {@code |} split the command into separate sub-commands.
 * <li>Backtick command substitutions {@code `cmd`} and {@code $(cmd)} contribute their inner command as another
 * sub-command (the substitution wrapper itself is also retained).
 * <li>Single-quoted, double-quoted and backslash-escaped sequences are passed through without being split — quotes
 * suppress operator detection so {@code bash -c "git push && rm -rf"} yields the inner string as a sub-command.
 * </ul>
 *
 * <p>
 * If the command cannot be tokenized cleanly (e.g. an unterminated quote), the predicate falls back to matching the
 * untokenized command as a single sub-command. This is best-effort whitelisting — callers that need defense-in-depth
 * should layer additional guards (e.g. tool-level permission rules).
 *
 * <h2>Glob grammar</h2>
 *
 * <p>
 * The glob follows the same rules as {@link NameOnlyPredicate}: {@code *} expands to "zero or more arbitrary
 * characters", every other character matches itself literally. The pattern is matched against the full sub-command
 * string after leading/trailing whitespace is trimmed.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class BashSubcommandPredicate implements ToolInputPredicate {

    /** Tool name handled by this predicate. */
    public static final String BASH_TOOL_NAME = "Bash";
    /** Conventional input field carrying the shell command. */
    public static final String COMMAND_FIELD = "command";

    private final String pattern;
    private final Pattern compiledGlob;

    private BashSubcommandPredicate(String pattern, Pattern compiledGlob) {
        this.pattern = pattern;
        this.compiledGlob = compiledGlob;
    }

    /**
     * Builds a predicate from a sub-command glob pattern.
     *
     * @param pattern
     *            The glob (must not be null or blank). Examples: {@code "git *"}, {@code "npm install"},
     *            {@code "rm -rf*"}.
     * @return The predicate (never null)
     * @throws NullPointerException
     *             if pattern is null
     * @throws IllegalArgumentException
     *             if pattern is blank
     */
    public static BashSubcommandPredicate of(String pattern) {
        Objects.requireNonNull(pattern, "Pattern cannot be null");
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("Pattern cannot be blank");
        }
        return new BashSubcommandPredicate(pattern, NameOnlyPredicate.compileGlob(pattern));
    }

    @Override
    public boolean test(String toolName, ToolInput input) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        Objects.requireNonNull(input, "Input cannot be null");
        if (!BASH_TOOL_NAME.equals(toolName)) {
            return false;
        }
        final Object raw = input.get(COMMAND_FIELD);
        if (!(raw instanceof String command) || command.isBlank()) {
            return false;
        }
        for (String sub : splitSubcommands(command)) {
            final String trimmed = sub.strip();
            if (!trimmed.isEmpty() && compiledGlob.matcher(trimmed).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the configured glob pattern. Intended for diagnostics / logging only.
     *
     * @return The pattern (never null)
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * Best-effort sub-command splitter. Visible (package-private) for direct unit testing.
     *
     * @param command
     *            The raw shell command (must not be null)
     * @return List of sub-command segments (never null, never empty)
     */
    static List<String> splitSubcommands(String command) {
        Objects.requireNonNull(command, "Command cannot be null");
        final List<String> result = new ArrayList<>();
        try {
            tokenize(command, result);
        } catch (TokenizeFailure failure) {
            result.clear();
            result.add(command);
            return result;
        }
        if (result.isEmpty()) {
            result.add(command);
        }
        return result;
    }

    /**
     * Tokenizes the command string into top-level sub-commands and the inner commands of any backtick / {@code $()}
     * substitutions encountered.
     */
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NestedIfDepth"})
    private static void tokenize(String command, List<String> out) {
        final StringBuilder current = new StringBuilder();
        final int len = command.length();
        int i = 0;
        while (i < len) {
            final char c = command.charAt(i);
            if (c == '\\' && i + 1 < len) {
                current.append(c).append(command.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '\'') {
                final int end = command.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new TokenizeFailure();
                }
                current.append(command, i, end + 1);
                i = end + 1;
                continue;
            }
            if (c == '"') {
                final int end = findMatchingDoubleQuote(command, i + 1);
                if (end < 0) {
                    throw new TokenizeFailure();
                }
                // Add the verbatim quoted block to the current segment, but also recurse into its body so that
                // patterns like `bash -c "git push"` can match the inner `git push` as a sub-command.
                current.append(command, i, end + 1);
                tokenize(command.substring(i + 1, end), out);
                i = end + 1;
                continue;
            }
            if (c == '`') {
                final int end = command.indexOf('`', i + 1);
                if (end < 0) {
                    throw new TokenizeFailure();
                }
                current.append(command, i, end + 1);
                tokenize(command.substring(i + 1, end), out);
                i = end + 1;
                continue;
            }
            if (c == '$' && i + 1 < len && command.charAt(i + 1) == '(') {
                final int end = findMatchingParen(command, i + 2);
                if (end < 0) {
                    throw new TokenizeFailure();
                }
                current.append(command, i, end + 1);
                tokenize(command.substring(i + 2, end), out);
                i = end + 1;
                continue;
            }
            // Logical / pipe / sequencing operators split sub-commands.
            if (c == '&' && i + 1 < len && command.charAt(i + 1) == '&') {
                flushSegment(current, out);
                i += 2;
                continue;
            }
            if (c == '|' && i + 1 < len && command.charAt(i + 1) == '|') {
                flushSegment(current, out);
                i += 2;
                continue;
            }
            if (c == ';' || c == '|' || c == '\n') {
                flushSegment(current, out);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        flushSegment(current, out);
    }

    private static void flushSegment(StringBuilder current, List<String> out) {
        if (current.length() == 0) {
            return;
        }
        out.add(current.toString());
        current.setLength(0);
    }

    private static int findMatchingDoubleQuote(String s, int from) {
        int i = from;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static int findMatchingParen(String s, int from) {
        int depth = 1;
        int i = from;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i += 2;
                continue;
            }
            if (c == '\'') {
                final int end = s.indexOf('\'', i + 1);
                if (end < 0) {
                    return -1;
                }
                i = end + 1;
                continue;
            }
            if (c == '"') {
                final int end = findMatchingDoubleQuote(s, i + 1);
                if (end < 0) {
                    return -1;
                }
                i = end + 1;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    @Override
    public String toString() {
        return "BashSubcommandPredicate{Bash(" + pattern + ")}";
    }

    /** Internal sentinel signalling a tokenization failure (unterminated quote / paren). */
    private static final class TokenizeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TokenizeFailure() {
            super(null, null, false, false);
        }
    }
}
