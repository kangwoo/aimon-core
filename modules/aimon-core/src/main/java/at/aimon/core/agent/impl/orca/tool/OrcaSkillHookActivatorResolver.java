package at.aimon.core.agent.impl.orca.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.HookRegistry;
import at.aimon.core.skill.hook.NoOpSkillHookActivator;
import at.aimon.core.skill.hook.ScopedSkillHookActivator;
import at.aimon.core.skill.hook.SkillHookActivator;

/**
 * Resolves the {@link SkillHookActivator} based on whether a {@link HookRegistry} is available.
 *
 * <p>
 * Returns a {@link ScopedSkillHookActivator} layering a skill's hooks over the registry when there is one, and a
 * {@link NoOpSkillHookActivator} otherwise. Shared by both skill invocation paths so a skill's hooks do not depend on
 * who invoked it (EE-68):
 *
 * <ul>
 * <li>LLM tool-call path — {@code OrcaSkillToolProvider} passes the activator to {@code SkillTool}.
 * <li>User-slash path — {@code OrcaAgentExecutor.executeCommand} publishes it under
 * {@link at.aimon.core.tools.ToolContextKeys#SKILL_HOOK_ACTIVATOR_KEY} for {@code LlmSkillExecutor}.
 * </ul>
 */
public final class OrcaSkillHookActivatorResolver {

    private static final Logger log = LoggerFactory.getLogger(OrcaSkillHookActivatorResolver.class);

    private OrcaSkillHookActivatorResolver() {
        throw new AssertionError("Utility class");
    }

    /**
     * Resolves the activator for the given registry.
     *
     * @param hookRegistry
     *            The registry the skill's hooks are layered over when the invoking context carries none (nullable;
     *            when null the resolver returns a {@link NoOpSkillHookActivator})
     * @return A {@link ScopedSkillHookActivator} when {@code hookRegistry} is non-null; otherwise a
     *         {@link NoOpSkillHookActivator}
     */
    public static SkillHookActivator resolve(HookRegistry hookRegistry) {
        if (hookRegistry == null) {
            log.debug("Resolved NoOpSkillHookActivator: no HookRegistry available");
            return new NoOpSkillHookActivator();
        }
        return new ScopedSkillHookActivator(hookRegistry);
    }
}
