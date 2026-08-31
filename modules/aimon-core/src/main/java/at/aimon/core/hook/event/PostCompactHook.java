package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook executed after a successful conversation compaction.
 *
 * <p>
 * PostCompactHook is non-blocking. Use cases include:
 *
 * <ul>
 * <li>Re-attaching recently-read files via the Read tool to restore context lost in the summary.
 * <li>Re-attaching active plan/todo items.
 * <li>Recording metrics ({@code tengu_compact}-style observability events).
 * </ul>
 *
 * <p>
 * Per design rule: hook implementations must not throw exceptions; the executor catches and logs them as warnings
 * without aborting the agent run.
 */
@FunctionalInterface
public interface PostCompactHook extends ExecutionHook<PostCompactContext> {
    // Inherits execute(PostCompactContext) from ExecutionHook
}
