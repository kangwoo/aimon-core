package at.aimon.core.agent.queue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default, thread-safe in-memory {@link MessageQueueRepository}.
 *
 * <h2>Data structure</h2>
 *
 * <p>
 * A single {@link ConcurrentLinkedDeque} stores all entries in strict insertion order. Reads that need priority
 * ordering walk the deque three times — once per {@link QueuedInputPriority} tier in
 * {@link QueuedInputPriority#NOW NOW} &rarr; {@link QueuedInputPriority#NEXT NEXT} &rarr;
 * {@link QueuedInputPriority#LATER LATER} order — which preserves FIFO within each tier. A single deque keeps mutating
 * operations ({@link #enqueue(QueuedInput) enqueue}, {@link #remove(UUID) remove}) simple and lock-free while still
 * yielding correct cross-tier ordering on reads. Given the expected small queue depth (typically a handful of buffered
 * messages), the O(N) read cost is acceptable; if the queue ever becomes hot enough to matter, the backing store can
 * be split into per-tier deques without changing this class's contract.
 *
 * <h2>Listeners</h2>
 *
 * <p>
 * Listeners are kept in a {@link CopyOnWriteArrayList} so subscribe/unsubscribe do not block producers. Exceptions
 * thrown by a listener are caught, logged at {@code WARN} and do not propagate, so a misbehaving listener cannot
 * affect the producer or other listeners.
 *
 * <h2>Limits</h2>
 *
 * <p>
 * This implementation has no TTL and no size cap. Production deployments that need bounded memory or expiry should run
 * a distributed backend implementation of {@link MessageQueueRepository}.
 */
public final class InMemoryMessageQueueRepository implements MessageQueueRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageQueueRepository.class);

    private final ConcurrentLinkedDeque<QueuedInput> entries = new ConcurrentLinkedDeque<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Creates an empty repository.
     */
    public InMemoryMessageQueueRepository() {
        // no-op
    }

    @Override
    public void enqueue(QueuedInput input) {
        Objects.requireNonNull(input, "input cannot be null");
        entries.addLast(input);
        notifyEnqueued(input);
    }

    @Override
    public Optional<QueuedInput> dequeue(Predicate<QueuedInput> filter) {
        Objects.requireNonNull(filter, "filter cannot be null");
        for (QueuedInputPriority priority : QueuedInputPriority.values()) {
            final Iterator<QueuedInput> it = entries.iterator();
            while (it.hasNext()) {
                final QueuedInput candidate = it.next();
                if (candidate.getPriority() == priority && filter.test(candidate)) {
                    if (entries.remove(candidate)) {
                        return Optional.of(candidate);
                    }
                    // Another thread removed it first; keep searching.
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<QueuedInput> peek(Predicate<QueuedInput> filter) {
        Objects.requireNonNull(filter, "filter cannot be null");
        for (QueuedInputPriority priority : QueuedInputPriority.values()) {
            for (QueuedInput candidate : entries) {
                if (candidate.getPriority() == priority && filter.test(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<QueuedInput> listByMaxPriority(QueuedInputPriority maxPriority, Predicate<QueuedInput> filter) {
        Objects.requireNonNull(maxPriority, "maxPriority cannot be null");
        Objects.requireNonNull(filter, "filter cannot be null");
        final int maxOrdinal = maxPriority.ordinal();
        final List<QueuedInput> result = new ArrayList<>();
        for (QueuedInputPriority priority : QueuedInputPriority.values()) {
            if (priority.ordinal() > maxOrdinal) {
                continue;
            }
            for (QueuedInput candidate : entries) {
                if (candidate.getPriority() == priority && filter.test(candidate)) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    @Override
    public boolean remove(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        final Iterator<QueuedInput> it = entries.iterator();
        while (it.hasNext()) {
            final QueuedInput candidate = it.next();
            if (candidate.getUuid().equals(uuid)) {
                return entries.remove(candidate);
            }
        }
        return false;
    }

    @Override
    public Listener.Registration subscribe(Listener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        listeners.add(listener);
        return new ListenerRegistration(listener);
    }

    @Override
    public int size() {
        return entries.size();
    }

    private void notifyEnqueued(QueuedInput input) {
        for (Listener listener : listeners) {
            try {
                listener.onEnqueued(input);
            } catch (RuntimeException e) {
                log.warn("Queue listener threw exception on enqueue (uuid={}): {}", input.getUuid(), e.getMessage(), e);
            }
        }
    }

    private final class ListenerRegistration implements Listener.Registration {

        private final Listener listener;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private ListenerRegistration(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                listeners.remove(listener);
            }
        }
    }
}
