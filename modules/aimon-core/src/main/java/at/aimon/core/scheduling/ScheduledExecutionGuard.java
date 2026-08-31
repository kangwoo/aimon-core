package at.aimon.core.scheduling;

import java.util.Optional;

/**
 * Idempotency seam consulted by {@link ScheduledTaskManager} before it fires a scheduled task, so a task is executed
 * at most once per fire even when it could otherwise be triggered more than once.
 *
 * <p>
 * Two triggering hazards this guards against:
 * <ul>
 * <li><b>Overlap</b> — a cron re-fire (or a spurious/duplicate fire) arrives while a previous, long-running execution
 * of the same task is still in progress, piling up concurrent runs.
 * <li><b>Multi-instance duplication</b> — in a scale-out deployment several nodes' schedulers fire the same task at
 * the same cron time. A distributed implementation of this interface (backed by a shared lock/lease store) can grant
 * the lease to exactly one node.
 * </ul>
 *
 * <p>
 * Following the project's multi-instance design rule, the guard is an interface with an in-memory default
 * ({@link InMemoryScheduledExecutionGuard}); swapping to a clustered strategy is an implementation change, not a
 * refactor. (For production clustered scheduling the {@code aimon-scheduling-quartz} module provides trigger-level
 * dedup natively; this seam is the core-level defense-in-depth and injection point.)
 */
public interface ScheduledExecutionGuard {

    /**
     * A guard that grants every request (no deduplication) — every fire executes. Restores the pre-guard behavior for
     * callers that explicitly want it.
     */
    ScheduledExecutionGuard ALLOW_ALL = taskId -> Optional.of(() -> {
    });

    /**
     * Attempts to begin an execution of {@code taskId}. When granted, returns a lease that MUST be closed (ideally via
     * try-with-resources) once the execution finishes so the claim is released; when denied — because a run is already
     * in progress (this node, or, for a distributed guard, any node) — returns {@link Optional#empty()} and the caller
     * must skip execution.
     *
     * @param taskId
     *            the task about to be fired (must not be null)
     * @return a lease if this caller may execute, or empty if it must skip
     */
    Optional<ExecutionLease> tryBegin(ScheduledTaskId taskId);

    /**
     * Handle representing a granted execution claim; closing it releases the claim. {@link #close()} is narrowed to
     * throw no checked exception so it composes cleanly with try-with-resources.
     */
    interface ExecutionLease extends AutoCloseable {
        @Override
        void close();
    }
}
