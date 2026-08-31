package at.aimon.core.agent.interrupt;

import java.util.Optional;

/**
 * Read-side view of an execution's interrupt flag. Exposed to tools through
 * {@link at.aimon.core.agent.tool.ToolContext} so cooperative tools can poll for cancellation without touching the
 * executor.
 *
 * <p>
 * A signal is single-shot: once tripped it stays tripped for the remainder of the execution. Signals are never reset. A
 * fresh instance is created by {@link InterruptCoordinator} at the start of every execution so a prior execution's
 * cancellation cannot leak.
 *
 * <h2>Threading</h2>
 * <ul>
 * <li>{@link #isCancelled()} and {@link #getReason()} are safe to call from any thread.
 * <li>{@link #onCancel(Runnable)} is idempotent — listeners added after the signal has already been tripped are
 * invoked synchronously on the registering thread, and listeners fired on trip run in registration order.
 * </ul>
 */
public interface CancellationSignal {

    /**
     * Handle returned by {@link #onCancel(Runnable)} used to deregister the listener. {@link #remove()} is idempotent
     * and safe to call at any time (including after the signal has tripped, when the listener list is already cleared
     * — then it is a no-op). This lets a component that registers a <em>per-unit-of-work</em> listener on a
     * longer-lived signal (e.g. a per-execution signal shared across many background subagent tasks) release its
     * listener on completion instead of accumulating listeners for the life of the signal.
     */
    @FunctionalInterface
    interface Registration {

        /** A registration that deregisters nothing (for already-tripped or no-op signals). */
        Registration NONE = () -> {
        };

        /**
         * Deregisters the associated listener. Idempotent; must never throw.
         */
        void remove();
    }

    /**
     * @return {@code true} if the signal has been tripped
     */
    boolean isCancelled();

    /**
     * @return the reason the signal was tripped, or empty if it has not been
     */
    Optional<InterruptReason> getReason();

    /**
     * Throws {@link CancelledExecutionException} if {@link #isCancelled()} is true; no-op otherwise.
     *
     * <p>
     * Suited to tool-level checkpoints that want to unwind via stack propagation rather than returning a
     * {@link at.aimon.core.agent.tool.ToolResult}. Tools that need to release resources must wrap the call in a
     * try/finally block.
     *
     * @throws CancelledExecutionException
     *             if the signal is tripped
     */
    void checkpoint();

    /**
     * Register a listener that fires when the signal is tripped. If the signal has already been tripped, the listener
     * is invoked synchronously on the registering thread. Listeners fired on trip are invoked in registration order.
     *
     * @param listener
     *            the listener (never null)
     * @return a {@link Registration} handle that deregisters this listener when {@link Registration#remove()} is
     *         called; callers that register a short-lived listener on a longer-lived signal should retain it and
     *         remove the listener when their unit of work completes. Callers whose listener naturally lives as long as
     *         the signal may ignore the return value.
     * @throws NullPointerException
     *             if {@code listener} is null
     */
    Registration onCancel(Runnable listener);
}
