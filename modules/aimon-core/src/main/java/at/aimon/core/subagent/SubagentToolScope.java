package at.aimon.core.subagent;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;

/**
 * Narrows a tool view to the names a subagent's allow-list admits, shared by the two subagent execution paths.
 *
 * <p>
 * <b>Name level is all this can do, and that is a property of the allow-list rather than a shortcoming here.</b> An
 * {@link AllowedTool} may carry a pattern ({@code Bash(git:*)}, {@code Read(/tmp/**)}), and a pattern cannot be
 * expressed in a list of tools — the tool is either offered or it is not. So a pattern-restricted tool stays in the
 * narrowed view and its out-of-pattern calls are still refused at dispatch. What this removes is the other case: a tool
 * whose <i>name</i> appears nowhere in the allow-list, which could never have been dispatched at all.
 *
 * <p>
 * <b>The narrowing is conservative by construction.</b> Refusal starts by keeping only the entries whose name equals
 * the called tool's ({@code DefaultToolPermissionValidator}), so a name absent from the allow-list is already a denial
 * before any pattern is consulted. A name match is therefore a necessary condition for permission, and filtering on
 * the name set can never withhold a tool that would have been allowed.
 *
 * <p>
 * The two callers want different shapes and the difference matters:
 *
 * <ul>
 * <li>The ReAct loop ({@code DefaultSubagentExecutor}) filters the {@code ToolDefinition} list it sends to the model
 * with {@link #admits(Subagent, Tool)}, and leaves its registry alone. The registry is also the dispatch registry, and
 * narrowing it would turn a forbidden name into {@code "Unknown tool: …"} — a distinction
 * {@code DefaultToolExecutionManager} keeps deliberately so an invented name does not read like a forbidden one in the
 * audit trail.
 * <li>The code-behavior path ({@code DefaultSubagentBehaviorSupport}) wants a registry it can hand to trusted code, and
 * takes {@link #scope(ToolRegistry, Subagent)}. That one exposes the allow-list without enforcing it — a behavior may
 * still reach the full registry through its execution context.
 * </ul>
 */
public final class SubagentToolScope {

    private SubagentToolScope() {
    }

    /**
     * Returns whether the subagent's allow-list admits the given tool by name, which is {@code true} for every tool
     * when the subagent declares no restrictions.
     *
     * @param subagent
     *            the subagent whose allow-list applies (must not be null)
     * @param tool
     *            the tool to test (must not be null)
     * @return {@code true} if the tool may be offered to the model
     */
    public static boolean admits(Subagent subagent, Tool tool) {
        Objects.requireNonNull(subagent, "Subagent cannot be null");
        Objects.requireNonNull(tool, "Tool cannot be null");
        if (!subagent.hasToolRestrictions()) {
            return true;
        }
        return allowedNames(subagent).contains(tool.getDefinition().getName());
    }

    /**
     * Returns a registry containing only the subagent's allowed tools (matched by name), or the full registry when the
     * subagent declares no tool restrictions.
     *
     * @param full
     *            the unrestricted registry (must not be null)
     * @param subagent
     *            the subagent whose allow-list applies (must not be null)
     * @return the narrowed registry, or {@code full} when there is nothing to narrow
     */
    public static ToolRegistry scope(ToolRegistry full, Subagent subagent) {
        Objects.requireNonNull(full, "Tool registry cannot be null");
        Objects.requireNonNull(subagent, "Subagent cannot be null");
        if (!subagent.hasToolRestrictions()) {
            return full;
        }
        final Set<String> allowedNames = allowedNames(subagent);
        final DefaultToolRegistry scoped = new DefaultToolRegistry();
        for (Tool tool : full.findAll()) {
            if (allowedNames.contains(tool.getDefinition().getName())) {
                scoped.register(tool);
            }
        }
        return scoped;
    }

    private static Set<String> allowedNames(Subagent subagent) {
        return subagent.getAllowedTools().stream().map(AllowedTool::getToolName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
