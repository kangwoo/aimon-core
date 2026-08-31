package at.aimon.core.agent.queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default thread-safe {@link MessageQueueManager}.
 *
 * <p>
 * Delegates storage to an injected {@link MessageQueueRepository} and maintains its own
 * {@link CopyOnWriteArrayList} of listeners so that add/remove operations do not block producers or consumers. Listener
 * fan-out is done <i>after</i> the repository call has completed — the manager captures affected entries first,
 * releases whatever locking the repository performed internally, and only then iterates listeners. Listener exceptions
 * are caught and logged so a misbehaving observer cannot stall the loop or starve other listeners.
 */
public final class DefaultMessageQueueManager implements MessageQueueManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultMessageQueueManager.class);

    private final MessageQueueRepository repository;
    private final CopyOnWriteArrayList<MessageQueueListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a manager backed by the given repository.
     *
     * @param repository
     *            the storage repository (must not be null)
     * @throws NullPointerException
     *             if {@code repository} is null
     */
    public DefaultMessageQueueManager(MessageQueueRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
    }

    @Override
    public void enqueue(QueuedInput input) {
        Objects.requireNonNull(input, "input cannot be null");
        repository.enqueue(input);
        notifyListeners(new MessageQueueListener.Event(input, MessageQueueListener.ChangeType.ENQUEUED));
    }

    @Override
    public List<QueuedInput> drainForInjection(Predicate<QueuedInput> filter, QueuedInputPriority maxPriority) {
        Objects.requireNonNull(filter, "filter cannot be null");
        Objects.requireNonNull(maxPriority, "maxPriority cannot be null");

        // Snapshot in priority-then-FIFO order, then remove each entry by uuid. We do the work in two steps — list,
        // then remove — rather than calling dequeue() in a loop to keep the returned ordering stable even if a
        // concurrent producer appends a higher-priority entry mid-drain.
        final List<QueuedInput> snapshot = repository.listByMaxPriority(maxPriority, filter);
        final List<QueuedInput> drained = new ArrayList<>(snapshot.size());
        for (QueuedInput candidate : snapshot) {
            if (repository.remove(candidate.getUuid())) {
                drained.add(candidate);
            }
            // If remove() returned false another consumer raced us — silently drop the entry from our batch.
        }

        for (QueuedInput entry : drained) {
            notifyListeners(new MessageQueueListener.Event(entry, MessageQueueListener.ChangeType.DRAINED));
        }
        return drained;
    }

    @Override
    public void addListener(MessageQueueListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        listeners.add(listener);
    }

    @Override
    public void removeListener(MessageQueueListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        listeners.remove(listener);
    }

    @Override
    public List<QueuedInput> snapshot() {
        return Collections.unmodifiableList(repository.listByMaxPriority(QueuedInputPriority.LATER, q -> true));
    }

    private void notifyListeners(MessageQueueListener.Event event) {
        for (MessageQueueListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                log.warn("MessageQueueListener {} threw on event {}: {}", listener.getClass().getName(),
                        event.getChangeType(), e.getMessage(), e);
            }
        }
    }
}
