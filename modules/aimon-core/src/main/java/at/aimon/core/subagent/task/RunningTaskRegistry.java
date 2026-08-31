package at.aimon.core.subagent.task;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Node-local registry of {@link RunningTaskHandle}s, keyed by task id.
 *
 * <p>
 * Complements {@link BackgroundTaskStore}: the store holds durable metadata that can be shared across nodes, while this
 * registry holds the live, non-serialisable execution handles for the tasks running on <em>this</em> node. A stop
 * request resolves its handle here; a task with no local handle is either finished (handle already evicted) or running
 * on a different node.
 *
 * <p>
 * Thread-safe via {@link ConcurrentHashMap}.
 */
public final class RunningTaskRegistry {

    private final ConcurrentMap<String, RunningTaskHandle> handles = new ConcurrentHashMap<>();

    /**
     * Registers a handle for a running task.
     *
     * @param handle
     *            the handle (must not be null)
     */
    public void register(RunningTaskHandle handle) {
        Objects.requireNonNull(handle, "handle cannot be null");
        handles.put(handle.getTaskId(), handle);
    }

    /**
     * Looks up the handle for a task.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @return the handle, or empty if no such task runs on this node
     */
    public Optional<RunningTaskHandle> find(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        return Optional.ofNullable(handles.get(taskId));
    }

    /**
     * Removes the handle for a task (typically on completion).
     *
     * @param taskId
     *            the task identifier (must not be null)
     */
    public void remove(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        handles.remove(taskId);
    }

    /**
     * @return the number of tasks currently running on this node
     */
    public int size() {
        return handles.size();
    }

    /**
     * Returns a point-in-time snapshot of the ids of all tasks with a live handle on this node. A handle is registered
     * from task submission (while still {@code PENDING} in the queue) until it reaches a terminal state and is removed,
     * so this set covers every non-terminal task this node owns. The {@link TaskHeartbeatPublisher} renews the lease of
     * exactly these ids, which is why a live node never lets its own queued-or-running tasks be reaped as zombies.
     *
     * @return a new, detached set of the currently-held task ids (never null; safe to iterate)
     */
    public Set<String> taskIds() {
        return new HashSet<>(handles.keySet());
    }
}
