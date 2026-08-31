package at.aimon.core.workflow.impl;

import java.util.Objects;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;

/**
 * Node-local live-cancellation machinery for one background run — a run-shaped analog of {@code RunningTaskHandle}.
 *
 * <p>
 * Holds the per-run {@link InterruptCoordinator} (whose {@link #signal()} is injected into the run's per-run execution
 * environment so its fan-out subagents observe a stop), the captured hosting worker thread, and the stop flag. Like the
 * subagent handle, {@link #requestStop()} both trips the signal AND interrupts the worker — {@code CompletableFuture}
 * cancellation does not interrupt a running body, and the interrupt unblocks any interruptible wait the worker is in
 * (cooperative stop still relies on the subagents polling the signal at their checkpoints).
 */
final class RunControl {

    private final InterruptCoordinator coordinator;
    private final CancellationSignal.Registration parentReg;
    private volatile Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean failureTrip;

    /**
     * @param coordinator
     *            the per-run interrupt coordinator (must not be null)
     * @param parentReg
     *            the retained registration of a parent-signal cascade to remove on close, or null when none
     */
    RunControl(InterruptCoordinator coordinator, CancellationSignal.Registration parentReg) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator cannot be null");
        this.parentReg = parentReg;
    }

    /** @return the run's cancellation signal (injected into the per-run environment). */
    CancellationSignal signal() {
        return coordinator.getSignal();
    }

    /** Records the hosting worker thread so a later {@link #requestStop()} can interrupt it. */
    void attachWorker(Thread workerThread) {
        this.worker = Objects.requireNonNull(workerThread, "workerThread cannot be null");
    }

    /** @return {@code true} if a stop has been requested for this run. */
    boolean isStopRequested() {
        return stopRequested;
    }

    /**
     * Requests cooperative cancellation: marks the stop flag, trips the per-run signal (so the run's subagents observe
     * it), and interrupts the hosting worker if it has started. Idempotent.
     */
    void requestStop() {
        stopRequested = true;
        coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED);
        final Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
    }

    /**
     * Trips the per-run signal because the run's body aborted (run-fatal failure), <em>without</em> marking a stop
     * request — the finalizer must still record FAILED, not KILLED. In-flight fan-out orphan leaves observe the trip
     * at their next checkpoint and stop consuming budget and shared leaf permits for a run whose outcome is already
     * settled (design §6.2). Must be called BEFORE the run's future settles: the finalizer closes the
     * coordinator, after which a trip is a no-op.
     */
    void tripOnFailure() {
        failureTrip = true;
        coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED);
    }

    /** @return {@code true} if the signal was tripped by {@link #tripOnFailure()} (a failure abort, not a stop). */
    boolean isFailureTrip() {
        return failureTrip;
    }

    /** Releases per-run resources: closes the coordinator and removes the parent-cascade registration. */
    void close() {
        coordinator.close();
        if (parentReg != null) {
            parentReg.remove();
        }
    }
}
