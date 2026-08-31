package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook executed after each tools call.
 *
 * <p>
 * Called after tools execution completes, with access to the result.
 *
 * <p>
 * Use cases:
 *
 * <ul>
 * <li>Logging tools results
 * <li>Collecting performance metrics (execution time)
 * <li>Error tracking
 * <li>Auditing
 * <li>Sending notifications
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     PostToolHook resultLoggingHook = context -> {
 *         logger.info("Tool '{}' completed: {}", context.getToolUse().getName(),
 *                 context.getToolResult().isError() ? "ERROR" : "SUCCESS");
 *         return HookResult.success();
 *     };
 *
 *     PostToolHook metricsHook = context -> {
 *         metricsService.recordToolExecution(context.getToolUse().getName(), context.getToolResult().isError());
 *         return HookResult.success();
 *     };
 * }
 * </pre>
 */
@FunctionalInterface
public interface PostToolHook extends ExecutionHook<PostToolContext> {
    // Inherits execute(PostToolContext) from ExecutionHook
}
