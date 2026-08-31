package at.aimon.core.subagent.task;

import java.util.function.Consumer;

/**
 * Cross-node cancellation seam for background subagent tasks (design §4).
 *
 * <p>
 * A background task's live execution handle ({@code RunningTaskHandle}) exists only on the node that started it, so a
 * {@code Task.stop(taskId)} issued on <em>another</em> node cannot reach it directly. This interface is the pub/sub
 * seam
 * that carries a stop request to whichever node owns the running task: the receiving node calls
 * {@link #broadcastStop(String)}, every node (including the sender) is delivered the {@code taskId} through its
 * {@link #subscribe(Consumer) subscription}, and the owning node trips its local handle.
 *
 * <p>
 * <b>Multi-instance rule.</b> The default {@link NoopTaskStopSignal} makes cross-node propagation a no-op, so a
 * single-node deployment behaves exactly as before (a local stop still works directly via the handle registry).
 * {@link InMemoryTaskStopSignal} adds in-process loopback for tests and single-JVM multi-manager setups; a scale-out
 * deployment supplies a shared-backend implementation (Redis pub/sub, ...). Swapping the backend is an implementation
 * change, not a refactoring.
 *
 * <p>
 * <b>Idempotent and best-effort.</b> A stop request is a hint: delivering the same {@code taskId} more than once, or to
 * a node that has no such task (unknown, already terminal, or never owned it), is harmless. Implementations must never
 * throw from {@link #broadcastStop(String)} for routine backend errors — a failed broadcast must not abort the caller's
 * {@code stop} flow.
 *
 * <p>
 * Implementations must be safe for concurrent {@link #broadcastStop(String)} / {@link #subscribe(Consumer)} calls.
 */
public interface TaskStopSignal {

    /**
     * Broadcasts a stop request for a task to every node listening on this signal (including the local node).
     *
     * <p>
     * Best-effort and idempotent: implementations must not throw for routine backend errors, and delivering the same
     * {@code taskId} repeatedly has no additional effect.
     *
     * @param taskId
     *            the identifier of the task to stop (must not be null)
     */
    void broadcastStop(String taskId);

    /**
     * Registers a handler invoked with the {@code taskId} of every stop request delivered on this signal.
     *
     * <p>
     * The handler runs on an implementation-owned delivery thread; it must be quick and non-throwing (a thrown
     * exception is swallowed and logged by the implementation). A manager subscribes once at construction to trip its
     * local execution handle when a remote stop arrives.
     *
     * @param onStopRequest
     *            the handler receiving stop-request task ids (must not be null)
     * @return a subscription; {@link Subscription#close() close} it to stop receiving stop requests
     */
    Subscription subscribe(Consumer<String> onStopRequest);

    /**
     * Handle for an active {@link #subscribe(Consumer) subscription}. Closing it detaches the handler; closing more
     * than
     * once is a no-op.
     */
    interface Subscription extends AutoCloseable {

        /** Detaches the handler. Idempotent and non-throwing. */
        @Override
        void close();
    }
}
