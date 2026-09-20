package at.aimon.core.subagent;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.AllowedTools;

/**
 * Narrows a tool view to the names a subagent's allow-list admits, shared by the two subagent execution paths.
 *
 * <p>
 * <b>Both shapes narrow by name only, and neither can over-withhold.</b> The reasoning lives on
 * {@link AllowedTools#admissionFilter(java.util.List)}, which both shapes here are expressed in terms of: a pattern
 * cannot be said in a list of tools, and a name absent from an allow-list is already a denial.
 *
 * <p>
 * The two callers want different shapes and the difference matters:
 *
 * <ul>
 * <li>The ReAct loop ({@code DefaultSubagentExecutor}) narrows only the {@code ToolDefinition} list it sends to the
 * model, with {@link #admissionFilter(Subagent)}, and leaves its registry alone — that registry may be a
 * {@code ToolSearchRegistry} holding the execution's activation state, which a narrowed copy would discard.
 * <li>The code-behavior path ({@code DefaultSubagentBehaviorSupport}) wants a registry it can hand to trusted code, and
 * takes {@link #scope(ToolRegistry, Subagent)}. That one exposes the allow-list without enforcing it — a behavior may
 * still reach the full registry through its execution context.
 * </ul>
 *
 * <p>
 * Neither shape is what enforces anything: a call reaches {@code ToolExecutionManager} with the same allow-list and is
 * refused there. Withholding a tool from the offer only saves the iteration the model would spend discovering that.
 */
public final class SubagentToolScope {

    private SubagentToolScope() {
    }

    /**
     * Returns a predicate admitting the tools this subagent's allow-list names, for filtering a whole collection.
     *
     * <p>
     * Prefer this over {@link #admits(Subagent, Tool)} in a loop: the allow-list is reduced to a name set once here
     * rather than once per candidate. The predicate is immutable and holds no reference to any registry.
     *
     * @param subagent
     *            the subagent whose allow-list applies (must not be null)
     * @return a predicate that is always {@code true} when the subagent declares no restrictions
     */
    public static Predicate<Tool> admissionFilter(Subagent subagent) {
        Objects.requireNonNull(subagent, "Subagent cannot be null");
        return AllowedTools.admissionFilter(subagent.getAllowedTools());
    }

    /**
     * Returns whether the subagent's allow-list admits the given tool by name, which is {@code true} for every tool
     * when the subagent declares no restrictions.
     *
     * <p>
     * Single-shot form of {@link #admissionFilter(Subagent)}; use that one to filter a collection.
     *
     * @param subagent
     *            the subagent whose allow-list applies (must not be null)
     * @param tool
     *            the tool to test (must not be null)
     * @return {@code true} if the tool may be offered to the model
     */
    public static boolean admits(Subagent subagent, Tool tool) {
        Objects.requireNonNull(tool, "Tool cannot be null");
        return admissionFilter(subagent).test(tool);
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
        final Predicate<Tool> admitted = admissionFilter(subagent);
        final DefaultToolRegistry scoped = new DefaultToolRegistry();
        for (Tool tool : full.findAll()) {
            if (admitted.test(tool)) {
                scoped.register(tool);
            }
        }
        return scoped;
    }

    /**
     * Returns the subagent with its allow-list replaced, keeping every other field — the name above all, since hooks,
     * attribution and behaviour lookup all key on it.
     *
     * <p>
     * The narrowed definition is how a caller's ceiling reaches a fork: both consumers of the allow-list
     * ({@code availableToolDefinitions} and the dispatch spec) read it off the {@code Subagent}, so replacing the
     * definition once leaves them unable to disagree, and a code behavior handed the same context sees the same
     * bound.
     *
     * @param subagent
     *            the subagent to rebase (must not be null)
     * @param allowedTools
     *            the allow-list to put in place of its own (must not be null)
     * @return a new subagent identical but for the allow-list
     * @throws NullPointerException
     *             if either argument is null
     */
    public static Subagent withAllowedTools(Subagent subagent, List<AllowedTool> allowedTools) {
        Objects.requireNonNull(subagent, "Subagent cannot be null");
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");
        final SubagentMetadata metadata = subagent.getMetadata();
        return Subagent.of(subagent.getName(),
                SubagentMetadata.builder().description(metadata.getDescription()).whenToUse(metadata.getWhenToUse())
                        .model(metadata.getModel()).maxIterations(metadata.getMaxIterations())
                        .allowedTools(allowedTools).build(),
                subagent.getContent());
    }
}
