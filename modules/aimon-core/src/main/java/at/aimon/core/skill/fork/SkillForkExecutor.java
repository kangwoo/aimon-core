package at.aimon.core.skill.fork;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.skill.Skill;

/**
 * Executes a skill in fork mode by delegating its rendered instructions to a named subagent.
 *
 * <p>
 * SkillTool consults this executor when a skill declares {@code execution.mode: fork}. The rendered skill body is
 * forwarded as the subagent's goal; the subagent's final answer is returned to the calling LLM.
 *
 * <p>
 * Implementations must be thread-safe.
 */
public interface SkillForkExecutor {

    /**
     * Executes the given skill in fork mode.
     *
     * @param skill
     *            The skill to fork (must not be null); {@code skill.getMetadata().getForkAgentName()} identifies the
     *            target subagent
     * @param goal
     *            The rendered skill body, used verbatim as the subagent's goal (must not be null)
     * @param toolContext
     *            The active tool context, used to source agent runtime ID, execution attributes, and parent LLM
     *            call metadata for subagent attribution propagation (must not be null)
     * @return A non-null outcome describing success (with final answer) or failure (with error message)
     */
    SkillForkOutcome fork(Skill skill, String goal, ToolContext toolContext);
}
