package at.aimon.core.skill.fork;

import java.util.Objects;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.skill.Skill;

/**
 * Default {@link SkillForkExecutor} used when fork-mode execution is not configured.
 *
 * <p>
 * Returns a failure outcome with a clear message so that an attempt to invoke a skill declared as {@code fork} fails
 * gracefully at execute time rather than at startup. This keeps deployments without subagent infrastructure usable for
 * inline-only skills.
 */
public final class NoOpSkillForkExecutor implements SkillForkExecutor {

    @Override
    public SkillForkOutcome fork(Skill skill, String goal, ToolContext toolContext) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(goal, "Goal cannot be null");
        Objects.requireNonNull(toolContext, "Tool context cannot be null");

        return SkillForkOutcome
                .failure(String.format("Skill '%s' declares execution.mode=fork but fork execution is not configured "
                        + "(SkillTool was wired with NoOpSkillForkExecutor).", skill.getName()));
    }
}
