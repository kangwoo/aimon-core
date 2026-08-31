package at.aimon.core.subagent.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for background subagent task metadata ({@link BackgroundTask} snapshots).
 *
 * <p>
 * This is the multi-instance seam for detached subagent execution. The default {@link InMemoryBackgroundTaskStore}
 * keeps tasks in a per-node map; a scale-out deployment supplies a shared implementation (Redis, a relational table,
 * ...) so that {@code Task.list} and status queries observe tasks spawned on <em>any</em> node. Per the project's
 * multi-instance rule, swapping the backend is an implementation change, not a refactoring.
 *
 * <p>
 * Implementations must be safe for concurrent access from multiple worker threads.
 */
public interface BackgroundTaskStore {

    /**
     * Inserts or replaces the snapshot for a task.
     *
     * @param task
     *            the task snapshot to persist (must not be null)
     */
    void put(BackgroundTask task);

    /**
     * Looks up a task by id.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @return the task snapshot, or empty if unknown
     */
    Optional<BackgroundTask> find(String taskId);

    /**
     * Lists tasks matching the given query.
     *
     * @param query
     *            the filter (must not be null; use {@link TaskQuery#all()} for no filtering)
     * @return a snapshot list of matching tasks (never null; ordering is implementation-defined)
     */
    List<BackgroundTask> list(TaskQuery query);

    /**
     * Atomically transitions a task to a new state, guarding against transitions out of a terminal state.
     *
     * <p>
     * <b>Guard-and-return semantics:</b> if the task is unknown, or is already in a
     * {@link BackgroundTaskState#isTerminal() terminal} state, this is a no-op and returns {@link Optional#empty()} —
     * making completion notification and duplicate {@code stop} requests idempotent. Otherwise the task's state is set
     * to {@code to}; when {@code to} is terminal, the implementation stamps the end time. The updated snapshot is
     * returned.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @param to
     *            the target state (must not be null)
     * @return the updated snapshot, or empty if the transition was rejected (unknown task or already terminal)
     */
    Optional<BackgroundTask> transition(String taskId, BackgroundTaskState to);

    /**
     * Renews a task's lease heartbeat, guarding against renewing a terminal task.
     *
     * <p>
     * <b>Guard-and-return semantics:</b> if the task is unknown, or is already in a
     * {@link BackgroundTaskState#isTerminal() terminal} state, this is a no-op and returns {@link Optional#empty()}.
     * Otherwise the task's {@link BackgroundTask#getLastHeartbeat() last heartbeat} is set to {@code at} and the
     * updated
     * snapshot is returned. Only the heartbeat is evolved — state, times and offset are untouched.
     *
     * <p>
     * The owning node calls this periodically (via {@link TaskHeartbeatPublisher}) for every task it is still running,
     * so
     * that a {@link ZombieTaskReaper} on any node can distinguish a live task (fresh heartbeat) from one whose owner
     * has
     * crashed (heartbeat aged past the lease TTL). The terminal guard mirrors {@link #transition} so a heartbeat can
     * never resurrect a task that completed or was stopped concurrently.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @param at
     *            the heartbeat instant to record (must not be null)
     * @return the updated snapshot, or empty if the renewal was rejected (unknown task or already terminal)
     */
    Optional<BackgroundTask> heartbeat(String taskId, Instant at);

    /**
     * Removes a task's metadata.
     *
     * @param taskId
     *            the task identifier (must not be null)
     */
    void remove(String taskId);
}
