package at.aimon.session.routing.wake;

import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.session.routing.SubmitRequest;

/**
 * Fills in what a host gives its own submits for a session and a wake turn would otherwise lack: the
 * {@link SubmitRequest.Builder#options live session options} and {@link SubmitRequest.Builder#openAttributes open
 * attributes}, which the router applies only when the turn reopens an evicted session — and then keeps for every turn
 * after it until the next eviction.
 *
 * <p>
 * Called on the waker's thread before anything is drained; a customizer that throws skips the wake, and the
 * notifications stay queued. The waker sets the input, the submit options (principal, attributes) and the idempotency
 * key after this runs. Must be thread-safe and quick.
 */
@FunctionalInterface
public interface WakeRequestCustomizer {

    /** A customizer that adds nothing: a reopened session gets the router's defaults. */
    WakeRequestCustomizer NONE = (request, record) -> {
    };

    /**
     * @param request
     *            the request being built (session, agent and discriminator already set)
     * @param record
     *            the session's record, as read just now
     */
    void customize(SubmitRequest.Builder request, SessionRecordView record);
}
