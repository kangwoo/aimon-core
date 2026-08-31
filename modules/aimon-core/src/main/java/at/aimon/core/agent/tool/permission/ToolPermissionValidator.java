package at.aimon.core.agent.tool.permission;

import java.util.List;
import java.util.Map;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.exception.ToolPermissionViolationException;

/**
 * Validates tool usage against permission policies.
 *
 * <p>
 * This interface defines the contract for validating tool invocations against allowed-tools configurations. Different
 * implementations can provide specialized validation logic for specific tools (e.g., Bash command pattern matching) or
 * general-purpose validation.
 *
 * <h2>Why the first argument is a {@link Tool}, not a name</h2>
 *
 * <p>
 * A pattern such as {@code Bash(git:*)} or {@code Read(/tmp/**)} has to be matched against <i>something</i>, and only
 * the tool knows which of its inputs that is. So validation asks the instance: a tool implementing
 * {@link ToolPermissionSubjectAware} names its {@link PermissionSubject}, and one implementing
 * {@link CustomToolPermissionAware} supplies a rule that judges for itself. Neither can be reached from a bare name.
 * The one case with no instance to ask — a name the registry does not know — has its own entry point,
 * {@link #validateByName(String, List)}.
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Strategy Pattern (OCP):</b> Open for extension (add new validators), closed for modification
 * <li><b>SRP:</b> Each implementation focuses on specific validation logic
 * <li><b>Stateless:</b> All implementations should be stateless and thread-safe
 * <li><b>Pluggable Rules:</b> Can be extended with custom rules via {@link CustomToolPermissionRule}
 * </ul>
 *
 * <h2>Implementations</h2>
 *
 * <ul>
 * <li>{@link DefaultToolPermissionValidator} - Subject-and-rule validation
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p>
 * <b>Example 1 - Basic usage with default validator:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolPermissionValidator validator = new DefaultToolPermissionValidator();
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.parse("Read"), AllowedTool.parse("Grep"));
 *
 *     PermissionValidationResult result = validator.validate(readTool, input, context, allowedTools);
 *     if (result.isAllowed()) {
 *         // Execute Read tool
 *     }
 * }
 * </pre>
 *
 * <p>
 * <b>Example 2 - Pattern validation, command and path:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolPermissionValidator validator = new DefaultToolPermissionValidator();
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.parse("Bash(git:*)"), AllowedTool.parse("Read(/tmp/**)"));
 *
 *     // BashTool's subject is COMMAND, so the pattern is matched by ToolPattern
 *     validator.validateOrThrow(bashTool, ToolInput.of("command", "git add ."), context, allowedTools); // OK
 *     validator.validateOrThrow(bashTool, ToolInput.of("command", "rm -rf /"), context, allowedTools); // Throws
 *
 *     // ReadTool's subject is PATH, so the pattern is matched by PathPattern
 *     validator.validateOrThrow(readTool, ToolInput.of("file_path", "/tmp/a.txt"), context, allowedTools); // OK
 *     validator.validateOrThrow(readTool, ToolInput.of("file_path", "/etc/passwd"), context, allowedTools); // Throws
 * }
 * </pre>
 *
 * <p>
 * <b>Example 3 - A name the registry does not know:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     // No instance to ask for a subject; the name alone decides
 *     validator.validateByNameOrThrow("MadeUpTool", allowedTools);
 * }
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * All implementations must be stateless and thread-safe. The validation methods should be pure functions with no side
 * effects, making them safe to call from multiple threads concurrently.
 *
 * @see DefaultToolPermissionValidator
 * @see ToolPermissionSubjectAware
 * @see CustomToolPermissionRule
 * @see AllowedTool
 * @see ToolPermissionViolationException
 */
public interface ToolPermissionValidator {

    /**
     * Validates a tool invocation and returns a result object.
     *
     * <p>
     * This method provides a functional alternative to {@link #validateOrThrow(Tool, ToolInput, ToolContext, List)} by
     * returning a {@link PermissionValidationResult} instead of throwing an exception. This design enables more
     * flexible error handling and allows processing multiple tool validations without stopping on first failure.
     *
     * <h3>Result Handling</h3>
     *
     * <p>
     * The returned {@link PermissionValidationResult} indicates:
     * <ul>
     * <li>{@link PermissionValidationResult#isAllowed()} - Whether permission is granted
     * <li>{@link PermissionValidationResult#getErrorMessage()} - Detailed error message if denied
     * </ul>
     *
     * @param tool
     *            The tool being invoked (must not be null)
     * @param input
     *            Input parameters for the invocation (must not be null)
     * @param context
     *            Runtime context for the invocation (must not be null)
     * @param allowedTools
     *            List of allowed tools (must not be null)
     * @return Validation result indicating success or failure with error message (never null)
     * @throws NullPointerException
     *             if any argument is null
     */
    PermissionValidationResult validate(Tool tool, ToolInput input, ToolContext context,
            List<AllowedTool> allowedTools);

