package at.aimon.core.agent.tool.permission;

import java.util.Objects;

/**
 * Represents the result of a permission validation check.
 *
 * <p>
 * This class provides a functional alternative to exception-based permission validation, allowing callers to handle
 * validation failures as data rather than control flow. This design follows the Result pattern and enables more
 * flexible error handling.
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Result Pattern:</b> Returns success/failure as data, not exceptions
 * <li><b>Immutable:</b> All instances are immutable and thread-safe
 * <li><b>Type-safe:</b> Error message only accessible when validation failed
 * <li><b>Command-Query Separation:</b> Clear separation between validation and error handling
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p>
 * <b>Example 1 - Basic validation check:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     PermissionValidationResult result = validator.validate(toolName, toolInput, allowedTools);
 *
 *     if (result.isAllowed()) {
 *         // Proceed with tool execution
 *         executeTool(toolName, toolInput);
 *     } else {
 *         // Handle validation failure
 *         logger.warn("Permission denied: {}", result.getErrorMessage());
 *     }
 * }
 * </pre>
 *
 * <p>
 * <b>Example 2 - Converting to exception when needed:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     PermissionValidationResult result = validator.validate(toolName, toolInput, allowedTools);
 *
 *     if (!result.isAllowed()) {
 *         throw new ToolPermissionViolationException(toolName, toolInput, result.getErrorMessage());
 *     }
 * }
 * </pre>
 *
 * <p>
 * <b>Example 3 - Batch validation with partial success:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     List&lt;ToolExecutionResult&gt; results = new ArrayList&lt;&gt;();
 *     for (ToolUse toolUse : toolUses) {
 *         PermissionValidationResult validation = validator.validate(toolUse.getName(), toolUse.getInput(),
 *                 allowedTools);
 *
 *         if (!validation.isAllowed()) {
 *             results.add(ToolExecutionResult.error(toolUse.getId(), validation.getErrorMessage()));
 *             continue; // Skip this tool, but continue with others
 *         }
 *
 *         // Execute allowed tool
 *         results.add(executeTool(toolUse));
 *     }
 * }
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * This class is immutable and thread-safe. Instances can be safely shared across multiple threads.
 *
 * @see ToolPermissionValidator
 * @see DefaultToolPermissionValidator
 * @see AllowedTool
 */
public final class PermissionValidationResult {

    private final boolean allowed;
    private final String errorMessage;

    private PermissionValidationResult(boolean allowed, String errorMessage) {
        this.allowed = allowed;
        this.errorMessage = errorMessage;
    }

    /**
     * Creates a validation result indicating permission is granted.
     *
     * @return A successful validation result (never null)
     */
    public static PermissionValidationResult allowed() {
        return new PermissionValidationResult(true, null);
    }

    /**
     * Creates a validation result indicating permission is denied.
     *
     * <p>
     * The error message should provide clear information about why the permission was denied and what tools are
     * allowed.
     *
     * <p>
     * <b>Example error message:</b>
     *
     * <pre>
     * Tool 'Bash' not allowed (command: rm -rf /). Allowed tools: Bash(git:*), Read, Grep
     * </pre>
     *
     * @param errorMessage
     *            Detailed error message explaining why permission was denied (must not be null)
     * @return A failed validation result (never null)
     * @throws NullPointerException
     *             if errorMessage is null
     */
    public static PermissionValidationResult denied(String errorMessage) {
        return new PermissionValidationResult(false,
                Objects.requireNonNull(errorMessage, "Error message cannot be null"));
    }

    /**
     * Checks if the permission validation passed.
     *
     * @return true if permission is granted, false otherwise
     */
    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Checks if the permission validation failed.
     *
     * @return true if permission is denied, false otherwise
     */
    public boolean isDenied() {
        return !allowed;
    }

    /**
     * Gets the error message explaining why permission was denied.
     *
     * <p>
     * This method can only be called when validation failed ({@link #isDenied()} returns true). Calling this method on
     * a successful validation result will throw an exception.
     *
     * @return The error message (never null)
     * @throws IllegalStateException
     *             if called on a successful validation result
     */
    public String getErrorMessage() {
        if (allowed) {
            throw new IllegalStateException("No error message for allowed validation result");
        }
        return errorMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PermissionValidationResult that = (PermissionValidationResult) o;
        return allowed == that.allowed && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowed, errorMessage);
    }

    @Override
    public String toString() {
        if (allowed) {
            return "PermissionValidationResult{allowed}";
        } else {
            return "PermissionValidationResult{denied, message='" + errorMessage + "'}";
        }
    }
}
