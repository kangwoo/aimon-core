package at.aimon.core.skill.hook.declarative.predicate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * This is best-effort whitelisting — callers that need defense-in-depth should layer additional guards (e.g.
 * tool-level permission rules).
 *
 * <h2>Text that does not pair up</h2>
 *
 * <p>
 * A quote, backtick or {@code $(} with no partner is read as the character it is, and splitting goes on. It is common
 * and harmless to a shell &mdash; the apostrophe of {@code echo "it's"}, which is no quote inside double quotes, or
 * one in a comment or a here-document &mdash; and giving up on the whole command there would hide every command after
 * it from the matcher. The unsplit command is kept as a sub-command of its own, so nothing that matched as a whole
 * stops matching.
 *
 * <p>
 * Comments and here-documents are read <em>as well</em>, not instead. An apostrophe in a comment or in the body of a
 * here-document is text to a shell; here it pairs up with the next one and quotes the lines between them. So a
 * command with a {@code #} or a {@code <<} is split a second time, with the quotes and substitutions left unread in
 * each comment ({@code #} at the start of a word, to the end of the line) and in each here-document body (the lines
 * after a {@code <<WORD} up to the line that is {@code WORD}), and the pieces of both readings are matched.
 * Substituting the second reading for the first would be wrong wherever this takes for a comment or a document what a
 * shell does not &mdash; a {@code #} inside double quotes, a {@code <<} that is a shift &mdash; because a
 * {@code $(...)} there, which the shell runs, would go unread.
 *
 * <h2>Commands that are not split</h2>
 *
 * <p>
 * The command is text the model wrote, and the splitter recurses into every quoted block and substitution, so two
 * limits bound what it is asked to do: a command longer than {@link #MAX_SPLIT_LENGTH} characters, one nested
 * deeper than {@link #MAX_NESTING_DEPTH} levels, or one that leaves more than {@link #MAX_UNPAIRED} quotes or
 * substitutions open, is not split at all. For such a command the predicate answers
 * {@code true} whatever the pattern. It cannot tell that no sub-command matches, and a matcher only decides whether a
 * hook is asked &mdash; so the hook is asked, with the whole command as its input. Answering {@code false} would let a
 * command step around every {@code Bash(...)} matcher by being nested one level too deep.
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

    private static final Logger log = LoggerFactory.getLogger(BashSubcommandPredicate.class);

    /**
     * How many levels of quoting and substitution the splitter follows. Each level is a stack frame and a copy of what
     * is left of the command, so without a limit the depth of the stack is chosen by whoever wrote the command.
     */
    static final int MAX_NESTING_DEPTH = 64;

    /**
     * The longest command the splitter takes apart, in characters. Twice what the {@code Bash} tool agrees to run, so
     * every command that can run is split; the predicate is asked before the tool has checked its own limit.
     */
    static final int MAX_SPLIT_LENGTH = 65_536;

    /** How many quotes, backticks and {@code $(} with no partner the splitter reads past in one command. */
    static final int MAX_UNPAIRED = 64;

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
        final List<String> subcommands;
        try {
            subcommands = splitSubcommands(command);
        } catch (UnsplittableCommand unsplittable) {
            // The command is not logged: it is as long as its author liked.
            log.warn("Bash command not split ({}, {} characters); treating it as matching Bash({})",
                    unsplittable.getMessage(), command.length(), pattern);
            return true;
        }
        for (String sub : subcommands) {
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
     * @throws UnsplittableCommand
     *             if the command is longer than {@link #MAX_SPLIT_LENGTH}, nested deeper than
     *             {@link #MAX_NESTING_DEPTH}, or leaves more than {@link #MAX_UNPAIRED} quotes or substitutions open
     */
    static List<String> splitSubcommands(String command) {
        Objects.requireNonNull(command, "Command cannot be null");
        if (command.length() > MAX_SPLIT_LENGTH) {
            throw new UnsplittableCommand("longer than " + MAX_SPLIT_LENGTH + " characters");
        }
        final List<String> result = new ArrayList<>();
        // Not paired up somewhere: the pieces are a guess, so the command as written is matched as well.
        final int[] unpaired = {0};
        tokenize(command, result, 0, unpaired, false);
        if (command.indexOf('#') >= 0 || command.contains("<<")) {
            // Added to the first reading, never in place of it: see the class comment.
            final int[] unpairedWithComments = {0};
            final List<String> withComments = new ArrayList<>();
            tokenize(command, withComments, 0, unpairedWithComments, true);
            result.addAll(withComments);
        }
        if (unpaired[0] > 0 || result.isEmpty()) {
            result.add(command);
        }
        return result;
    }

    /**
     * Tokenizes the command string into top-level sub-commands and the inner commands of any backtick / {@code $()}
     * substitutions encountered. {@code depth} is how many blocks enclose {@code command}, 0 for the command itself.
     * {@code unpaired} counts, across all levels, the quotes, backticks and {@code $(} that had no partner and were
     * read as plain characters. With {@code readComments}, the quotes and substitutions of a comment and of a
     * here-document body are plain characters too.
     */
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NestedIfDepth", "checkstyle:NPathComplexity",
            "checkstyle:MethodLength"})
    private static void tokenize(String command, List<String> out, int depth, int[] unpaired, boolean readComments) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new UnsplittableCommand("nested deeper than " + MAX_NESTING_DEPTH + " levels");
        }
        final StringBuilder current = new StringBuilder();
        final int len = command.length();
        boolean inComment = false;
        boolean inHereDocument = false;
        final HereDocuments hereDocuments = readComments ? new HereDocuments() : null;
        int i = 0;
        while (i < len) {
            final char c = command.charAt(i);
            if (c == '\n') {
                inComment = false;
                inHereDocument = hereDocuments != null && hereDocuments.isBodyLine(command, i + 1);
            } else if (readComments && !inHereDocument && c == '#' && startsWord(command, i)) {
                inComment = true;
            } else if (readComments && !inHereDocument && !inComment && c == '<') {
                hereDocuments.openIfOperator(command, i);
            }
            if ((inComment || inHereDocument) && c != ';' && c != '|' && c != '&' && c != '\n') {
                // Quotes and substitutions in a comment or a document are text. Operators are left to the branches
                // below, so this reading splits wherever the plain one does.
                current.append(c);
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < len) {
                current.append(c).append(command.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '\'') {
                final int end = command.indexOf('\'', i + 1);
                if (end >= 0) {
                    current.append(command, i, end + 1);
                    i = end + 1;
                    continue;
                }
                countUnpaired(unpaired);
            } else if (c == '"') {
                final int end = findMatchingDoubleQuote(command, i + 1);
                if (end >= 0) {
                    // Add the verbatim quoted block to the current segment, but also recurse into its body so that
                    // patterns like `bash -c "git push"` can match the inner `git push` as a sub-command.
                    current.append(command, i, end + 1);
                    tokenize(command.substring(i + 1, end), out, depth + 1, unpaired, readComments);
                    i = end + 1;
                    continue;
                }
                countUnpaired(unpaired);
            } else if (c == '`') {
                final int end = command.indexOf('`', i + 1);
                if (end >= 0) {
                    current.append(command, i, end + 1);
                    tokenize(command.substring(i + 1, end), out, depth + 1, unpaired, readComments);
                    i = end + 1;
                    continue;
                }
                countUnpaired(unpaired);
            } else if (c == '$' && i + 1 < len && command.charAt(i + 1) == '(') {
                final int end = findMatchingParen(command, i + 2);
                if (end >= 0) {
                    current.append(command, i, end + 1);
                    tokenize(command.substring(i + 2, end), out, depth + 1, unpaired, readComments);
                    i = end + 1;
                    continue;
                }
                countUnpaired(unpaired);
            }
            // An opener with no partner falls through here and is read as the character it is.
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

    /**
     * Each opener without a partner cost a scan to the end of the command to find that out, so their number is
     * bounded like the depth is.
     */
    private static void countUnpaired(int[] unpaired) {
        if (++unpaired[0] > MAX_UNPAIRED) {
            throw new UnsplittableCommand("more than " + MAX_UNPAIRED + " quotes or substitutions left open");
        }
    }

    /** Whether the character at {@code i} begins a word: the only place a {@code #} starts a comment. */
    private static boolean startsWord(String command, int i) {
        if (i == 0) {
            return true;
        }
        final char before = command.charAt(i - 1);
        return Character.isWhitespace(before) || before == ';' || before == '|' || before == '&';
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

    /**
     * Which lines of a command are the body of a here-document: those after a line with {@code <<WORD}, up to and
     * including the line that is {@code WORD}. Several documents opened on one line follow one another.
     */
    private static final class HereDocuments {

        private final Deque<String> delimiters = new ArrayDeque<>();
        private final Deque<Boolean> tabsStripped = new ArrayDeque<>();
        private boolean inBody;
        private boolean bodyEndsWithThisLine;

        /** Notes a here-document when {@code <<} at {@code at} opens one; {@code <<<} is a here-string. */
        void openIfOperator(String command, int at) {
            final int len = command.length();
            if (at + 1 >= len || command.charAt(at + 1) != '<' || (at > 0 && command.charAt(at - 1) == '<')
                    || (at + 2 < len && command.charAt(at + 2) == '<')) {
                return;
            }
            int i = at + 2;
            final boolean strip = i < len && command.charAt(i) == '-';
            if (strip) {
                i++;
            }
            while (i < len && (command.charAt(i) == ' ' || command.charAt(i) == '\t')) {
                i++;
            }
            final StringBuilder word = new StringBuilder();
            while (i < len && !Character.isWhitespace(command.charAt(i)) && ";|&<>()".indexOf(command.charAt(i)) < 0) {
                final char c = command.charAt(i++);
                if (c != '\'' && c != '"' && c != '\\') {
                    word.append(c);
                }
            }
            if (word.length() > 0) {
                delimiters.add(word.toString());
                tabsStripped.add(strip);
            }
        }

        /** Whether the line starting at {@code lineStart} belongs to a document's body (its last line included). */
        boolean isBodyLine(String command, int lineStart) {
            if (bodyEndsWithThisLine) {
                bodyEndsWithThisLine = false;
                inBody = false;
                delimiters.poll();
                tabsStripped.poll();
            }
            if (!inBody && delimiters.isEmpty()) {
                return false;
            }
            inBody = true;
            int end = command.indexOf('\n', lineStart);
            if (end < 0) {
                end = command.length();
            }
            int start = lineStart;
            if (Boolean.TRUE.equals(tabsStripped.peek())) {
                while (start < end && command.charAt(start) == '\t') {
                    start++;
                }
            }
            bodyEndsWithThisLine = command.regionMatches(start, delimiters.peek(), 0, end - start)
                    && delimiters.peek().length() == end - start;
            return true;
        }
    }

    /** Signals a command the splitter declines to take apart; the message names the limit it is over. */
    static final class UnsplittableCommand extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnsplittableCommand(String limit) {
            super(limit, null, false, false);
        }
    }
}
