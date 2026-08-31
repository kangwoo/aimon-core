package at.aimon.core.skill.hook;

import java.util.Objects;

import at.aimon.core.skill.Skill;

/**
 * Default {@link SkillHookActivator} used when per-skill hook scoping is not configured.
 *
 * <p>
 * Returns {@link SkillHookScope#EMPTY} for every skill so {@code SkillTool} can use the same try-with-resources pattern
 * regardless of whether a {@link RegistryBackedSkillHookActivator} is wired in.
 */
public final class NoOpSkillHookActivator implements SkillHookActivator {

    @Override
    public SkillHookScope activate(Skill skill) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        return SkillHookScope.EMPTY;
    }
}
