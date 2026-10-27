package at.aimon.core.scheduling;

import java.util.Optional;

import at.aimon.core.scheduling.scheduler.ScheduledFire;

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
 * <h2>Task id or occurrence</h2>
 *
 * <p>
 * The two hazards need different keys. A lease keyed on the task id prevents overlap, but it cannot stop
 * duplication: once node A's run finishes and releases the lease, node B's fire of the same cron time — late by a GC
 * pause, CPU throttling, or a misfire recovery — finds it free and runs the occurrence again. Holding the lease past
 * the
 * run would block the next occurrence instead. {@link #tryBegin(ScheduledFire)} is the way out: the fire carries its
 * {@link ScheduledFire#getSlot() slot}, the same on every node for the same occurrence, so a distributed guard can
 * record "slot S of task T has run" and refuse any later fire of S while granting S+1.
 *
 * <p>
 * The manager always calls {@link #tryBegin(ScheduledFire)}. Its default forwards to
 * {@link #tryBegin(ScheduledTaskId)},
 * so an existing guard keeps working unchanged and keeps its task-id semantics; a guard that wants occurrence semantics
 * overrides it. The fire's slot is empty when the scheduler did not supply one — fall back to the task id then.
 * {@link InMemoryScheduledExecutionGuard} keeps the task-id behaviour: on one node, overlap is the only hazard.
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
     * Attempts to begin one fire of a task. The lease contract is that of {@link #tryBegin(ScheduledTaskId)}. A guard
     * may additionally refuse a fire whose {@link ScheduledFire#getOccurrenceKey() occurrence} has already run on any
     * node, which is what stops a late duplicate.
     *
     * <p>
     * The default forwards to {@link #tryBegin(ScheduledTaskId)} and ignores the fire's time.
     *
     * @param fire
     *            the fire about to run (must not be null)
     * @return a lease if this caller may execute, or empty if it must skip
     */
    default Optional<ExecutionLease> tryBegin(ScheduledFire fire) {
        return tryBegin(fire.getTaskId());
    }

    /**
     * Handle representing a granted execution claim; closing it releases the claim. {@link #close()} is narrowed to
     * throw no checked exception so it composes cleanly with try-with-resources.
     */
    interface ExecutionLease extends AutoCloseable {
        @Override
        void close();
    }
}
