package at.aimon.core.skill.render;

import java.util.Objects;

import at.aimon.core.skill.Skill;

/**
 * The skill-derived half of the {@link RenderContext} a skill body is rendered with.
 *
 * <p>
 * Knows only the {@link Skill}, and since the skill directory became a staged path it contributes nothing of its
 * own: which directory {@code ${AIMON_SKILL_DIR}} expands to is decided by the execution environment that stages the
 * skill (execution-environment design §4.4). The run-derived half — the agent runtime id, the session or execution
 * id, the principal, and the staged skill directory — comes from whoever invokes the skill, and is layered on top by
 * {@code at.aimon.core.tools.SkillRenderContextAccess} for the paths that carry a tool context. Keeping it out of here
 * keeps this package free of the tool layer and of the environment.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class SkillRenderContexts {

    private SkillRenderContexts() {
    }

    /**
     * Starts a {@link RenderContext} builder for rendering the given skill.
     *
     * <p>
     * It deliberately does <b>not</b> set the skill directory. {@code ${AIMON_SKILL_DIR}} is the path the execution's
     * environment returns when it stages the skill (execution-environment design §4.4), which only the caller holding
     * that environment can compute — {@code at.aimon.core.tools.SkillRenderContextAccess}. This package knows neither
     * the environment nor any skill path.
     *
     * @param skill
     *            The skill being rendered (must not be null)
     * @return A new builder (never null)
     */
    public static RenderContext.Builder builderFor(Skill skill) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        return RenderContext.builder();
    }
}
