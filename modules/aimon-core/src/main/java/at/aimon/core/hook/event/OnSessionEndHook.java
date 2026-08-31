package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook fired when a {@code LiveSession} is closed.
 *
 * <p>
 * Advisory chain — fired for both clean and abnormal terminations; hooks must not block.
 */
@FunctionalInterface
public interface OnSessionEndHook extends ExecutionHook<OnSessionEndContext> {
}
