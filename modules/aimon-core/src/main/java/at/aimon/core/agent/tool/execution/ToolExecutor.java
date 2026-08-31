package at.aimon.core.agent.tool.execution;

import at.aimon.core.agent.tool.ToolResult;

/**
 * Interface for executing tools requested by the LLM.
 *
 * <p>
 * Implementations handle the actual execution of tools based on the tools name and input parameters provided by the
 * LLM.
 *
 * <p>
 * Thread-safety is implementation-specific.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolExecutor executor = new MyToolExecutor();
 *
 *     // Create execution context with tool
 *     Tool tool = registry.getTool("bash");
 *     ToolExecutionContext executionContext = ToolExecutionContext.of(tool);
 *
 *     // Create execution request
 *     ToolUse toolUse = ToolUse.of("tool_123", "bash", Map.of("command", "ls -la"));
 *     ToolContext toolContext = ToolContext.empty();
 *     ToolExecutionRequest request = ToolExecutionRequest.of(toolUse, toolContext);
 *
 *     // Execute
 *     ToolResult result = executor.execute(executionContext, request);
 * }
 * </pre>
 *
 * @see ToolExecutionContext
 * @see ToolExecutionRequest
 * @see ToolResult
 */
public interface ToolExecutor {
    /**
     * Executes a tools based on the LLM's request.
     *
     * <p>
     * This method should:
     *
     * <ol>
     * <li>Extract the tool from the execution context
     * <li>Extract the tool use and tool context from the request
     * <li>Execute the tools
     * <li>Return the result (success or error)
     * </ol>
     *
     * @param executionContext
     *            The execution context containing the tool to execute (must not be null)
     * @param request
     *            The execution request containing tool use and tool context (must not be null)
     * @return The execution result (never null)
     * @throws NullPointerException
     *             if executionContext or request is null
     */
    ToolExecutionResult execute(ToolExecutionContext executionContext, ToolExecutionRequest request);
}
