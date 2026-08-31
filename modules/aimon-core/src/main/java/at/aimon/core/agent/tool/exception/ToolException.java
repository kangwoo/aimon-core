package at.aimon.core.agent.tool.exception;

import java.io.Serial;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.base.exception.AimonException;

/**
 * Base exception for all tool-related errors in the Aimon agent system.
 *
 * <p>
 * This exception serves as the root of the tool exception hierarchy and is thrown when generic tool-related errors
 * occur that don't fit into more specific categories. For most cases, prefer using one of the specialized subclasses:
 *
 * <ul>
 * <li>{@link ToolExecutionException} - For runtime errors during tool execution
 * <li>{@link ToolPermissionViolationException} - For permission and access control violations
 * </ul>
 *
 * <p>
 * <b>Exception Hierarchy:</b>
 *
 * <pre>
 * AimonException
 *     └── ToolException (base for all tool errors)
 *         ├── ToolExecutionException (execution failures)
 *         └── ToolPermissionViolationException (permission violations)
 * </pre>
 *
 * <p>
 * <b>When to use ToolException directly:</b>
 * <ul>
 * <li>Generic tool-related errors that don't fit specialized categories
 * <li>As a catch-all for unexpected tool system errors
 * <li>When creating custom tool exception types (extend this class)
 * </ul>
 *
 * <p>
 * <b>When to use subclasses:</b>
 * <ul>
 * <li>Use {@link ToolExecutionException} for errors during tool execution (I/O errors, invalid parameters, timeouts)
 * <li>Use {@link ToolPermissionViolationException} for access control violations (unauthorized tool usage)
 * </ul>
 *
 * <p>
 * <b>Design Notes:</b>
 * <ul>
 * <li>Extends {@link AimonException} to integrate with the overall exception hierarchy
 * <li>Provides both message-only and message-with-cause constructors
 * <li>Follows standard Java exception conventions
 * <li>Thread-safe (exceptions are immutable after construction)
 * </ul>
 *
 * <p>
 * Example - Using base ToolException:
 *
 * <pre>
 * {@code
 * // For generic tool system errors
 * if (!toolRegistry.isInitialized()) {
 *     throw new ToolException("Tool registry not initialized");
 * }
 *
 * // Wrapping unexpected errors
 * try {
 *     registerTool(tool);
 * } catch (IllegalStateException e) {
 *     throw new ToolException("Failed to register tool: " + tool.getName(), e);
 * }
 * }
 * </pre>
 *
 * <p>
 * Example - Creating custom tool exception:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class ToolNotFoundException extends ToolException {
 *         private final String toolName;
 *
 *         public ToolNotFoundException(String toolName) {
 *             super("Tool not found: " + toolName);
 *             this.toolName = toolName;
 *         }
 *
 *         public String getToolName() {
 *             return toolName;
 *         }
 *     }
 * }
 * </pre>
 *
 * @see ToolExecutionException
 * @see ToolPermissionViolationException
 * @see Tool
 * @see ToolRegistry
 */
public class ToolException extends AimonException {

    @Serial
    private static final long serialVersionUID = 3154549539937699065L;

    /**
     * Creates a new ToolException with the specified message.
     *
     * <p>
     * Use this constructor when the error has no underlying cause.
     *
     * @param message
     *            The error message describing the tool-related error (must not be null)
     * @throws NullPointerException
     *             if message is null
     */
    public ToolException(String message) {
        super(message);
    }

    /**
     * Creates a new ToolException with the specified message and cause.
     *
     * <p>
     * Use this constructor to wrap and preserve the original exception that caused the tool error. This maintains the
     * full stack trace for debugging.
     *
     * @param message
     *            The error message describing the tool-related error (must not be null)
     * @param cause
     *            The underlying cause of the error (must not be null)
     * @throws NullPointerException
     *             if message or cause is null
     */
    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
