/**
 * Core exceptions for the Aimon agent framework.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package contains exception classes that represent error conditions in the core agent system. These exceptions
 * follow a clear hierarchy and provide meaningful error information for debugging and error handling.
 *
 * <h2>Exception Hierarchy</h2>
 *
 * <pre>
 * RuntimeException
 *   └── AgentException (base exception for all agent errors)
 *       └── MaxIterationsExceededException (agent exceeded iteration limit)
 * </pre>
 *
 * <h2>Key Exceptions</h2>
 *
 * <h3>AgentException</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.exception.AgentException} is the base exception for all agent-related errors:
 *
 * <pre>
 * {
 *     &#64;code
 *     public void executeAgent() {
 *         try {
 *             AgentExecutionResult result = executor.execute(context, request);
 *         } catch (AgentException e) {
 *             // Handle any agent-related error
 *             logger.error("Agent execution failed: {}", e.getMessage(), e);
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>MaxIterationsExceededException</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.exception.MaxIterationsExceededException} is thrown when an agent exceeds its maximum
 * iteration limit:
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         AgentExecutionResult result = executor.execute(context, request);
 *     } catch (MaxIterationsExceededException e) {
 *         // Agent is stuck in a loop or task is too complex
 *         int maxIterations = e.getMaxIterations();
 *         logger.warn("Agent exceeded {} iterations: {}", maxIterations, e.getMessage());
 *
 *         // Consider increasing max iterations or simplifying the task
 *     }
 * }
 * </pre>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Catching Specific Exceptions</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         AgentExecutionResult result = executor.execute(context, request);
 *     } catch (MaxIterationsExceededException e) {
 *         // Handle iteration limit specifically
 *         handleIterationLimitExceeded(e);
 *     } catch (AgentException e) {
 *         // Handle other agent errors
 *         handleGeneralAgentError(e);
 *     }
 * }
 * </pre>
 *
 * <h3>Catching All Agent Exceptions</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         AgentExecutionResult result = executor.execute(context, request);
 *     } catch (AgentException e) {
 *         // Catch all agent-related exceptions
 *         logger.error("Agent error: {}", e.getMessage(), e);
 *         return AgentExecutionResult.error("Agent execution failed: " + e.getMessage());
 *     }
 * }
 * </pre>
 *
 * <h3>Rethrowing with Context</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     public void processUserRequest(String userId, AgentExecutionRequest request) {
 *         try {
 *             AgentExecutionResult result = executor.execute(context, request);
 *         } catch (AgentException e) {
 *             // Add context to exception
 *             throw new AgentException("Failed to process request for user " + userId + ": " + e.getMessage(), e);
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Clear Hierarchy:</b> All agent exceptions extend AgentException for easy catching
 * <li><b>Meaningful Messages:</b> Exception messages provide actionable error information
 * <li><b>Context Preservation:</b> Original exceptions are preserved via cause chains
 * <li><b>Unchecked Exceptions:</b> Extends RuntimeException for cleaner API design
 * </ul>
 *
 * <h2>Error Handling Best Practices</h2>
 *
 * <h3>Catch Specific Exceptions First</h3>
 *
 * <p>
 * Always catch more specific exceptions before catching general ones:
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         // ...
 *     } catch (MaxIterationsExceededException e) {
 *         // Specific handling
 *     } catch (AgentException e) {
 *         // General handling
 *     }
 * }
 * </pre>
 *
 * <h3>Log with Full Stack Trace</h3>
 *
 * <p>
 * Always include the full exception when logging:
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         // ...
 *     } catch (AgentException e) {
 *         // Good: Includes stack trace
 *         logger.error("Agent execution failed: {}", e.getMessage(), e);
 *
 *         // Bad: No stack trace
 *         logger.error("Agent execution failed: {}", e.getMessage());
 *     }
 * }
 * </pre>
 *
 * <h3>Don't Swallow Exceptions</h3>
 *
 * <p>
 * Always handle or rethrow exceptions; never silently ignore them:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Bad: Silently swallows exception
 *     try {
 *         executor.execute(context, request);
 *     } catch (AgentException e) {
 *         // Empty catch block
 *     }
 *
 *     // Good: At minimum, log the exception
 *     try {
 *         executor.execute(context, request);
 *     } catch (AgentException e) {
 *         logger.error("Failed to execute agent", e);
 *         throw e; // Re-throw if you can't handle it
 *     }
 * }
 * </pre>
 *
 * <h2>When to Use AgentException</h2>
 *
 * <p>
 * Use AgentException (or its subclasses) for:
 *
 * <ul>
 * <li>Agent execution failures (max iterations, configuration errors)
 * <li>Invalid agent state or configuration
 * <li>Errors in agent lifecycle management
 * </ul>
 *
 * <p>
 * Don't use AgentException for:
 *
 * <ul>
 * <li>Tool execution errors (use ToolResult instead)
 * <li>LLM client errors (use LlmException or specific exceptions)
 * <li>Validation errors (use IllegalArgumentException)
 * <li>Null pointer errors (use NullPointerException)
 * </ul>
 *
 * @see at.aimon.core.agent.exception.AgentException
 * @see at.aimon.core.agent.exception.MaxIterationsExceededException
 */
package at.aimon.core.agent.exception;
