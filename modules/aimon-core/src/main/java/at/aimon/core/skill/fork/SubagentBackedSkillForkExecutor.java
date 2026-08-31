package at.aimon.core.skill.fork;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.skill.Skill;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.tools.InvokingSessionAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Production {@link SkillForkExecutor} implementation that delegates fork-mode skill execution to a named subagent via
 * {@link SubagentExecutionManager}.
 *
 * <p>
 * Wires the same subagent execution machinery that {@code TaskTool} uses, so a fork-mode skill behaves identically to
 * an explicit {@code Task(subagent_name=..., prompt=<rendered skill body>)} call.
 *
 * <p>
 * Fails fast at fork time when the named subagent is missing from the registry — the SkillTool surfaces this as a
 * tool-level error so the parent LLM can recover gracefully.
 */
public final class SubagentBackedSkillForkExecutor implements SkillForkExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubagentBackedSkillForkExecutor.class);

    private final LlmModel defaultModel;
    private final SubagentRegistry subagentRegistry;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final SubagentExecutionManager subagentExecutionManager;

    /**
     * Creates a new SubagentBackedSkillForkExecutor.
     *
     * @param defaultModel
     *            The default LLM model used for subagent execution when no per-subagent override is set (must not be
     *            null)
     * @param subagentRegistry
     *            Registry consulted to validate that the target subagent exists before forking (must not be null)
     * @param toolRegistry
     *            Tool registry exposed to the forked subagent (must not be null)
     * @param hookRegistry
     *            Hook registry exposed to the forked subagent (must not be null)
     * @param environment
     *            Runtime environment passed to the forked subagent (must not be null)
     * @param subagentExecutionManager
     *            Manager that performs the actual subagent execution (must not be null)
     */
    public SubagentBackedSkillForkExecutor(LlmModel defaultModel, SubagentRegistry subagentRegistry,
            ToolRegistry toolRegistry, HookRegistry hookRegistry, Environment environment,
            SubagentExecutionManager subagentExecutionManager) {
        this.defaultModel = Objects.requireNonNull(defaultModel, "Default model cannot be null");
        this.subagentRegistry = Objects.requireNonNull(subagentRegistry, "Subagent registry cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "Tool registry cannot be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "Hook registry cannot be null");
        this.environment = Objects.requireNonNull(environment, "Environment cannot be null");
        this.subagentExecutionManager = Objects.requireNonNull(subagentExecutionManager,
                "Subagent execution manager cannot be null");
    }

    @Override
    public SkillForkOutcome fork(Skill skill, String goal, ToolContext toolContext) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(goal, "Goal cannot be null");
        Objects.requireNonNull(toolContext, "Tool context cannot be null");

        final String agentName = skill.getMetadata().getForkAgentName();
        if (agentName == null || agentName.isBlank()) {
            return SkillForkOutcome.failure(String
                    .format("Skill '%s' is declared as fork mode but has no execution.agent set", skill.getName()));
        }

        // Fail fast when the target subagent does not exist; otherwise the executor would surface a less specific
        // SubagentNotFoundException nested inside a generic execution failure.
        if (subagentRegistry.getSubagent(agentName).isEmpty()) {
            return SkillForkOutcome
                    .failure(String.format("Skill '%s' references unknown subagent '%s'", skill.getName(), agentName));
        }

        final AgentRuntimeId agentRuntimeId = toolContext.get(ToolContextKeys.AGENT_RUNTIME_ID).orElse(null);
        if (agentRuntimeId == null) {
            return SkillForkOutcome.failure(String
                    .format("Cannot fork skill '%s': agent runtime ID not available in tool context", skill.getName()));
        }

        final Map<String, Object> executionAttributes = toolContext.get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY)
                .orElse(Map.of());

        final LlmCallMetadata parentMetadata = toolContext.get(ToolContextKeys.LLM_CALL_METADATA_KEY).orElseGet(() -> {
            log.debug("LLM_CALL_METADATA_KEY absent in ToolContext; subagent usage will lack parent "
                    + "traceId/principal.");
            return LlmCallMetadata.empty();
        });

        final SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder().agentRuntimeId(agentRuntimeId)
                .subagentRegistry(subagentRegistry).toolRegistry(toolRegistry).hookRegistry(hookRegistry)
                .environment(environment).defaultModel(defaultModel).executionAttributes(executionAttributes)
                .parentLlmCallMetadata(parentMetadata)
                .invokingSessionId(InvokingSessionAccess.idToPropagate(toolContext).orElse(null)).build();

        final String taskId = UUID.randomUUID().toString();
        final String description = "skill:" + skill.getName();

        try {
            final SubagentExecutionResult result = subagentExecutionManager.execute(env, taskId, agentName, goal,
                    description);
            if (result.isSuccess()) {
                return SkillForkOutcome.success(result.getFinalAnswer());
            }
            return SkillForkOutcome.failure(result.getErrorMessage());
        } catch (RuntimeException e) {
            log.error("Fork execution of skill '{}' via subagent '{}' failed: {}", skill.getName(), agentName,
                    e.getMessage(), e);
            return SkillForkOutcome.failure("Fork execution failed: " + e.getMessage());
        }
    }
}
