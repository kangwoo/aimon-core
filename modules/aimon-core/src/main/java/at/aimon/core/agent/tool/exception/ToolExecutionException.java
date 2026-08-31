package at.aimon.core.agent.tool.exception;

import java.io.Serial;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.execution.ToolExecutionResult;

/**
 * Exception thrown when tool execution fails at runtime.
 *
 * <p>
 * This exception indicates that a tool failed to execute successfully. It is a specialized subclass of
 * {@link ToolException} used specifically for runtime execution failures, as opposed to permission violations (see
 * {@link ToolPermissionViolationException}).
 *
 * <p>
 * <b>Common Failure Scenarios:</b>
 *
 * <ul>
 * <li><b>I/O Errors:</b> File not found, permission denied, disk full
 * <li><b>Invalid Parameters:</b> Malformed input, missing required parameters, type mismatches
 * <li><b>Tool-Specific Failures:</b> Command execution errors, API call failures, parsing errors
 * <li><b>Resource Issues:</b> Timeout, memory exhaustion, connection failures
 * <li><b>State Violations:</b> Tool prerequisites not met, invalid tool state
 * </ul>
 *
 * <p>
 * <b>When to Use:</b>
 * <ul>
 * <li>Tool execution encounters runtime errors (I/O, network, parsing)
 * <li>Tool parameters are invalid or malformed
 * <li>Tool-specific preconditions are not met
 * <li>External resources (files, APIs) are unavailable
 * <li>Execution exceeds timeout or resource limits
 * </ul>
 *
 * <p>
 * <b>When NOT to Use:</b>
 * <ul>
 * <li>For permission/access control violations - use {@link ToolPermissionViolationException}
 * <li>For generic tool system errors - use {@link ToolException}
 * </ul>
 *
 * <p>
 * <b>Exception Hierarchy:</b>
 *
 * <pre>
 * AimonException
 *     └── ToolException (base for all tool errors)
 *         ├── ToolExecutionException (this class - execution failures)
 *         └── ToolPermissionViolationException (permission violations)
 * </pre>
 *
 * <p>
 * <b>Design Notes:</b>
 * <ul>
 * <li>Preserves the original exception cause for debugging (via {@link #getCause()})
 * <li>Error messages should be descriptive and include tool name and context
 * <li>Thread-safe (immutable after construction)
 * <li>Integrates with {@link ToolExecutionResult} for error handling
 * </ul>
 *
 * <p>
 * Example - Wrapping I/O errors:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class ReadTool implements Tool {
 *         public ToolResult execute(ToolUse toolUse, ToolContext context) {
 *             try {
 *                 String content = readFile(toolUse.getInput());
 *                 return ToolResult.success(content);
 *             } catch (IOException e) {
 *                 throw new ToolExecutionException("Failed to read file: " + toolUse.getInput().get("path"), e);
 *             }
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>
 * Example - Invalid parameters:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class BashTool implements Tool {
 *         public ToolResult execute(ToolUse toolUse, ToolContext context) {
 *             String command = (String) toolUse.getInput().get("command");
 *             if (command == null || command.isBlank()) {
 *                 throw new ToolExecutionException("Bash command cannot be null or empty");
 *             }
 *             // Execute command...
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>
 * Example - Timeout handling:
 *
 * <pre>
 * {@code
 * try {
 *     result = executeWithTimeout(tool, toolUse, context, Duration.ofSeconds(30));
 * } catch (TimeoutException e) {
 *     throw new ToolExecutionException("Tool execution timed out after 30 seconds: " + tool.getName(), e);
 * }
 * }
 * </pre>
 *
 * @see ToolException
 * @see ToolPermissionViolationException
 * @see Tool
 * @see ToolExecutionResult
 */
public class ToolExecutionException extends ToolException {
    @Serial
    private static final long serialVersionUID = 5650681013970454951L;

    /**
     * Creates a new ToolExecutionException with the specified message.
     *
     * <p>
     * Use this constructor when the tool execution failed without an underlying exception cause. The error message
     * should clearly describe what went wrong and include relevant context (tool name, parameters, etc.).
     *
     * @param message
     *            The error message describing the execution failure (must not be null)
     * @throws NullPointerException
     *             if message is null
     */
    public ToolExecutionException(String message) {
        super(message);
    }

    /**
     * Creates a new ToolExecutionException with the specified message and cause.
     *
     * <p>
     * Use this constructor to wrap and preserve the original exception that caused the tool execution to fail. This
     * maintains the full stack trace for debugging while providing a tool-specific error message.
     *
     * <p>
     * The error message should provide tool-specific context, while the cause preserves the low-level technical
     * details. For example: message = "Failed to read file: /path/to/file.txt", cause = FileNotFoundException.
     *
     * @param message
     *            The error message describing the execution failure (must not be null)
     * @param cause
     *            The underlying cause of the execution error (must not be null)
     * @throws NullPointerException
     *             if message or cause is null
     */
    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
