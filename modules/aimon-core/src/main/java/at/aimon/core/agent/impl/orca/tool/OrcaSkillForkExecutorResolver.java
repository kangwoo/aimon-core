package at.aimon.core.agent.impl.orca.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.skill.fork.NoOpSkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkExecutor;
import at.aimon.core.skill.fork.SubagentBackedSkillForkExecutor;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * Resolves a {@link SkillForkExecutor} based on the availability of subagent infrastructure.
 *
 * <p>
 * Returns a {@link SubagentBackedSkillForkExecutor} when all six dependencies are available; otherwise returns a
 * {@link NoOpSkillForkExecutor} so deployments without subagent infrastructure remain usable for inline-only skills
 * while surfacing a clear error if a fork-mode skill is invoked.
 *
 * <p>
 * Shared by both skill invocation paths so they agree on what "fork executor is configured" means:
 *
 * <ul>
 * <li>LLM tool-call path — {@code OrcaSkillToolProvider} resolves the executor when registering {@code SkillTool} into
 * the per-context tool registry.
 * <li>User-slash path — {@code OrcaAgentExecutor.executeCommand} resolves the executor and passes it through the
 * {@code ToolContext} so {@code LlmSkillExecutor} picks it up for fork-mode skills.
 * </ul>
 */
public final class OrcaSkillForkExecutorResolver {

    private static final Logger log = LoggerFactory.getLogger(OrcaSkillForkExecutorResolver.class);

    private OrcaSkillForkExecutorResolver() {
        throw new AssertionError("Utility class");
    }

    /**
     * Resolves a {@link SkillForkExecutor} from the supplied dependencies.
     *
     * @param agent
     *            The active agent (nullable; when null the resolver returns a {@link NoOpSkillForkExecutor})
     * @param subagentRegistry
     *            Subagent registry (nullable)
     * @param toolRegistry
     *            Tool registry exposed to forked subagents (nullable)
     * @param hookRegistry
     *            Hook registry exposed to forked subagents (nullable)
     * @param environment
     *            Runtime environment passed to forked subagents (nullable)
     * @param subagentExecutionManager
     *            Manager that performs the actual subagent execution (nullable)
     * @return A {@link SubagentBackedSkillForkExecutor} when all dependencies are non-null; otherwise a
     *         {@link NoOpSkillForkExecutor}
     */
    public static SkillForkExecutor resolve(Agent agent, SubagentRegistry subagentRegistry, ToolRegistry toolRegistry,
            HookRegistry hookRegistry, Environment environment, SubagentExecutionManager subagentExecutionManager) {
        if (agent == null || subagentRegistry == null || toolRegistry == null || hookRegistry == null
                || environment == null || subagentExecutionManager == null) {
            log.debug("Resolved NoOpSkillForkExecutor: subagent infrastructure incomplete");
            return new NoOpSkillForkExecutor();
        }
        return new SubagentBackedSkillForkExecutor(agent.getMetadata().getModel(), subagentRegistry, toolRegistry,
                hookRegistry, environment, subagentExecutionManager);
    }
}
