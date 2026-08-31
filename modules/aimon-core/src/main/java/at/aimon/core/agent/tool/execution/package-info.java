/**
 * Tool execution infrastructure and implementations.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides the execution infrastructure for tools, including executors, contexts, requests, and results.
 * It separates the concern of tool definition from tool execution, enabling flexible execution strategies.
 *
 * <h2>Key Concepts</h2>
 *
 * <h3>Tool Executor</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.execution.ToolExecutor} is responsible for executing a single tool:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolExecutor executor = new DefaultToolExecutor();
 *
 *     // Create execution context
 *     ToolExecutionContext context = ToolExecutionContext.of(tool);
 *
 *     // Create execution request
 *     ToolInput input = ToolInput.of(Map.of("command", "ls -la"));
 *     ToolContext toolContext = ToolContext.builder().put("environment", env).build();
 *     ToolExecutionRequest request = ToolExecutionRequest.of("tool_123", input, toolContext);
 *
 *     // Execute
 *     ToolExecutionResult result = executor.execute(context, request);
 * }
 * </pre>
 *
 * <h3>Execution Context</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.execution.ToolExecutionContext} encapsulates the tool to be executed:
 *
 * <pre>
 * {
 *     &#64;code
 *     Tool bashTool = new BashTool(shell);
 *     ToolExecutionContext context = ToolExecutionContext.of(bashTool);
 *
 *     // Access tool
 *     Tool tool = context.getTool();
 * }
 * </pre>
 *
 * <h3>Execution Request</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.execution.ToolExecutionRequest} contains all information needed to execute a tool:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolExecutionRequest request = ToolExecutionRequest.builder().toolUseId("tool_123")
 *             .input(ToolInput.of("command", "ls")).context(ToolContext.empty()).build();
 *
 *     String toolUseId = request.getToolUseId();
 *     ToolInput input = request.getInput();
 *     ToolContext context = request.getContext();
 * }
 * </pre>
 *
 * <h3>Execution Result</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.execution.ToolExecutionResult} represents the outcome of tool execution:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Success result
 *     ToolExecutionResult result = ToolExecutionResult.success("tool_123", "File created successfully");
 *
 *     // Error result
 *     ToolExecutionResult result = ToolExecutionResult.error("tool_123", "Permission denied");
 *
 *     // Check result
 *     if (result.isSuccess()) {
 *         String content = result.getContent();
 *     } else {
 *         String error = result.getError();
 *     }
 * }
 * </pre>
 *
 * <h3>Result Converter</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.execution.ToolExecutionResultConverter} converts between ToolResult and
 * ToolExecutionResult:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Convert ToolResult to ToolExecutionResult
 *     ToolResult toolResult = ToolResult.success("Operation completed");
 *     ToolExecutionResult executionResult = ToolExecutionResultConverter.toExecutionResult("tool_123", toolResult);
 * }
 * </pre>
 *
 * <h2>Execution Flow</h2>
 *
 * <ol>
 * <li>Create ToolExecutionContext with the tool to execute
 * <li>Create ToolExecutionRequest with tool use ID, input parameters, and context
 * <li>Call ToolExecutor.execute(context, request)
 * <li>ToolExecutor invokes Tool.execute(input, context)
 * <li>Tool returns ToolResult
 * <li>ToolExecutor converts ToolResult to ToolExecutionResult
 * <li>Return ToolExecutionResult to caller
 * </ol>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Single Responsibility:</b> Each class has one clear responsibility in the execution pipeline
 * <li><b>Immutability:</b> Execution requests and results are immutable value objects
 * <li><b>Fail-Safe:</b> All errors are captured and returned as ToolExecutionResult
 * <li><b>Traceability:</b> Tool use ID links requests to results for debugging
 * </ul>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Direct Tool Execution</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Setup
 *     Tool tool = new BashTool(shell);
 *     ToolExecutor executor = new DefaultToolExecutor();
 *
 *     // Execute
 *     ToolExecutionContext context = ToolExecutionContext.of(tool);
 *     ToolExecutionRequest request = ToolExecutionRequest.of("tool_123", ToolInput.of("command", "ls"),
 *             ToolContext.empty());
 *     ToolExecutionResult result = executor.execute(context, request);
 *
 *     // Handle result
 *     if (result.isSuccess()) {
 *         System.out.println(result.getContent());
 *     } else {
 *         System.err.println(result.getError());
 *     }
 * }
 * </pre>
 *
 * <h3>Batch Tool Execution</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolExecutor executor = new DefaultToolExecutor();
 *
 *     List<ToolExecutionRequest> requests = List.of(
 *             ToolExecutionRequest.of("tool_1", ToolInput.of("command", "ls"), ToolContext.empty()),
 *             ToolExecutionRequest.of("tool_2", ToolInput.of("command", "pwd"), ToolContext.empty()));
 *
 *     List<ToolExecutionResult> results = requests.stream()
 *             .map(request -> executor.execute(ToolExecutionContext.of(bashTool), request)).toList();
 * }
 * </pre>
 *
 * <h3>Error Handling</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolExecutionResult result = executor.execute(context, request);
 *
 *     if (result.isSuccess()) {
 *         processSuccessResult(result.getContent());
 *     } else {
 *         // Get error details
 *         String errorMessage = result.getError();
 *         Optional<Exception> exception = result.getException();
 *
 *         // Log with stack trace if available
 *         exception.ifPresent(e -> logger.error("Tool execution failed: {}", errorMessage, e));
 *
 *         // Handle specific error types
 *         if (exception.isPresent() &amp;&amp; exception.get() instanceof SecurityException) {
 *             handleSecurityViolation(errorMessage);
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <ul>
 * <li>ToolExecutionContext, ToolExecutionRequest, and ToolExecutionResult are immutable and thread-safe
 * <li>DefaultToolExecutor is stateless and thread-safe
 * <li>Multiple threads can safely share the same executor instance
 * </ul>
 *
 * @see at.aimon.core.agent.tool.execution.ToolExecutor
 * @see at.aimon.core.agent.tool.execution.ToolExecutionContext
 * @see at.aimon.core.agent.tool.execution.ToolExecutionRequest
 * @see at.aimon.core.agent.tool.execution.ToolExecutionResult
 * @see at.aimon.core.agent.tool.execution.ToolExecutionResultConverter
 */
package at.aimon.core.agent.tool.execution;
