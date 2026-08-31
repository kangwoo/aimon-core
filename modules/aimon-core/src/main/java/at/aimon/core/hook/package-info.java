/**
 * Hook system for lifecycle callbacks and observability.
 *
 * <p>
 * This package provides a flexible hook system that enables observability, logging, metrics collection, and external
 * integrations through lifecycle callbacks at key execution points.
 *
 * <h2>Hook Types</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.hook.event.OnStartHook} - Before agent execution starts
 * <li>{@link at.aimon.core.hook.event.PreToolHook} - Before each tool execution
 * <li>{@link at.aimon.core.hook.event.PostToolHook} - After each tool execution
 * <li>{@link at.aimon.core.hook.event.OnStopHook} - After agent execution completes
 * </ul>
 *
 * <h2>Hook Capabilities</h2>
 *
 * <p>
 * Hooks can:
 *
 * <ul>
 * <li>Observe execution state and collect metrics
 * <li>Log events for debugging and auditing
 * <li>Validate permissions and block execution
 * <li>Integrate with external systems (monitoring, alerting, etc.)
 * <li>Modify execution context (with caution)
 * </ul>
 *
 * <h2>Hook Results</h2>
 *
 * <p>
 * Each hook returns a {@link at.aimon.core.hook.execution.HookResult} with:
 *
 * <ul>
 * <li>{@link at.aimon.core.hook.execution.HookStatus#SUCCESS} - Continue execution normally
 * <li>{@link at.aimon.core.hook.execution.HookStatus#BLOCKED} - Block execution with reason
 * <li>ERROR - Hook failed (execution continues)
 * </ul>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Fast and Non-Blocking</b> - Hooks should execute quickly
 * <li><b>No Exceptions</b> - Return error results instead of throwing
 * <li><b>Stateless</b> - Prefer stateless implementations
 * <li><b>Defensive</b> - Handle all edge cases gracefully
 * </ul>
 *
 * <h2>Example Implementation</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     public class MetricsCollectionHook implements OnStartHook {
 *         private final MetricsCollector metrics;
 *
 *         &#64;Override
 *         public HookResult execute(OnStartContext context) {
 *             metrics.recordAgentStart(context.getExecutorName());
 *             return HookResult.success();
 *         }
 *     }
 *
 *     // Register the hook
 *     HookRegistry registry = new DefaultHookRegistry();
 *     registry.register(HookEventType.ON_START, new MetricsCollectionHook(metrics));
 * }
 * </pre>
 *
 * <h2>Blocking Execution</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     public class PermissionCheckHook implements PreToolHook {
 *         &#64;Override
 *         public HookResult execute(PreToolContext context) {
 *             if (!hasPermission(context.getToolName())) {
 *                 return HookResult.blocked("Permission denied for tool: " + context.getToolName());
 *             }
 *             return HookResult.success();
 *         }
 *     }
 * }
 * </pre>
 *
 * @see at.aimon.core.hook.execution.ExecutionHook
 * @see at.aimon.core.hook.HookRegistry
 * @see at.aimon.core.hook.HookExecutionManager
 */
package at.aimon.core.hook;
