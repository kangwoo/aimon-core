package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook fired after a subagent (dispatched via the Task tool) finishes execution.
 *
 * <p>
 * Advisory chain — hooks must not block. Use cases include audit/logging and metrics emission for both successful and
 * failed dispatches.
 */
@FunctionalInterface
public interface SubagentStopHook extends ExecutionHook<SubagentStopContext> {
}
