package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook executed when agent/subagent execution starts.
 *
 * <p>
 * Called after context initialization but before the first LLM call.
 *
 * <p>
 * Use cases:
 *
 * <ul>
 * <li>Logging the start of execution
 * <li>Recording metrics (start time, user message length)
 * <li>Sending notifications
 * <li>Initializing resources
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OnStartHook loggingHook = context -> {
 *         logger.info("Agent '{}' starting execution: {}", context.getExecutorName(), context.getUserMessage());
 *         return HookResult.success();
 *     };
 *
 *     OnStartHook metricsHook = context -> {
 *         metricsService.recordExecutionStart(context.getExecutorName(), context.getTimestamp());
 *         return HookResult.success();
 *     };
 * }
 * </pre>
 */
@FunctionalInterface
public interface OnStartHook extends ExecutionHook<OnStartContext> {
    // Inherits execute(OnStartContext) from ExecutionHook
}
