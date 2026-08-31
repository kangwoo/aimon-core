package at.aimon.core.skill.execution;

import java.util.List;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Dispatches one batch of tool calls made by a user-invoked skill.
 *
 * <p>
 * A skill invoked as {@code /my-skill} runs its own ReAct loop, and until this interface existed it ran that loop
 * straight against a {@link at.aimon.core.agent.tool.ToolExecutionManager ToolExecutionManager}. That is the same
 * registry the agent uses, but a shorter path to it: no {@code PermissionRequest} hooks, no side-effect approval, no
 * {@code PreTool} / {@code PostTool}. A tool the agent could not have run without asking ran without asking as soon as
 * the user typed a slash.
 *
 * <p>
 * The executor binds an implementation into the command {@link ToolContext} under
 * {@link at.aimon.core.tools.ToolContextKeys#SKILL_TOOL_DISPATCHER_KEY}, following the same pattern as
 * {@link at.aimon.core.skill.fork.SkillForkExecutor}: the collaborator is per-execution and knows about the live agent
 * runtime, so it travels in the context rather than widening {@code CommandExecutionManager}, whose signature is public
 * API and would have had to carry a hook registry, an environment and an interrupt coordinator to say the same thing.
 *
 * <p>
 * When no dispatcher is bound — an embedder driving {@code LlmSkillExecutor} directly, or a host with no agent runtime
 * — the executor falls back to the plain execution manager, which is the historical behaviour.
 *
 * <p>
 * Implementations must not throw: a tool that fails, is denied, or does not exist is reported as an error
 * {@link ToolUseResult} in the returned list, one entry per input {@link ToolUse}, in the same order.
 */
@FunctionalInterface
public interface SkillToolDispatcher {

    /**
     * Runs every tool call in {@code toolUses} and returns their results in the same order.
     *
     * @param toolRegistry
     *            the registry the tools are looked up and executed against (must not be null)
     * @param toolContext
     *            the skill's tool context — identity, filesystem, agent runtime id (must not be null)
     * @param toolUses
     *            the tool calls the skill's LLM asked for (must not be null)
     * @param allowedTools
     *            the skill's declared allow-list; empty means unrestricted (must not be null)
     * @param iterationCount
     *            the skill's current ReAct iteration, forwarded to the hook contexts
     * @return one result per requested tool call, in request order (never null)
     */
    List<ToolUseResult> dispatch(ToolRegistry toolRegistry, ToolContext toolContext, List<ToolUse> toolUses,
            List<AllowedTool> allowedTools, int iterationCount);
}
