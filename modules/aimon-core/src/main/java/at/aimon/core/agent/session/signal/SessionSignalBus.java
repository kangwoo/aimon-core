package at.aimon.core.agent.session.signal;

import java.util.List;
import java.util.Objects;
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
     * Publish several signals, in list order.
     *
     * <p>
     * This exists for the one caller that publishes at stream rate — the per-turn event relay, which sees a signal per
     * text delta. A backend whose {@link #publish} costs a round trip (a document insert, a transaction) should
     * override this to send the list in as few round trips as its transport allows; the default simply publishes one
     * at a time and is right for a backend where a publish is already cheap.
     *
     * <p>
     * Two things hold for every implementation:
     * <ul>
     * <li><b>Order.</b> Signals of one kind for one session are delivered to a subscriber in list order. Across kinds
     * there is no more order than {@link #publish} gives — a backend may carry {@code EVENT} on a channel of its
     * own.</li>
     * <li><b>One bad signal does not take the rest with it.</b> A signal that cannot be published is skipped and the
     * ones after it are still attempted; the failure is reported by throwing once the whole list has been tried. A
     * failure of the transport itself, where nothing further could succeed, may be thrown straight away.</li>
     * </ul>
     *
     * <p>
     * A bus that wraps another must forward this method as well as {@link #publish}: inheriting the default would
     * publish through the delegate one signal at a time and quietly undo the delegate's batching.
     *
     * @param signals
     *            the signals to publish, in delivery order (must not be null; may be empty)
     * @throws RuntimeException
     *             the first failure, with any later ones attached as suppressed, after every signal has been attempted
     */
    default void publishAll(List<SessionSignal> signals) {
        Objects.requireNonNull(signals, "signals must not be null");
        RuntimeException failure = null;
        for (SessionSignal signal : signals) {
            try {
                publish(signal);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else if (e != failure) {
                    // The same instance twice cannot suppress itself; a bus that rethrows a cached exception does this.
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Handle to a single subscription. Closing it unregisters the handler.
     */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
