package at.aimon.core.subagent;

import java.util.List;
import java.util.Optional;

import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.TaskQuery;

/**
 * Control-plane operations over background subagent tasks: listing, status inspection, and cooperative stopping.
 *
 * <p>
 * This is the SPI the {@code TaskList} and {@code TaskStop} tools depend on. {@link SubagentExecutionManager} extends
 * it
 * so the same component that spawns background tasks also governs them, but the narrow interface keeps the tools
 * decoupled from the full execution surface (Interface Segregation).
 *
 * <p>
 * Operations are keyed by task id and are safe to call from any thread.
 */
public interface SubagentTaskController {

    /**
     * Requests cooperative cancellation of a running background task.
     *
     * <p>
     * Trips the task's cancellation signal and interrupts its worker thread; the task settles to
     * {@link at.aimon.core.subagent.task.BackgroundTaskState#KILLED KILLED}. Idempotent — a second stop on the same
     * task is a no-op. A stop can only be applied directly on the node that owns the running task. In a scale-out
     * deployment, when the task is not owned by this node but a shared store shows it known and non-terminal, the stop
     * is broadcast to the owning node over a cross-node signal (design §4); such a task also returns {@code true}
     * even though it settles asynchronously on the other node.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @return {@code true} if a stop was requested — either honoured locally on this node, or broadcast to the node
     *         that owns a known, non-terminal task; {@code false} if the task is unknown or already terminal
     */
    boolean stop(String taskId);

    /**
     * Lists background tasks matching the given query.
     *
     * @param query
     *            the filter (must not be null; use {@link TaskQuery#all()} for no filtering)
     * @return a snapshot list of matching tasks (never null; ordering is implementation-defined)
     */
    List<BackgroundTask> list(TaskQuery query);

    /**
     * Looks up the current status snapshot of a background task.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @return the task snapshot, or empty if unknown
     */
    Optional<BackgroundTask> status(String taskId);
}
