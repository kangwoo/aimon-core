package at.aimon.core.skill.hook.declarative;

import at.aimon.core.agent.tool.ToolInput;

/**
 * Predicate that decides whether a declarative {@code preTool} / {@code postTool} hook fires for a given
 * {@code (toolName, toolInput)} pair (AIMON extension).
 *
 * <p>
 * This is the unified extension point for matching. It superseded an earlier name-only {@code ToolMatcher}, which
 * could not express argument-level conditions and has been removed. Implementations encode the full
 * Claude-Code-style {@code matcher} grammar:
 * <ul>
 * <li>Name-only — e.g. {@code "Bash"}, {@code "*Tool"} — see
 * {@code at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate}.
 * <li>Bash sub-command pattern — e.g. {@code "Bash(git *)"}, {@code "Bash(npm install)"} — splits the
 * {@code command} input on {@code &&}, {@code ||}, {@code ;}, {@code |}, backticks and {@code $(...)} and matches
 * each segment against the supplied glob.
 * <li>Path glob — e.g. {@code "Edit(*.ts)"}, {@code "Write(.env)"} — matches the tool's path/file_path argument.
 * <li>Composite — OR / AND combinations of the above (see
 * {@code at.aimon.core.skill.hook.declarative.predicate.CompositePredicate}).
 * </ul>
 *
 * <p>
 * Implementations must be immutable and thread-safe — predicates are shared across hook firings.
 */
@FunctionalInterface
public interface ToolInputPredicate {

    /**
     * Returns {@code true} when the predicate matches the supplied tool invocation.
     *
     * @param toolName
     *            The tool name (must not be null)
     * @param input
     *            The tool input (must not be null; pass {@link ToolInput#of()} for name-only checks)
     * @return {@code true} when the predicate matches
     */
    boolean test(String toolName, ToolInput input);
}
