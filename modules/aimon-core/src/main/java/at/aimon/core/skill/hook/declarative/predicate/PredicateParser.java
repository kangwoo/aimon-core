package at.aimon.core.skill.hook.declarative.predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

/**
 * Parses Claude-Code-style {@code if}-expression matcher strings into {@link ToolInputPredicate}s (AIMON extension).
 *
 * <h2>Grammar</h2>
 *
 * <pre>
 * expression := term ( "|" term )*
 * term       := tool-name [ "(" pattern ")" ]
 * tool-name  := identifier (e.g. Bash, Read, Edit, Glob, *)
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
 * <p>
 * Stateless and thread-safe.
 */
public final class PredicateParser {

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
            terms.add(parseTerm(trimmed));
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
