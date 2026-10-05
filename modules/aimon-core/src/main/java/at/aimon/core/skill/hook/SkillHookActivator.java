package at.aimon.core.skill.hook;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.skill.Skill;

/**
 * Strategy seam (AIMON extension) for activating skill-scoped hooks around a {@code SkillTool} invocation.
 *
 * <p>
 * The activator inspects the supplied {@link Skill} and returns a {@link SkillHookScope} that makes the hooks it
 * declares available to the skill's fork — through {@link SkillHookScope#hookRegistry()} — until
 * {@link SkillHookScope#close() close()} ends them. {@code SkillTool} wraps the skill body's execution in a
 * try-with-resources block over this scope so that the hooks are guaranteed to end on normal completion, errors, and
 * exceptions alike. The user-slash path ({@code LlmSkillExecutor}) does the same around a fork-mode skill's fork, so a
 * skill's hooks do not depend on whether the model or the user invoked it.
 *
 * <p>
 * Two implementations ship in core:
 * <ul>
 * <li>{@link NoOpSkillHookActivator} — does nothing; the default for deployments that have not opted in to per-skill
 * hook scopes (or where no {@code HookRegistry} is available).
 * <li>{@link ScopedSkillHookActivator} — layers the skill's hooks over the invoking execution's registry.
 * </ul>
 *
 * <p>
 * Implementations must be thread-safe — multiple skill invocations may activate concurrently.
 *
 * <p>
 * <b>Where the hooks fire.</b> In the skill's fork and the forks that fork starts, for as long as the scope is open —
 * and nowhere else: not in another session of the same agent, and not in the execution that invoked the skill. An
 * implementation must not register the hooks with a registry other executions dispatch against. An inline-mode skill
 * has no fork, so its hooks do not fire at all.
 *
 * <p>
 * Two things the scope does not reach. A background subagent the fork starts keeps running after the skill returns,
 * and from then on runs without the skill's hooks. A background workflow run never has them — it runs on the
 * agent-scoped runner, which the invoking call's registry cannot follow — so {@code Workflow} and {@code WorkflowJs}
 * refuse background mode while a skill's guard hooks are active.
 */
public interface SkillHookActivator {

    /**
     * Activate any hooks declared by {@code skill} for the duration of its current invocation.
     *
     * @param skill
     *            The skill being invoked (must not be null)
     * @param context
     *            The tool context of the invoking call, read for the registry that execution dispatches against (must
     *            not be null)
     * @return A scope carrying the registry the skill's fork should use, which ends the hooks when closed (never null)
     */
    SkillHookScope activate(Skill skill, ToolContext context);
}
