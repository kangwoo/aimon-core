package at.aimon.core.agent.session.signal;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;

/**
 * Single-process {@link SessionSignalBus} backed by per-session subscriber lists.
 *
 * <p>
 * Each {@code subscribe} appends to a {@link CopyOnWriteArrayList} so {@link #publish} can iterate without locking.
 * Handlers run synchronously on the publisher thread — callers must keep them non-blocking. Suitable for
 * {@code SINGLE_NODE} deployments and unit tests.
 */
public final class InMemorySignalBus implements SessionSignalBus {

    private static final Logger log = LoggerFactory.getLogger(InMemorySignalBus.class);

    private final ConcurrentMap<SessionId, List<Subscriber>> subscribers = new ConcurrentHashMap<>();

    @Override
    public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        final Subscriber subscriber = new Subscriber(handler);
        subscribers.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(subscriber);
        return () -> {
            final List<Subscriber> list = subscribers.get(id);
            if (list != null) {
                list.remove(subscriber);
                subscribers.computeIfPresent(id, (k, v) -> v.isEmpty() ? null : v);
            }
        };
    }

    @Override
    public void publish(SessionSignal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        final List<Subscriber> list = subscribers.get(signal.getSessionId());
        if (list == null) {
            return;
        }
        for (Subscriber s : list) {
            try {
                s.handler.accept(signal);
            } catch (Exception e) {
                log.warn("Signal handler threw for session {}: {}", signal.getSessionId(), e.toString());
            }
        }
    }

    private static final class Subscriber {
        final Consumer<SessionSignal> handler;

        Subscriber(Consumer<SessionSignal> handler) {
            this.handler = handler;
        }
    }
}
