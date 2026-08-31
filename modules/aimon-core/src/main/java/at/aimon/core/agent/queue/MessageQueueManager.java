package at.aimon.core.agent.queue;

import java.util.List;
import java.util.function.Predicate;

/**
 * Facade over a {@link MessageQueueRepository} that couples storage with observability.
 *
 * <p>
 * {@code MessageQueueManager} is the entry point every non-storage caller should depend on. It adds two concerns on top
 * of the raw repository:
 * <ol>
 * <li><b>Listener fan-out</b> — enqueue/drain events are broadcast to all registered {@link MessageQueueListener}s.
 * <li><b>Batch drain semantics</b> — {@link #drainForInjection(Predicate, QueuedInputPriority)} atomically removes the
 * set of entries that a consumer (e.g. the ReAct loop at an iteration boundary) is about to inject, preserving
 * priority-then-FIFO ordering as defined by {@link QueuedInputPriority}.
 * </ol>
 *
 * <p>
 * Per the AIMON multi-instance design rule, the repository remains the swap-point for distributed backends; this facade
 * stays in {@code aimon-core} and does not introduce any backend-specific concepts.
 *
 * <h2>Threading</h2>
 *
 * <p>
 * Implementations must be safe to invoke from multiple threads. Listener callbacks are delivered synchronously on the
 * thread that triggered the state change and listener exceptions must be isolated so they cannot affect the producer or
 * other listeners.
 *
 * @see MessageQueueRepository
 * @see MessageQueueListener
 */
public interface MessageQueueManager {

    /**
     * Appends the given input to the queue.
     *
     * <p>
     * Registered listeners receive a {@link MessageQueueListener.ChangeType#ENQUEUED} event after the repository
     * accepts the input.
     *
     * @param input
     *            the input to enqueue (must not be null)
     * @throws NullPointerException
     *             if {@code input} is null
     */
    void enqueue(QueuedInput input);

    /**
     * Removes and returns queued inputs that match {@code filter} and have priority at-or-above {@code maxPriority}
     * (i.e. {@code priority.ordinal() <= maxPriority.ordinal()}).
     *
     * <p>
     * Ordering of the returned list mirrors the repository's priority-then-FIFO contract: higher priority tiers come
     * first, and within a tier entries are returned in insertion order. Listeners receive one
     * {@link MessageQueueListener.ChangeType#DRAINED} event per returned entry, in the same order. The notification
     * fan-out happens <i>after</i> all matching entries have been removed from the repository so listeners always
     * observe the post-drain state.
     *
     * @param filter
     *            predicate applied to candidates (must not be null)
     * @param maxPriority
     *            lowest priority tier to include (inclusive; must not be null)
     * @return the removed entries in priority-then-FIFO order (never null; may be empty)
     * @throws NullPointerException
     *             if any argument is null
     */
    List<QueuedInput> drainForInjection(Predicate<QueuedInput> filter, QueuedInputPriority maxPriority);

    /**
     * Registers a listener that will receive every subsequent queue change event.
     *
     * <p>
     * If the same listener instance is added more than once it will be invoked more than once per event — de-duplicate
     * at the call site if that is undesirable.
     *
     * @param listener
     *            the listener to add (must not be null)
     * @throws NullPointerException
     *             if {@code listener} is null
     */
    void addListener(MessageQueueListener listener);

    /**
     * Removes a previously added listener. No-op if the listener is not registered.
     *
     * @param listener
     *            the listener to remove (must not be null)
     * @throws NullPointerException
     *             if {@code listener} is null
     */
    void removeListener(MessageQueueListener listener);

    /**
     * Returns an unmodifiable snapshot of the queue in priority-then-FIFO order. Intended for observability (status
     * commands, metrics, debugging) rather than as a consumption primitive — use
     * {@link #drainForInjection(Predicate, QueuedInputPriority)} for that.
     *
     * <p>
     * The returned list is a defensive copy: concurrent modifications to the queue do not affect it, and callers
     * cannot mutate it ({@link UnsupportedOperationException} on structural modification).
     *
     * @return the snapshot (never null; may be empty)
     */
    List<QueuedInput> snapshot();
}
