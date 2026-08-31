package at.aimon.core.agent.queue;

import java.util.Objects;

/**
 * Listener invoked by {@link MessageQueueManager} when queue state changes.
 *
 * <p>
 * The listener is intentionally a single-method functional interface carrying a strongly-typed
 * {@link Event} — this mirrors the lightweight observer pattern already used in
 * {@code ScheduledTaskEventPublisher} while keeping the callback surface small (only one event shape instead of one
 * method per change type). Using a nested {@link Event} class plus a {@link ChangeType} enum makes it easy to add new
 * change kinds later without breaking existing listener implementations.
 *
 * <p>
 * Listeners are invoked synchronously on the thread that triggered the state change. Implementations should therefore
 * return quickly and must tolerate being called from any thread (REPL input thread, Orca loop thread, scheduling
 * thread,
 * etc.). Exceptions thrown by a listener are caught and logged by the manager and do <b>not</b> propagate to the
 * producer or to other listeners.
 *
 * @see MessageQueueManager
 */
@FunctionalInterface
public interface MessageQueueListener {

    /**
     * Called after the queue state has changed.
     *
     * @param event
     *            the change event (never null)
     */
    void onEvent(Event event);

    /**
     * Discriminator for {@link Event}s emitted by {@link MessageQueueManager}.
     */
    enum ChangeType {

        /** Emitted after a successful {@link MessageQueueManager#enqueue(QueuedInput) enqueue}. */
        ENQUEUED,

        /**
         * Emitted for each entry returned by
         * {@link MessageQueueManager#drainForInjection(java.util.function.Predicate, QueuedInputPriority)
         * drainForInjection}.
         */
        DRAINED,

        /**
         * Reserved for explicit out-of-band removals (e.g. cancellation flows). Not emitted by the default manager yet
         * —
         * callers that implement custom removal paths may publish this change type via a future hook.
         */
        REMOVED
    }

    /**
     * Immutable event describing a single change to the queue.
     */
    final class Event {

        private final QueuedInput input;
        private final ChangeType changeType;

        /**
         * Creates a new event.
         *
         * @param input
         *            the changed queued input (must not be null)
         * @param changeType
         *            the change type (must not be null)
         * @throws NullPointerException
         *             if any argument is null
         */
        public Event(QueuedInput input, ChangeType changeType) {
            this.input = Objects.requireNonNull(input, "input cannot be null");
            this.changeType = Objects.requireNonNull(changeType, "changeType cannot be null");
        }

        /**
         * The queued input that was affected.
         *
         * @return the input (never null)
         */
        public QueuedInput getInput() {
            return input;
        }

        /**
         * The kind of change that occurred.
         *
         * @return the change type (never null)
         */
        public ChangeType getChangeType() {
            return changeType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Event)) {
                return false;
            }
            Event event = (Event) o;
            return input.equals(event.input) && changeType == event.changeType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(input, changeType);
        }

        @Override
        public String toString() {
            return "MessageQueueListener.Event{" + "changeType=" + changeType + ", input=" + input + '}';
        }
    }
}
