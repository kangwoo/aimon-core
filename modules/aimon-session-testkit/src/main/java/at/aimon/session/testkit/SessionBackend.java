package at.aimon.session.testkit;

import java.util.Objects;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.SessionLeaseStore;

/**
 * The four cross-node SPIs one simulated node runs on, already bound to that node's own connections.
 *
 * <p>
 * A backend hands this to {@link TwoNodeSessionHarness} through {@link SessionBackendFactory#createNode}; the harness
 * turns it into a {@link at.aimon.session.routing.SessionRouter} and never looks inside again. Grouping the four is
 * what keeps the harness from knowing that Redis needs three connections, Postgres two pools and MongoDB one client.
 *
 * <p>
 * A static factory rather than the builder {@code .claude/rules/immutability-pattern.md} asks for, and the reason is
 * narrow enough to state: all four components are required, so a builder would move the check from the compiler to
 * {@code build()} and add nothing else. That rule guards domain and value objects an application assembles; this is a
 * four-element tuple handed straight back to the harness that asked for it. Immutability is unchanged — final class,
 * final fields, no setters.
 */
public final class SessionBackend {

    private final SessionLeaseStore leaseStore;

    private final SessionSignalBus signalBus;

    private final SessionInbox inbox;

    private final IdempotencyStore idempotencyStore;

    private SessionBackend(SessionLeaseStore leaseStore, SessionSignalBus signalBus, SessionInbox inbox,
            IdempotencyStore idempotencyStore) {
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore must not be null");
        this.signalBus = Objects.requireNonNull(signalBus, "signalBus must not be null");
        this.inbox = Objects.requireNonNull(inbox, "inbox must not be null");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore must not be null");
    }

    /**
     * @param leaseStore
     *            the node's lease store
     * @param signalBus
     *            the node's signal bus, already subscribed on its own connection
     * @param inbox
     *            the node's view of the shared inbox
     * @param idempotencyStore
     *            the node's idempotency store
     * @return the four bound together
     */
    public static SessionBackend of(SessionLeaseStore leaseStore, SessionSignalBus signalBus, SessionInbox inbox,
            IdempotencyStore idempotencyStore) {
        return new SessionBackend(leaseStore, signalBus, inbox, idempotencyStore);
    }

    /** @return the node's lease store */
    public SessionLeaseStore leaseStore() {
        return leaseStore;
    }

    /** @return the node's signal bus */
    public SessionSignalBus signalBus() {
        return signalBus;
    }

    /** @return the node's inbox */
    public SessionInbox inbox() {
        return inbox;
    }

    /** @return the node's idempotency store */
    public IdempotencyStore idempotencyStore() {
        return idempotencyStore;
    }
}
