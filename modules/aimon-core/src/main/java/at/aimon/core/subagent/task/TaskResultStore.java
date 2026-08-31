package at.aimon.core.subagent.task;

import java.util.Optional;

/**
 * Storage abstraction for the <b>final result</b> of a background subagent task.
 *
 * <p>
 * This closes the last node-local hole in the background-task surface. Lifecycle metadata lives in
 * {@link BackgroundTaskStore} and the incremental progress log in {@link TaskOutputStore}, both of which a scale-out
 * deployment can share; until this SPI existed, the result itself lived only in a node-local
 * {@code CompletableFuture} holder, so a task started on one node was listable and stoppable from another but its
 * output was not retrievable there, and a restart lost it entirely.
 *
 * <p>
 * <b>One result per task, written once.</b> Unlike the append-only {@link TaskOutputStore}, a task settles exactly once
 * and its result is a single immutable {@link TaskResult}. Implementations are last-write-wins so a duplicate save (a
 * retry, a racing finalizer) is harmless rather than an error.
 *
 * <p>
 * <b>Ordering contract — save before the terminal transition.</b> Writers must persist the result <em>before</em>
 * moving the task to a terminal {@link BackgroundTaskState} in {@link BackgroundTaskStore}. Readers rely on the
 * resulting invariant: <em>a terminal state observed implies the result is already visible</em>. Without it, a poller
 * that saw {@code COMPLETED} and found no result could not tell "not written yet" from "there is no result", and would
 * have to keep waiting for something that already happened. With it, a terminal state plus an absent result means
 * exactly one thing — the task produced none (it was reaped, killed before it ran, or its result was evicted).
 *
 * <p>
 * <b>Not owner-tagged.</b> Unlike {@link SessionSnapshotStore}, whose entries carry the owning
 * {@link at.aimon.core.agent.AgentRuntimeId} because {@code Task(resume=…)} loads a transcript <em>into</em> the
 * caller's own execution, this store holds inert text. Authorization happens one layer up: the {@code AgentOutput} tool
 * resolves the task through a {@code ScopedSubagentTaskController} first and only reads a result for a task the caller
 * already owns. Tagging here would duplicate that check in a second place that could drift from it. This matches its
 * sibling {@link TaskOutputStore}, which is untagged for the same reason and keyed by the same {@code taskId}.
 *
 * <p>
 * <b>Best-effort.</b> No method throws for routine backend errors: a failed save must never change the result the task
 * returns to its caller, and an unreadable or malformed entry loads as {@link Optional#empty()}, exactly as an evicted
 * one would. Implementations must be safe for concurrent access — a subagent thread saving a terminal result may race
 * with a parent agent polling for it.
 */
public interface TaskResultStore {

    /**
     * Records the final result of a task, replacing any result already stored under the same id.
     *
     * <p>
     * Must be called before the task's terminal {@link BackgroundTaskState} transition — see the ordering contract in
     * the type javadoc. Best-effort: implementations must not throw for routine backend errors.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @param result
     *            the result to persist (must not be null)
     */
    void save(String taskId, TaskResult result);

    /**
     * Loads a task's final result.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @return the stored result, or empty when the task is unknown, has not settled yet, produced no result, or its
     *         entry is unreadable
     */
    Optional<TaskResult> load(String taskId);

    /**
     * Discards the result recorded for a task, releasing its storage.
     *
     * <p>
     * A no-op when the task is unknown. Best-effort: implementations must not throw for routine backend errors.
     *
     * @param taskId
     *            the task identifier (must not be null)
     */
    void evict(String taskId);
}
