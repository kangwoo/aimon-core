package at.aimon.core.agent.queue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Storage abstraction for the mid-turn {@link QueuedInput} queue.
 *
 * <p>
 * Implementations must provide thread-safe access so producers (REPL, sub-agents) and consumers (ReAct loop driver) may
 * operate concurrently. The repository owns ordering: within a single {@link QueuedInputPriority} tier entries are
 * stored FIFO (insertion order); across tiers {@link QueuedInputPriority#NOW} is always surfaced before
 * {@link QueuedInputPriority#NEXT} and {@link QueuedInputPriority#LATER}.
 *
 * <p>
 * Per the AIMON multi-instance design rule, this interface is the swap-point between the default in-memory backend and
 * future distributed backends (Redis, Mongo, etc.).
 *
 * <h2>Listener semantics</h2>
 *
 * <p>
 * {@link #subscribe(Listener)} registers a best-effort listener for enqueue events. Implementations must invoke the
 * listener at least for every successful {@link #enqueue(QueuedInput)} call but are not required to guarantee
 * exactly-once delivery across JVM crashes. Listener exceptions must be isolated from the producer and from other
 * listeners.
 */
public interface MessageQueueRepository {

    /**
     * Inserts the given input at the tail of its priority tier.
     *
     * @param input
     *            the input to enqueue (must not be null)
     * @throws NullPointerException
     *             if {@code input} is null
     */
    void enqueue(QueuedInput input);

    /**
     * Removes and returns the highest-priority queued input matching {@code filter}, breaking ties by insertion order.
     *
     * @param filter
     *            predicate applied to candidates in priority-then-FIFO order (must not be null)
     * @return the removed entry, or empty if no entry matched
     * @throws NullPointerException
     *             if {@code filter} is null
     */
    Optional<QueuedInput> dequeue(Predicate<QueuedInput> filter);

    /**
     * Returns the highest-priority queued input matching {@code filter} without removing it.
     *
     * @param filter
     *            predicate applied to candidates in priority-then-FIFO order (must not be null)
     * @return the first matching entry, or empty if none match
     * @throws NullPointerException
     *             if {@code filter} is null
     */
    Optional<QueuedInput> peek(Predicate<QueuedInput> filter);

    /**
     * Returns a snapshot of queued inputs whose priority is at most {@code maxPriority} (i.e.
     * {@code priority.ordinal() <= maxPriority.ordinal()}) and that satisfy {@code filter}.
     *
     * <p>
     * The returned list is ordered priority-then-FIFO and is a defensive copy — mutating it does not affect repository
     * state.
     *
     * @param maxPriority
     *            the maximum priority to include (inclusive; must not be null)
     * @param filter
     *            predicate applied to candidates (must not be null)
     * @return the (possibly empty) snapshot list
     * @throws NullPointerException
     *             if any argument is null
     */
    List<QueuedInput> listByMaxPriority(QueuedInputPriority maxPriority, Predicate<QueuedInput> filter);

    /**
     * Removes the entry with the given uuid, if present.
     *
     * @param uuid
     *            the uuid of the entry to remove (must not be null)
     * @return {@code true} if an entry was removed, {@code false} if no entry had the given uuid
     * @throws NullPointerException
     *             if {@code uuid} is null
     */
    boolean remove(UUID uuid);

    /**
     * Registers a listener for enqueue events.
     *
     * @param listener
     *            the listener (must not be null)
     * @return a registration handle; closing it unsubscribes the listener
     * @throws NullPointerException
     *             if {@code listener} is null
     */
    Listener.Registration subscribe(Listener listener);

    /**
     * Returns the current number of queued inputs across all priority tiers.
     *
     * @return the queue size (non-negative)
     */
    int size();

    /**
     * Callback invoked when a new {@link QueuedInput} is enqueued.
     *
     * <p>
     * Implementations must not assume any particular thread — the listener may be invoked on the producer's thread.
     * Listener implementations should therefore complete quickly and not block the producer.
     */
    @FunctionalInterface
    interface Listener {

        /**
         * Called after an input has been successfully enqueued.
         *
         * @param input
         *            the enqueued input (never null)
         */
        void onEnqueued(QueuedInput input);

        /**
         * Handle returned from {@link MessageQueueRepository#subscribe(Listener)}; closing it removes the listener.
         *
         * <p>
         * {@link #close()} does not throw checked exceptions. Closing an already-closed registration is a no-op.
         */
        interface Registration extends AutoCloseable {
            @Override
            void close();
        }
    }
}
