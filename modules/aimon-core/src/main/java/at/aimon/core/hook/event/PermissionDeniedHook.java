package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook fired after a {@link PermissionRequestHook} chain produced a deny outcome.
 *
 * <p>
 * Permission-denied hooks are advisory: their {@link at.aimon.core.hook.execution.HookResult HookResults} are
 * collected for observability (logs, metrics, audit trails) but cannot revert the deny decision. The dispatcher will
 * not invoke the underlying tool regardless of what these hooks return.
 *
 * <p>
 * Use cases:
 * <ul>
 * <li>Audit logging of denied permission requests.
 * <li>Counter / alert emission for security dashboards.
 * <li>Surfacing remediation hints back to the user via feedback strings.
 * </ul>
 *
 * <p>
 * Implementations must be thread-safe and must not throw.
 */
@FunctionalInterface
public interface PermissionDeniedHook extends ExecutionHook<PermissionDeniedContext> {
    // Inherits execute(PermissionDeniedContext) from ExecutionHook
}
