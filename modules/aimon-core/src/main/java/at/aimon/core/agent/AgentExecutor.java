package at.aimon.core.agent;

/**
 * Base agent executor interface for the Aimon library.
 *
 * <p>
 * Agent executors execute tasks based on an agent runtime and requests, using the ReAct (Reasoning and Acting) pattern
 * or other approaches.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentExecutor agent = CoreAgent.builder().name("MyAgent").llmClient(llmClient).toolExecutor(toolExecutor)
 *             .defaultTools(tools).defaultConfig(config).build();
 *
 *     AgentRuntime runtime = OrcaAgentRuntime.builder().agent(agent).build();
 *     ExecutionRequest request = ExecutionRequest.of("What files are here?");
 *     AgentExecutionResult result = agent.execute(runtime, request);
 * }
 * </pre>
 *
 * @param <CTX>
 *            에이전트 실행 컨텍스트 타입
 * @param <REQ>
 *            에이전트 실행 요청 타입
 * @param <RES>
 *            에이전트 실행 결과 타입
 */
// @formatter:off
public interface AgentExecutor<
        CTX extends AgentRuntime,
        REQ extends AgentExecutionRequest,
        RES extends AgentExecutionResult> {
    // @formatter:on

    /**
     * Executes the agent with the given agent runtime and request.
     *
     * @param agentRuntime
     *            The agent runtime with tools and config (must not be null)
     * @param executionRequest
     *            The execution request with user message and info (must not be null)
     * @return The execution result containing the final answer or error
     * @throws NullPointerException
     *             if any parameter is null
     */
    RES execute(CTX agentRuntime, REQ executionRequest);
}
