package at.aimon.core.scheduling;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, node-local {@link ScheduledExecutionGuard} that prevents overlapping executions of the same task on this
 * instance: {@link #tryBegin(ScheduledTaskId)} grants a lease only when no execution of that task id is currently
 * in progress here, and the lease releases the claim on {@link ExecutionLease#close() close}.
 *
 * <p>
 * This is the reference/default implementation and is the correct behavior for a single-instance deployment. It does
 * not coordinate across nodes; a clustered deployment should inject a distributed guard (or use the Quartz scheduler,
 * which dedups at the trigger level) so a task fires once across the cluster rather than once per node.
 *
 * <p>
 * Thread-safe: the in-flight set is a {@link ConcurrentHashMap}-backed set, so {@code tryBegin} is an atomic
 * check-and-claim.
 */
public final class InMemoryScheduledExecutionGuard implements ScheduledExecutionGuard {

    private final Set<ScheduledTaskId> inFlight = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<ExecutionLease> tryBegin(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        // add() returns false if the id was already present — i.e. a run for this task is already in progress here.
        if (!inFlight.add(taskId)) {
            return Optional.empty();
        }
        return Optional.of(() -> inFlight.remove(taskId));
    }

    /**
     * Returns whether an execution of {@code taskId} is currently in progress on this instance. Intended for tests and
     * diagnostics.
     *
     * @param taskId
     *            the task id to check (must not be null)
     * @return {@code true} if a lease is currently held for the task
     */
    public boolean isInProgress(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        return inFlight.contains(taskId);
    }
}
