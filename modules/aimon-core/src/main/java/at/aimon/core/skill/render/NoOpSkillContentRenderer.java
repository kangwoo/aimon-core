package at.aimon.core.skill.render;

import java.util.Objects;

import at.aimon.core.skill.Skill;

/**
 * Default {@link SkillContentRenderer} that returns the skill's instructions unchanged.
 *
 * <p>
 * Used as the safe default when no transformation is required. Subsequent work units (SK-02 and later) introduce
 * non-trivial implementations.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class NoOpSkillContentRenderer implements SkillContentRenderer {

    @Override
    public String render(Skill skill, String args, RenderContext context) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(args, "Args cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        return skill.getContent().getInstructions();
    }
}
