package at.aimon.core.skill.execution;

/**
 * Executes a {@link at.aimon.core.skill.Skill} as a user-invoked ReAct task.
 *
 * <p>
 * Introduced in SK-08-C as the user-invocation counterpart to {@code SkillTool} (model invocation). The default
 * implementation, {@code LlmSkillExecutor}, runs the same ReAct loop that {@code LlmCommandExecutor} uses for slash
 * commands but operates directly on a {@link at.aimon.core.skill.Skill} input.
 *
 * <p>
 * Implementations must be thread-safe — a single executor instance is shared across user invocations.
 */
public interface SkillExecutor {

    /**
     * Executes the configured skill against the supplied request.
     *
     * @param context
     *            The execution context containing the skill, model config, and tool registry (must not be null)
     * @param request
     *            The execution request containing user-supplied arguments and render context (must not be null)
     * @return The execution result (never null)
     * @throws NullPointerException
     *             if context or request is null
     */
    SkillExecutionResult execute(SkillExecutionContext context, SkillExecutionRequest request);
}
