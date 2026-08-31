package at.aimon.core.agent.impl.orca;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.stream.AgentExecutionEvent;

/**
 * Thread-safe fan-out helper that dispatches {@link AgentExecutionEvent}s to a set of registered listeners.
 *
 * <p>
 * <b>Package-private by design:</b> {@code EventEmitter} is an implementation detail of
 * {@link OrcaAgentExecutor}'s streaming-event support and is not part of any public API. External consumers should
 * interact with executors via the {@link at.aimon.core.agent.stream.StreamingAgentExecutor} interface.
 *
 * <p>
 * <b>Listener isolation:</b> per the streaming event contract, a misbehaving listener MUST NOT be able to abort an
 * agent execution or prevent sibling listeners from seeing subsequent events. {@link #emit(AgentExecutionEvent)}
 * therefore iterates every registered listener individually, catching and logging any {@link RuntimeException} thrown
 * from {@link Consumer#accept(Object)}. JVM-level {@link Error}s (e.g. {@link OutOfMemoryError}) are intentionally
 * <em>not</em> swallowed — those conditions are not recoverable and must propagate to the executor's outer handler.
 *
 * <p>
 * <b>Concurrency:</b> listener registration uses {@link CopyOnWriteArrayList}, so iteration during
 * {@link #emit(AgentExecutionEvent)} is snapshot-based and safe even if another thread concurrently adds or removes
 * listeners.
 */
final class EventEmitter {

    private static final Logger log = LoggerFactory.getLogger(EventEmitter.class);

    private final List<Consumer<AgentExecutionEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Registers a listener.
     *
     * @param listener
     *            the listener to register (must not be null)
     * @throws NullPointerException
     *             if {@code listener} is null
     */
    void addListener(Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * <p>
     * Removal is identity-based: only the first listener instance {@code ==} to {@code listener} is removed.
     *
     * @param listener
     *            the listener to remove (must not be null)
     * @return {@code true} if a matching listener was found and removed; {@code false} otherwise
     * @throws NullPointerException
     *             if {@code listener} is null
     */
    boolean removeListener(Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        return listeners.remove(listener);
    }

    /**
     * Returns whether no listeners are currently registered.
     *
     * @return {@code true} if this emitter has zero listeners
     */
    boolean isEmpty() {
        return listeners.isEmpty();
    }

    /**
     * Dispatches {@code event} to every registered listener.
     *
     * <p>
     * Listeners are invoked in registration order on the caller's thread. Any {@link RuntimeException} thrown by a
     * listener is caught and logged at {@code WARN} level; it does not short-circuit dispatch to the remaining
     * listeners and never propagates back to the caller.
     *
     * @param event
     *            the event to emit (must not be null)
     * @throws NullPointerException
     *             if {@code event} is null
     */
    void emit(AgentExecutionEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        if (listeners.isEmpty()) {
            return;
        }
        for (Consumer<AgentExecutionEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.warn("Event listener threw an exception while handling {}: {}", event.getClass().getSimpleName(),
                        e.toString(), e);
            }
        }
    }
}
