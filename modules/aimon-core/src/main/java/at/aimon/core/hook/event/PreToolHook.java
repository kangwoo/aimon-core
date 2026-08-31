package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook executed before each tools call.
 *
 * <p>
 * Called after LLM requests tools use but before tools execution.
 *
 * <p>
 * This is the only hook type that can block operations by returning {@code
 * HookResult.block(reason)}. When blocked, the tools execution is skipped and the block reason is sent to the LLM as a
 * tools error.
 *
 * <p>
 * Use cases:
 *
 * <ul>
 * <li>Logging tools usage
 * <li>Validating tools parameters
 * <li>Collecting metrics
 * <li>Blocking dangerous operations (e.g., rm -rf, sudo commands)
 * <li>Enforcing security policies
 * <li>Rate limiting
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     PreToolHook securityHook = context -> {
 *         if ("Bash".equals(context.getCurrentToolUse().getName())) {
 *             String command = (String) context.getCurrentToolUse().getInput().get("command");
 *             if (command != null &amp;&amp; command.contains("rm -rf")) {
 *                 return HookResult.block("Dangerous command blocked: " + command);
 *             }
 *         }
 *         return HookResult.success();
 *     };
 *
 *     PreToolHook loggingHook = context -> {
 *         logger.info("Calling tools '{}' with params: {}", context.getCurrentToolUse().getName(),
 *                 context.getCurrentToolUse().getInput());
 *         return HookResult.success();
 *     };
 * }
 * </pre>
 */
@FunctionalInterface
public interface PreToolHook extends ExecutionHook<PreToolContext> {
    // Inherits execute(PreToolContext) from ExecutionHook
}
