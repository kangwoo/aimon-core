package at.aimon.core.skill.hook;

import at.aimon.core.skill.Skill;

/**
 * Strategy seam (AIMON extension) for activating skill-scoped hooks around a {@code SkillTool} invocation.
 *
 * <p>
 * The activator inspects the supplied {@link Skill}, registers any hooks it declares, and returns a
 * {@link SkillHookScope} whose {@link SkillHookScope#close() close()} unregisters them. {@code SkillTool} wraps the
 * skill body's execution in a try-with-resources block over this scope so that hooks are guaranteed to be torn down on
 * normal completion, errors, and exceptions alike.
 *
 * <p>
 * Two implementations ship in core:
 * <ul>
 * <li>{@link NoOpSkillHookActivator} — does nothing; the default for deployments that have not opted in to per-skill
 * hook scopes (or where no {@code HookRegistry} is available).
 * <li>{@link RegistryBackedSkillHookActivator} — wires registration through a real {@code HookRegistry}.
 * </ul>
 *
 * <p>
 * Implementations must be thread-safe — multiple skill invocations may activate concurrently.
 *
 * <p>
 * Design note: the per-skill hook contract makes the most sense in fork-mode skills, where the registered hooks remain
 * active for the lifetime of the spawned SubAgent. In inline mode, the scope only spans the rendering phase of
 * {@code SkillTool.execute()} and so hooks practically do not fire — this is documented behavior, not a bug.
 */
public interface SkillHookActivator {

    /**
     * Activate any hooks declared by {@code skill} for the duration of its current invocation.
     *
     * @param skill
     *            The skill being invoked (must not be null)
     * @return A scope that, when closed, unregisters the hooks (never null)
     */
    SkillHookScope activate(Skill skill);
}
