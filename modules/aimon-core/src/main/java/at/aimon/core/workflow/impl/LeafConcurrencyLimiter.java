package at.aimon.core.workflow.impl;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.CancelledExecutionException;
import at.aimon.core.agent.interrupt.InterruptReason;

/**
 * The single global ceiling on concurrent {@code manager.execute} (LLM) calls across every run that shares the runner's
 * fan-out pool (design §6.2).
 *
 * <p>
 * When the fan-out pool became an unbounded cached pool, thread count stopped bounding LLM concurrency, so this
 * runner-owned {@link Semaphore} became the authoritative LLM-concurrency limit. A permit is acquired <b>only around
 * the
 * terminal leaf</b> (the {@code manager.execute} call) and released immediately after — never held across a fan-out
 * join or the opaque dispatcher thunk. Gating the thunk would hold a permit across a nested join and reintroduce a
 * permit-starvation deadlock (design §6.2); gating only the non-recursive leaf keeps the wait-for graph acyclic,
 * so
 * no permit-holder ever waits on another permit.
 *
 * <p>
 * <b>Acquire release contract (design §6.2).</b> A blocked {@link #around} is released by exactly two paths: (i)
 * <em>normal</em> — another in-flight leaf finishes and returns its permit (the deadlock-free invariant guarantees this
 * happens); (ii) <em>teardown</em> — {@code fanout.close()}'s {@code shutdownNow} interrupts the worker. The two
 * give-up paths behave differently. Once the run's {@link CancellationSignal} trips, the acquire loop stops polling and
 * runs the leaf <em>without</em> a permit — bounded, because the leaf's own {@code manager.execute} observes the same
 * tripped signal and returns promptly — so a stopped run tears down without waiting on a permit. An
 * <em>interruption</em> with an untripped signal, however, aborts by throwing {@link CancelledExecutionException}
 * instead of running the leaf: the runner's {@code close()} never trips per-run signals, so a leaf launched here would
 * be a fresh, unbounded LLM call started during (or after) the drain. The dispatcher isolates the abort into its
 * {@code onError} substitute, and the interrupt flag is restored either way so the drain still empties the parked
 * worker immediately.
 */
final class LeafConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(LeafConcurrencyLimiter.class);

    /** Poll interval for the signal-checking acquire loop. */
    private static final long POLL_MILLIS = 50L;

    private final Semaphore permits;

    /**
     * @param maxConcurrency
     *            the maximum concurrent leaf (LLM) calls across all runs sharing the pool (must be >= 1)
     */
    LeafConcurrencyLimiter(int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1, got: " + maxConcurrency);
        }
        this.permits = new Semaphore(maxConcurrency);
    }

    /**
     * Runs {@code leaf} under a permit. Acquires a permit (polling the signal), runs the leaf, and releases the permit.
     * Once the run's signal trips it stops waiting and runs the leaf without a permit (bounded — the leaf observes the
     * same tripped signal and returns promptly), never holding a permit it did not acquire.
     *
     * @param signal
     *            the run's cancellation signal (must not be null)
     * @param leaf
     *            the terminal leaf work — a single {@code manager.execute} call (must not be null)
     * @param <R>
     *            the leaf result type
     * @return the leaf's result
     * @throws CancelledExecutionException
     *             if the worker was interrupted while waiting for a permit and the signal is untripped (teardown) —
     *             the leaf is <em>not</em> run in that case
     */
    <R> R around(CancellationSignal signal, Supplier<R> leaf) {
        final boolean acquired = acquire(signal);
        try {
            return leaf.get();
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    /**
     * @return {@code true} if a permit was acquired (caller must release it); {@code false} if it gave up because the
     *         run was cancelled (no permit held — the leaf still runs, bounded by the tripped signal).
     * @throws CancelledExecutionException
     *             if the worker was interrupted while the signal is untripped (teardown) — the caller must not run the
     *             leaf
     */
    private boolean acquire(CancellationSignal signal) {
        while (true) {
            if (signal.isCancelled()) {
                return false;
            }
            try {
                if (permits.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                // Teardown: shutdownNow interrupted this parked worker. Restore the flag so the drain observes it.
                Thread.currentThread().interrupt();
                if (signal.isCancelled()) {
                    // A stop raced the interrupt: bounded give-up — the leaf observes the tripped signal.
                    return false;
                }
                // Untripped signal: close() never trips per-run signals, so running the leaf here would launch a
                // fresh, unbounded LLM call during the drain. Abort instead; the dispatcher isolates this per-item.
                log.warn("Leaf permit wait interrupted with an untripped run signal (teardown); aborting the leaf "
                        + "instead of running it ungated");
                throw new CancelledExecutionException(InterruptReason.SYSTEM_SHUTDOWN);
            }
        }
    }
}
