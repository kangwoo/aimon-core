package at.aimon.core.subagent.execution;

/**
 * Executor interface for subagent execution.
 *
 * <p>
 * All subagents are executed through this interface with a unified execution model. The executor is responsible for:
 *
 * <ul>
 * <li>Creating isolated execution context
 * <li>Spawning subagent instances
 * <li>Managing the ReAct loop
 * <li>Validating tools permissions
 * <li>Collecting execution results
 * </ul>
 *
 * <p>
 * Follows the same pattern as AgentExecutor: context and request are separated.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentToolExecutor agentToolExecutor = new AgentToolExecutor(toolExecutor);
 *     SubagentExecutor executor = new DefaultSubagentExecutor(llmClient, agentToolExecutor);
 *
 *     SubagentExecutionContext context = SubagentExecutionContext.builder().subagent(codeReviewer)
 *             .availableTools(tools).build();
 *
 *     SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-001")
 *             .goal("Review the authentication module").build();
 *
 *     SubagentExecutionResult result = executor.execute(context, request);
 * }
 * </pre>
 */
public interface SubagentExecutor {
    /**
     * Executes a subagent with the given context and request.
     *
     * @param context
     *            Execution context (how to execute - subagent, tools)
     * @param request
     *            Execution request (what to execute - goal, user info)
     * @return Execution result with status, summary, and artifacts
     * @throws NullPointerException
     *             if context or request is null
     */
    SubagentExecutionResult execute(SubagentExecutionContext context, SubagentExecutionRequest request);
}
