package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook fired immediately before a subagent (dispatched via the Task tool) begins execution.
 *
 * <p>
 * Advisory chain — hooks must not block. Use cases include audit/logging, metrics seeding, and forwarding parent trace
 * ids to the subagent.
 */
@FunctionalInterface
public interface SubagentStartHook extends ExecutionHook<SubagentStartContext> {
}
