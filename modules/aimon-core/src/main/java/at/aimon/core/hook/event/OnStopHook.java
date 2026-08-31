package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook executed when agent/subagent execution stops.
 *
 * <p>
 * Called after the final answer is generated or on execution failure.
 *
 * <p>
 * Guaranteed to execute even if execution fails. This makes it ideal for cleanup operations, final logging, and
 * resource deallocation.
 *
 * <p>
 * Use cases:
 *
 * <ul>
 * <li>Logging execution completion
 * <li>Recording final metrics (duration, token usage, iteration count)
 * <li>Sending notifications
 * <li>Cleanup operations
 * <li>Auditing
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OnStopHook completionHook = context -> {
 *         logger.info("Agent '{}' completed in {}ms with {} iterations, tokens: {}", context.getExecutorName(),
 *                 context.getMetadata().getDurationMillis(), context.getMetadata().getIterationCount(),
 *                 context.getMetadata().getTokenUsage().getTotalTokens());
 *         return HookResult.success();
 *     };
 *
 *     OnStopHook metricsHook = context -> {
 *         metricsService.recordExecutionComplete(context.getExecutorName(), context.isSuccess(),
 *                 context.getMetadata());
 *         return HookResult.success();
 *     };
 * }
 * </pre>
 */
@FunctionalInterface
public interface OnStopHook extends ExecutionHook<OnStopContext> {
    // Inherits execute(OnStopContext) from ExecutionHook
}
