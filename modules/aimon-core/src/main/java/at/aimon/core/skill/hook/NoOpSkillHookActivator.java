package at.aimon.core.skill.hook;

import java.util.Objects;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.skill.Skill;

/**
 * Default {@link SkillHookActivator} used when per-skill hook scoping is not configured.
 *
 * <p>
 * Returns {@link SkillHookScope#EMPTY} for every skill so {@code SkillTool} can use the same try-with-resources pattern
 * regardless of whether a {@link ScopedSkillHookActivator} is wired in.
 */
public final class NoOpSkillHookActivator implements SkillHookActivator {

    @Override
    public SkillHookScope activate(Skill skill, ToolContext context) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        return SkillHookScope.EMPTY;
    }
}
