package at.aimon.core.agent.session.signal;

import java.util.function.Consumer;

import at.aimon.core.agent.session.SessionId;

/**
 * Cross-node pub/sub bus for {@link SessionSignal}s.
 *
 * <p>
 * Subscribers register a handler scoped to a single {@link SessionId}; publishers fan out a signal to every node
 * subscribed to that session. The Redis-backed implementation splits {@link SessionSignal.SignalKind#EVENT}
 * onto a separate channel from the control kinds (design §5.4).
 *
 * <p>
 * Signal handlers run on the bus's delivery thread. Handlers must be non-blocking — long work belongs on the
 * handler-side dispatcher, not on the bus thread.
 */
public interface SessionSignalBus {

    /**
     * Subscribe to signals targeting {@code id}.
     *
     * @param id
     *            the session to subscribe to (must not be null)
     * @param handler
     *            invoked for every signal received (must not be null)
     * @return a {@link Subscription} the caller closes to unsubscribe
     */
    Subscription subscribe(SessionId id, Consumer<SessionSignal> handler);

    /**
     * Publish a signal. Idempotent w.r.t. duplicate delivery — receivers must be able to handle the same signal
     * repeatedly.
     *
     * @param signal
     *            the signal to publish (must not be null)
     */
    void publish(SessionSignal signal);

    /**
     * Handle to a single subscription. Closing it unregisters the handler.
     */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
