package at.aimon.core.skill.hook.declarative.predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

/**
 * Parses Claude-Code-style {@code if}-expression matcher strings into {@link ToolInputPredicate}s (AIMON extension).
 *
 * <h2>Grammar</h2>
 *
 * <pre>
 * expression := term ( "|" term )*
 * term       := tool-name [ "(" pattern ")" ]
 * tool-name  := one or more of [A-Za-z0-9_.-] and "*" (e.g. Bash, Read, mcp__github__*, *)
 * pattern    := free-form glob, may contain spaces; opening "(" is consumed up to the
 *               matching ")".
 * </pre>
 *
 * <h2>Term semantics</h2>
 *
 * <ul>
 * <li>{@code Read} → {@link NameOnlyPredicate} matching the bare tool name.
 * <li>{@code *} → {@link NameOnlyPredicate#ANY}.
 * <li>{@code Bash(<glob>)} → {@link BashSubcommandPredicate}.
 * <li>{@code <PathTool>(<glob>)} → {@link PathGlobPredicate} bound to the tool, where {@code PathTool} is one of the
 * known path tools ({@code Read}, {@code Edit}, {@code Write}, {@code MultiEdit}, {@code Glob}, {@code Grep},
 * {@code LS}, {@code NotebookEdit}). Other tool names are rejected because the predicate kind cannot be inferred.
 * </ul>
 *
 * <p>
 * The {@code |} pipe combines terms via {@link CompositePredicate#or(ToolInputPredicate...)} — this matches the
 * Claude Code "any of" semantics.
 *
 * <h2>What the grammar does not have</h2>
 *
 * <p>
 * No regular expressions, no AND, no negation, no way to name an input field. The parser does not recognise those
 * spellings; it reads them as what the grammar does have, and what it reads is a matcher no call can satisfy. The two
 * places a foreign spelling can land are treated differently.
 *
 * <h3>A term without parentheses: refused when no tool can have that name</h3>
 *
 * <p>
 * Such a term is a tool name in its entirety, and a name with a character no tool name holds matches nothing. It used
 * to parse anyway — {@code "^Edit$"}, {@code "tool=Bash"}, {@code "Bash & input.command~^npm"} each became a
 * name-only predicate, registered, and never fired. It is now a term that does not parse, which the two front-ends
 * already treat as fatal where it matters: {@code HookRegistryApplier} stops the load for a {@code preTool} matcher
 * and warns for the others, {@code SkillHookSetParser} fails the skill.
 *
 * <p>
 * Nothing in the framework validates a tool name ({@code DefaultToolRegistry} takes any non-null string), so the
 * character set is the widest one its three sources of names produce, not a rule this class made up:
 * <ul>
 * <li>the names declared in the tree — letters, digits and {@code _} ({@code Bash}, {@code schedule_task}), and
 * {@code .} ({@code deriver.memory.search});
 * <li>MCP tools, composed by {@code McpTool} as {@code mcp__<server>__<tool>}: a server name is {@code [a-z0-9-]}
 * ({@code McpServerConfig}), a tool name is the server's own and by the MCP specification letters, digits, {@code _},
 * {@code -} and {@code .};
 * <li>what a model can say back: the Anthropic and OpenAI tool-call formats carry only letters, digits, {@code _} and
 * {@code -}.
 * </ul>
 * That is {@code [A-Za-z0-9_.-]}, plus {@code *}, the wildcard. A tool an embedder registers under a name outside it
 * can still be matched, by putting {@code *} where the odd character is.
 *
 * <p>
 * One composed name is checked further. After {@code mcp__} comes a server name, so {@code "mcp__.*"} — the regular
 * expression for "every MCP tool", which this project's own guide once documented — and {@code "mcp__GitHub__x"} can
 * match no MCP tool and are refused as well.
 *
 * <p>
 * What this cannot refuse: {@code .} is a name character, so {@code "Bash.*"} is a well-formed glob for names that
 * start with {@code Bash.} and cannot be told from {@code "deriver.*"}, which matches. It parses, with a WARN that
 * says what it will match.
 *
 * <h3>Inside parentheses: taken whole as a glob, and not checked</h3>
 *
 * <p>
 * {@code "Bash(command=^git\s+push)"} matches only a command that is that text, and the permission-pattern spelling
 * {@code "Bash(git:*)"} only a command that starts with {@code git:} — here the colon is a literal. Neither is
 * refused, because the glob is compared with a command line or a path and those hold any character:
 * {@code "Bash(FOO=1 make*)"} and {@code "Bash(npm run deploy:*)"} are guards that fire, and have the same shape.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class PredicateParser {

    private static final Logger log = LoggerFactory.getLogger(PredicateParser.class);

    /** How {@code McpTool} starts the name of every MCP tool: {@code mcp__<server>__<tool>}. */
    private static final String MCP_PREFIX = "mcp__";

    private static final Set<String> PATH_TOOL_NAMES = Set.of("Read", "Edit", "Write", "MultiEdit", "Glob", "Grep",
            "LS", "NotebookEdit");

    private PredicateParser() {
        // utility class
    }

    /**
     * Parses an {@code if}-expression string into a predicate.
     *
     * @param expression
     *            The expression (must not be null or blank)
     * @return The parsed predicate (never null)
     * @throws NullPointerException
     *             if expression is null
     * @throws IllegalArgumentException
     *             if expression is blank or fails to parse
     */
    public static ToolInputPredicate parse(String expression) {
        return parseTerms(expression, null);
    }

    /**
     * Parses an expression for a hook that cannot block, keeping the terms that can match and leaving out the ones
     * that are tool names no tool can have. {@link #parse(String)} refuses the whole expression for one such term,
     * which is right for a guard &mdash; a guard that silently lost a term is a hole &mdash; and wrong for an observer:
     * {@code "Bash|mcp__.*"} on {@code postTool} fired on {@code Bash} before that term was refused, and must go on
     * doing so.
     *
     * <p>
     * Only that refusal is forgiven. An expression that is malformed in any other way still throws, as it always did.
     * When no term is left the result matches nothing, which is what such an expression did before.
     *
     * @param expression
     *            The expression (must not be null or blank)
     * @param refused
     *            Told why each left-out term was refused (must not be null)
     * @return The predicate over the terms that were kept (never null)
     * @throws NullPointerException
     *             if expression or refused is null
     * @throws IllegalArgumentException
     *             if expression is blank or is malformed in some other way
     */
    public static ToolInputPredicate parseKeepingMatchableTerms(String expression, Consumer<String> refused) {
        Objects.requireNonNull(refused, "Refused cannot be null");
        return parseTerms(expression, refused);
    }

    private static ToolInputPredicate parseTerms(String expression, Consumer<String> refused) {
        Objects.requireNonNull(expression, "Expression cannot be null");
        if (expression.isBlank()) {
            throw new IllegalArgumentException("Expression cannot be blank");
        }
        final List<String> termStrings = splitTopLevelPipe(expression);
        final List<ToolInputPredicate> terms = new ArrayList<>(termStrings.size());
        for (String term : termStrings) {
            final String trimmed = term.strip();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Empty term in expression: '" + expression + "'");
            }
            try {
                terms.add(parseTerm(trimmed));
            } catch (UnmatchableTermException e) {
                if (refused == null) {
                    throw e;
                }
                refused.accept(e.getMessage());
            }
        }
        if (terms.isEmpty()) {
            // Every term was refused. The raw expression as a name is the predicate this used to produce: it holds a
            // character no tool name has, so it matches nothing.
            return NameOnlyPredicate.of(expression);
        }
        if (terms.size() == 1) {
            return terms.get(0);
        }
        return CompositePredicate.or(terms.toArray(new ToolInputPredicate[0]));
    }

    private static ToolInputPredicate parseTerm(String term) {
        final int parenStart = term.indexOf('(');
        if (parenStart < 0) {
            // Bare tool name (or "*").
            requireMatchableName(term);
            return NameOnlyPredicate.of(term);
        }
        if (!term.endsWith(")")) {
            throw new IllegalArgumentException("Term must end with ')' when arguments are supplied: '" + term + "'");
        }
        final String toolName = term.substring(0, parenStart).strip();
        if (toolName.isEmpty()) {
            throw new IllegalArgumentException("Term is missing a tool name: '" + term + "'");
        }
        final String pattern = term.substring(parenStart + 1, term.length() - 1).strip();
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("Term has empty argument pattern: '" + term + "'");
        }
        if ("Bash".equals(toolName)) {
            return BashSubcommandPredicate.of(pattern);
        }
        if (PATH_TOOL_NAMES.contains(toolName)) {
            return PathGlobPredicate.of(toolName, pattern);
        }
        throw new IllegalArgumentException("Unsupported tool with argument pattern: '" + toolName
                + "' (supported: Bash for sub-command globs, " + PATH_TOOL_NAMES + " for path globs)");
    }

    /**
     * Refuses a parenthesis-less term that can match no tool — see the class javadoc for where the character set comes
     * from. A term that passes may still match nothing ({@code "bash"}, {@code "Bash.*"}); this only removes the ones
     * that cannot.
     */
    private static void requireMatchableName(String term) {
        for (int i = 0; i < term.length(); i++) {
            final char c = term.charAt(i);
            if (!isToolNameChar(c) && c != '*') {
                throw new UnmatchableTermException("Term '" + term + "' can match no tool: a term without parentheses"
                        + " is a tool name, and " + describe(c) + " cannot occur in one (tool names are letters,"
                        + " digits, '_', '-' and '.'; '*' is the only wildcard). There are no regular expressions,"
                        + " no '&' and no input-field form: write Tool, Tool(glob), or terms joined by '|'");
            }
        }
        if (term.startsWith(MCP_PREFIX)) {
            requireMatchableMcpServer(term);
        }
        if (term.contains(".*")) {
            log.warn("hooks: matcher term '{}' holds '.*'. That is not a regular expression here: '.' is a literal and"
                    + " '*' alone is the wildcard, so the term matches only names with a dot at that place. If \"any"
                    + " characters\" was meant, write '{}'", term, term.replace(".*", "*"));
        }
    }

    /**
     * The server segment of an {@code mcp__<server>__<tool>} term — up to the next {@code _} or {@code *} — may hold
     * only what a server name can. The character class is {@code McpServerConfig}'s name pattern.
     */
    private static void requireMatchableMcpServer(String term) {
        for (int i = MCP_PREFIX.length(); i < term.length(); i++) {
            final char c = term.charAt(i);
            if (c == '_' || c == '*') {
                return;
            }
            final boolean serverNameChar = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-';
            if (!serverNameChar) {
                throw new UnmatchableTermException("Term '" + term + "' can match no MCP tool: MCP tools are named"
                        + " mcp__<server>__<tool> and a server name holds only lowercase letters, digits and '-', so "
                        + describe(c) + " cannot stand in the server's place. '*' is the only wildcard: every MCP"
                        + " tool is 'mcp__*', every tool of one server 'mcp__<server>__*'");
            }
        }
    }

    /**
     * A bare term that is a tool name no tool can have: the one refusal {@link #parseKeepingMatchableTerms} forgives.
     */
    private static final class UnmatchableTermException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private UnmatchableTermException(String message) {
            super(message);
        }
    }

    private static boolean isToolNameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.';
    }

    private static String describe(char c) {
        if (Character.isWhitespace(c)) {
            return "whitespace";
        }
        if (c < 0x20 || c > 0x7e) {
            return String.format("U+%04X", (int) c);
        }
        return "'" + c + "'";
    }

    /**
     * Splits the expression on top-level {@code |} pipes — i.e. pipes that are not nested inside parentheses.
     */
    private static List<String> splitTopLevelPipe(String expression) {
        final List<String> out = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            final char c = expression.charAt(i);
            if (c == '(') {
                depth++;
                current.append(c);
                continue;
            }
            if (c == ')') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("Unmatched ')' in expression: '" + expression + "'");
                }
                current.append(c);
                continue;
            }
            if (c == '|' && depth == 0) {
                out.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (depth != 0) {
            throw new IllegalArgumentException("Unmatched '(' in expression: '" + expression + "'");
        }
        out.add(current.toString());
        return out;
    }
}
