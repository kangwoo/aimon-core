package at.aimon.core.agent.tool.exception;

import java.io.Serial;
import java.util.Map;
import java.util.Optional;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.DefaultToolPermissionValidator;
import at.aimon.core.agent.tool.permission.ToolPermissionValidator;

/**
 * Exception thrown when tool usage violates permission policies.
 *
 * <p>
 * This exception indicates that an attempt was made to use a tool that is not permitted by the current permission
 * configuration. It is a specialized subclass of {@link ToolException} used specifically for access control violations,
 * as opposed to execution failures (see {@link ToolExecutionException}).
 *
 * <p>
 * <b>Common Violation Scenarios:</b>
 *
 * <ul>
 * <li><b>Tool Not Allowed:</b> The tool name is not in the allowed-tools list
 * <li><b>Pattern Mismatch:</b> A Bash command doesn't match any allowed command patterns
 * <li><b>Restricted Tool:</b> An attempt to use a tool that is explicitly restricted
 * <li><b>Missing Permissions:</b> Required permissions are not granted for the operation
 * </ul>
 *
 * <p>
 * <b>When to Use:</b>
 * <ul>
 * <li>Tool is not in the allowed-tools configuration
 * <li>Bash command doesn't match allowed patterns (e.g., "git:*")
 * <li>Command execution requires permissions that are not granted
 * <li>Tool usage is explicitly forbidden by policy
 * </ul>
 *
 * <p>
 * <b>When NOT to Use:</b>
 * <ul>
 * <li>For runtime execution failures - use {@link ToolExecutionException}
 * <li>For generic tool errors - use {@link ToolException}
 * </ul>
 *
 * <p>
 * <b>Exception Hierarchy:</b>
 *
 * <pre>
 * AimonException
 *     └── ToolException (base for all tool errors)
 *         ├── ToolExecutionException (execution failures)
 *         └── ToolPermissionViolationException (this class - permission violations)
 * </pre>
 *
 * <p>
 * <b>Design Notes:</b>
 * <ul>
 * <li>Stores tool name and input for debugging and audit logging
 * <li>Uses defensive copy ({@code Map.copyOf()}) to ensure immutability
 * <li>Returns {@link Optional} for null-safe access to optional fields
 * <li>Thread-safe (immutable after construction)
 * <li>Integrates with {@link ToolPermissionValidator}
 * </ul>
 *
 * <p>
 * <b>Permission System Integration:</b>
 *
 * <p>
 * This exception is typically thrown by {@link ToolPermissionValidator} implementations when validating tool usage
 * against allowed-tools configuration:
 *
 * <pre>
 * allowed-tools:
 *   - Read
 *   - Grep
 *   - Bash(git:*)      # Only git commands allowed
 * </pre>
 *
 * <p>
 * Example - Tool not in allowed list:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolPermissionValidator validator = new DefaultToolPermissionValidator();
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.parse("Read"), AllowedTool.parse("Grep"));
 *
 *     // Throws: the name is absent from the list
 *     validator.validateOrThrow(editTool, input, context, allowedTools);
 * }
 * </pre>
 *
 * <p>
 * Example - Bash command pattern violation:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolPermissionValidator validator = new DefaultToolPermissionValidator();
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.parse("Bash(git:*)")); // Only git commands
 *
 *     // Throws: BashTool offers "rm -rf /" as its COMMAND subject, which does not match the pattern
 *     validator.validateOrThrow(bashTool, ToolInput.of("command", "rm -rf /"), context, allowedTools);
 * }
 * </pre>
 *
 * <p>
 * Example - Accessing violation details:
 *
 * <pre>
 * {@code
 * try {
 *     executeTool(toolUse, context);
 * } catch (ToolPermissionViolationException e) {
 *     logger.warn("Permission violation: {}", e.getMessage());
 *
 *     e.getToolName().ifPresent(name -> logger.debug("Attempted tool: {}", name));
 *
 *     e.getToolInput().ifPresent(input -> logger.debug("Tool input: {}", input));
 *
 *     // Re-throw or handle appropriately
 *     throw e;
 * }
 * }
 * </pre>
 *
 * @see ToolException
 * @see ToolExecutionException
 * @see ToolPermissionValidator
 * @see DefaultToolPermissionValidator
 * @see AllowedTool
 */
public class ToolPermissionViolationException extends ToolException {
    @Serial
    private static final long serialVersionUID = -1719386816923464824L;

    private final String toolName;
    private final Map<String, Object> toolInput;

    /**
     * Creates a new ToolPermissionViolationException with the specified message.
     *
     * <p>
     * Use this constructor when the permission violation doesn't have specific tool details to attach. The error
     * message should clearly describe the violation and include what was attempted and what is allowed.
     *
     * <p>
     * <b>Note:</b> This constructor does not capture tool name or input. For violations related to specific tool usage,
     * prefer using {@link #ToolPermissionViolationException(String, Map, String)} to preserve debugging context.
     *
     * @param message
     *            The error message describing the permission violation (must not be null)
     * @throws NullPointerException
     *             if message is null
     */
    public ToolPermissionViolationException(String message) {
        super(message);
        this.toolName = null;
        this.toolInput = null;
    }

    /**
     * Creates a new ToolPermissionViolationException with tool details for debugging.
     *
     * <p>
     * Use this constructor to capture the specific tool and input that caused the permission violation. This preserves
     * valuable context for debugging, audit logging, and error reporting.
     *
     * <p>
     * The tool input is defensively copied using {@link Map#copyOf(Map)} to ensure immutability. If toolInput is null,
     * it is stored as null (use {@link #getToolInput()} to access safely).
     *
     * <p>
     * <b>Best Practice:</b> Include both the attempted operation and allowed operations in the message:
     *
     * <pre>
     * {
     *     &#64;code
     *     String message = String.format("Tool '%s' not allowed. Allowed tools: %s", toolName,
     *             String.join(", ", allowedToolNames));
     *     throw new ToolPermissionViolationException(toolName, toolInput, message);
     * }
     * </pre>
     *
     * @param toolName
     *            The name of the tool that was not allowed (may be null, prefer non-null for debugging)
     * @param toolInput
     *            The input parameters for the tool (may be null, will be defensively copied if non-null)
     * @param message
     *            The error message describing the permission violation (must not be null)
     * @throws NullPointerException
     *             if message is null
     */
    public ToolPermissionViolationException(String toolName, Map<String, Object> toolInput, String message) {
        super(message);
        this.toolName = toolName;
        this.toolInput = toolInput != null ? Map.copyOf(toolInput) : null;
    }

    /**
     * Returns the name of the tool that caused the violation.
     *
     * <p>
     * This information is useful for debugging, audit logging, and generating detailed error reports. The tool name is
     * only available if the exception was created using {@link #ToolPermissionViolationException(String, Map, String)}.
     *
     * @return An Optional containing the tool name, or empty if not specified
     */
    public Optional<String> getToolName() {
        return Optional.ofNullable(toolName);
    }

    /**
     * Returns the input parameters of the tool that caused the violation.
     *
     * <p>
     * This information is useful for debugging, audit logging, and understanding exactly what operation was attempted.
     * The tool input is only available if the exception was created using
     * {@link #ToolPermissionViolationException(String, Map, String)}.
     *
     * <p>
     * If present, the returned map is immutable (created via {@link Map#copyOf(Map)}).
     *
     * @return An Optional containing the tool input map, or empty if not specified
     */
    public Optional<Map<String, Object>> getToolInput() {
        return Optional.ofNullable(toolInput);
    }
}
