package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook fired after the hook configuration has been reloaded by the watcher.
 *
 * <p>
 * Application-scoped, advisory chain — hooks observe reload outcomes; they cannot block or roll back the reload.
 */
@FunctionalInterface
public interface OnConfigReloadHook extends ExecutionHook<OnConfigReloadContext> {
}
