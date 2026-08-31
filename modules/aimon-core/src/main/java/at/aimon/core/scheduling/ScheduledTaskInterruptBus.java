/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import at.aimon.core.agent.interrupt.InterruptReason;

/**
 * Cross-node fan-out of "stop this task's runs", so a cancellation entered on one node reaches whichever node is
 * actually running the routine.
 *
 * <p>
 * {@link RoutineExecutor}'s in-flight registry is node-local, so {@code interrupt} can only trip a run that lives in
 * the same JVM. In a scale-out deployment the node a user cancels from is usually not the node the cron happened to
 * fire on, and without this seam the run there would carry on to the end of its remaining steps — writing files,
 * calling out to systems — on behalf of a task that has just been deleted.
 *
 * <p>
 * <b>Only the event is distributed, not the signal.</b> A {@link at.aimon.core.agent.interrupt.CancellationSignal} is
 * a per-run in-memory object and stays one; what has to cross the wire is the fact that somebody asked for a stop.
 * That is the same division the session side makes with {@code SessionSignalBus} (see
 * {@code docs/design/agent-execution/interrupt.md} §11), and it is why this interface carries an id and a reason
 * rather than a coordinator.
 *
 * <p>
 * <b>Why not the session bus.</b> The join key here is {@link ScheduledTaskId}, not a {@code SessionId} — a scheduled
 * routine is an execution with no session at all. Reusing {@code SessionSignalBus} would mean minting a
 * {@code SessionId} that stands for something that is not a session, which is the exact confusion the session-first
 * restructure removed. So this follows {@link ScheduledExecutionGuard} instead: an interface in this package with an
 * in-memory implementation, where going distributed is an implementation swap rather than a refactor.
 *
 * <p>
 * <b>Contract for implementations.</b> Delivery is at-least-once and may echo back to the publishing node; subscribers
 * must tolerate the same request arriving more than once, which {@link RoutineExecutor#interrupt} does because
 * tripping an already-tripped signal is a no-op. Handlers run on the bus's delivery thread and must not block.
 * {@link #publish} is best-effort: it may throw, and callers must not let that abort the operation that asked for the
 * stop.
 *
 * @see ScheduledExecutionGuard
 */
public interface ScheduledTaskInterruptBus {

    /**
     * A bus for a deployment of one node, where there is no other node to tell.
     *
     * <p>
     * This is the default rather than the in-memory fan-out, because on a single node the caller's own
     * {@link RoutineExecutor#interrupt} has already reached the only place a run can be — a fan-out would only deliver
     * the request back to the node that just made it. Wire {@link InMemoryScheduledTaskInterruptBus} when several
     * engines share one JVM, and a distributed implementation when they do not share a process at all.
     */
    ScheduledTaskInterruptBus LOCAL_ONLY = new ScheduledTaskInterruptBus() {

        @Override
        public void publish(ScheduledTaskId taskId, InterruptReason reason) {
            // Nothing to tell: the only node is the one that called this.
        }

        @Override
        public Subscription subscribe(InterruptListener listener) {
            return () -> {
            };
        }
    };

    /**
     * Asks every node to stop the runs of {@code taskId} it is holding.
     *
     * <p>
     * A request, not a join — this returns once the request is out, and each node's runs need until their current step
     * yields to actually unwind. Nothing here reports whether any node had a run to stop; that answer does not exist
     * on a fan-out.
     *
     * @param taskId
     *            the task whose runs should stop (must not be null)
     * @param reason
     *            why (must not be null)
     */
    void publish(ScheduledTaskId taskId, InterruptReason reason);

    /**
     * Registers a listener for stop requests published by any node, including possibly this one.
     *
     * @param listener
     *            invoked for every request received (must not be null)
     * @return a {@link Subscription} the caller closes to unregister
     */
    Subscription subscribe(InterruptListener listener);

    /** Receives stop requests arriving from the bus. */
    @FunctionalInterface
    interface InterruptListener {

        /**
         * Honours a stop request for {@code taskId} on this node, if anything of it is running here.
         *
         * @param taskId
         *            the task whose runs should stop (never null)
         * @param reason
         *            why (never null)
         */
        void onInterruptRequested(ScheduledTaskId taskId, InterruptReason reason);
    }

    /** Handle to a single subscription. Closing it unregisters the listener. */
    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }
}
