package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.fork.NoOpSkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkExecutor;
import at.aimon.core.skill.fork.SubagentBackedSkillForkExecutor;
import at.aimon.core.skill.hook.NoOpSkillHookActivator;
import at.aimon.core.skill.hook.RegistryBackedSkillHookActivator;
import at.aimon.core.skill.hook.SkillHookActivator;
import at.aimon.core.skill.policy.AlwaysAllowSkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.tools.skill.SkillTool;

/**
 * Provides skill management tools to the Orca agent system.
 *
 * <p>
 * This provider registers the {@link SkillTool} which enables agents to activate and execute specialized skills for
 * domain-specific tasks. When the subagent infrastructure is available, fork-mode skills are wired through
 * {@link SubagentBackedSkillForkExecutor}; otherwise a {@link NoOpSkillForkExecutor} is used and fork-mode skills
 * fail at execute time with a clear message.
 *
 * @see OrcaToolProvider
 * @see SkillTool
 */
public class OrcaSkillToolProvider implements OrcaToolProvider {

    private static final Logger log = LoggerFactory.getLogger(OrcaSkillToolProvider.class);

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final SkillRegistry skillRegistry = context.getSkillRegistry();
        Objects.requireNonNull(skillRegistry, "skillRegistry must not be null in context");

        registry.register(new SkillTool(skillRegistry, new DefaultSkillContentRenderer(), resolveForkExecutor(context),
                resolveHookActivator(context), resolveInvocationPolicy(context)));
    }

    /**
     * Resolves the {@link SkillInvocationPolicy} from the provider context, falling back to
     * {@link AlwaysAllowSkillInvocationPolicy#INSTANCE} when SK-11 wiring is absent. Sharing the same policy
     * instance with the {@code OrcaAgentExecutor}'s pre-flight scanner ensures that a {@code DENY} from the policy
     * is observed both at suspend-decision time and at per-tool execution time.
     */
    private static SkillInvocationPolicy resolveInvocationPolicy(OrcaToolProviderContext context) {
        final SkillInvocationPolicy policy = context.getSkillInvocationPolicy();
        if (policy == null) {
            log.debug("SkillTool wired with AlwaysAllowSkillInvocationPolicy: no policy provided in context");
            return AlwaysAllowSkillInvocationPolicy.INSTANCE;
        }
        return policy;
    }

    /**
     * Resolves the {@link SkillHookActivator} based on what is available in the provider context. Returns
     * {@link NoOpSkillHookActivator} when no {@link HookRegistry} is wired in; otherwise registers per-skill hooks
     * through a {@link RegistryBackedSkillHookActivator}.
     */
    private static SkillHookActivator resolveHookActivator(OrcaToolProviderContext context) {
        final HookRegistry hookRegistry = context.getHookRegistry();
        if (hookRegistry == null) {
            log.debug("SkillTool wired with NoOpSkillHookActivator: no HookRegistry available in context");
            return new NoOpSkillHookActivator();
        }
        return new RegistryBackedSkillHookActivator(hookRegistry);
    }

    /**
     * Resolves the {@link SkillForkExecutor} based on what is available in the provider context. Returns
     * {@link NoOpSkillForkExecutor} when subagent infrastructure is incomplete; this keeps deployments without
     * subagents usable for inline-only skills while surfacing a clear error if a fork-mode skill is invoked.
     */
    private static SkillForkExecutor resolveForkExecutor(OrcaToolProviderContext context) {
        return OrcaSkillForkExecutorResolver.resolve(context.getAgent(), context.getSubagentRegistry(),
                context.getToolRegistry(), context.getHookRegistry(), context.getEnvironment(),
                context.getSubagentExecutionManager());
    }
}