    /**
     * Validates a tool name on its own, with no instance to consult.
     *
     * <p>
     * This is the entry point for a name that could not be resolved to a {@link Tool} — a name the model invented, or
     * one whose provider is not registered. There is no instance, therefore no {@link PermissionSubject} and no
     * {@link CustomToolPermissionRule}; only the name-level rules apply (empty list permits, an unlisted name is
     * denied, a listed name with a pattern cannot be judged and is therefore denied).
     *
     * <p>
     * A call reaching here is going to fail with {@code "Unknown tool: …"} at execution anyway. It is validated first
     * so that an invented name does not read differently from a forbidden one in the audit trail.
     *
     * @param toolName
     *            Name of the tool (must not be null)
     * @param allowedTools
     *            List of allowed tools (must not be null)
     * @return Validation result indicating success or failure with error message (never null)
     * @throws NullPointerException
     *             if toolName or allowedTools is null
     */
    PermissionValidationResult validateByName(String toolName, List<AllowedTool> allowedTools);

    /**
     * Validates a tool invocation and throws an exception if it is not allowed.
     *
     * <p>
     * If validation fails, this throws a {@link ToolPermissionViolationException} carrying the tool name, the input
     * parameters (for audit logging), and a descriptive message listing the allowed tools.
     *
     * <p>
     * <b>Example error message:</b>
     *
     * <pre>
     * Tool 'Edit' not allowed (path: /etc/passwd). Allowed tools: Read, Grep, Bash(git:*)
     * </pre>
     *
     * @param tool
     *            The tool being invoked (must not be null)
     * @param input
     *            Input parameters for the invocation (must not be null)
     * @param context
     *            Runtime context for the invocation (must not be null)
     * @param allowedTools
     *            List of allowed tools (must not be null)
     * @throws ToolPermissionViolationException
     *             if the tool usage is not allowed
     * @throws NullPointerException
     *             if any argument is null
     */
    default void validateOrThrow(Tool tool, ToolInput input, ToolContext context, List<AllowedTool> allowedTools) {
        final PermissionValidationResult result = validate(tool, input, context, allowedTools);
        if (!result.isAllowed()) {
            throw new ToolPermissionViolationException(tool.getDefinition().getName(), input.toMap(),
                    result.getErrorMessage());
        }
    }

    /**
     * Validates a tool name on its own and throws an exception if it is not allowed.
     *
     * @param toolName
     *            Name of the tool (must not be null)
     * @param allowedTools
     *            List of allowed tools (must not be null)
     * @throws ToolPermissionViolationException
     *             if the tool usage is not allowed
     * @throws NullPointerException
     *             if toolName or allowedTools is null
     * @see #validateByName(String, List)
     */
    default void validateByNameOrThrow(String toolName, List<AllowedTool> allowedTools) {
        final PermissionValidationResult result = validateByName(toolName, allowedTools);
        if (!result.isAllowed()) {
            throw new ToolPermissionViolationException(toolName, Map.of(), result.getErrorMessage());
        }
    }

    /**
     * Checks whether the given allowed tools list has any restrictions.
     *
     * <p>
     * This utility method helps determine if permission validation is active. When the allowed tools list is empty, no
     * restrictions apply and all tools are permitted.
     *
     * <h3>Usage Example</h3>
     *
     * <pre>
     * {@code
     * if (validator.hasRestrictions(allowedTools)) {
     *     // Perform validation
     *     validator.validateOrThrow(tool, input, context, allowedTools);
     * } else {
     *     // No restrictions, skip validation
     *     executeTool(tool, input);
     * }
     * }
     * </pre>
     *
     * @param allowedTools
     *            List of allowed tools (must not be null)
     * @return true if allowed tools list is non-empty (has restrictions), false otherwise (no restrictions)
     */
    default boolean hasRestrictions(List<AllowedTool> allowedTools) {
        if (allowedTools == null) {
            throw new NullPointerException("Allowed tools cannot be null");
        }
        return !allowedTools.isEmpty();
    }

}
