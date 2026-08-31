package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook fired immediately after a {@code LiveSession} is opened.
 *
 * <p>
 * Advisory chain — hooks must not block session creation.
 */
@FunctionalInterface
public interface OnSessionStartHook extends ExecutionHook<OnSessionStartContext> {
}
