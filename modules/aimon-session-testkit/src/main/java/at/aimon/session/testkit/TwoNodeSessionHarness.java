package at.aimon.session.testkit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.session.routing.DeploymentMode;
import at.aimon.session.routing.LiveSessionOpener;
import at.aimon.session.routing.SessionRouter;

/**
 * Two {@link SessionRouter} instances over one backend, each on its own connections so signal traffic really crosses
 * the wire.
 *
 * <p>
 * Both nodes share a single {@link InMemorySessionRecordStore}. Record sharing is orthogonal to the manager-side
 * concerns these scenarios are about, and it matches the runtime, where every node points at the same store.
 *
 * <p>
 * The backend arrives as a {@link SessionBackendFactory} rather than through a subclass hook on purpose: a subclass
 * hook would be called from this constructor, before the subclass's own fields exist.
 */
public final class TwoNodeSessionHarness implements AutoCloseable {

    private final SessionRecordStore sharedRepository = new InMemorySessionRecordStore();

    private final List<AutoCloseable> closeables = new ArrayList<>();

    private final Node nodeA;

    private final Node nodeB;

    /**
     * Two nodes sharing one backend, with the shipped 3:1 lease-to-renewal ratio.
     *
     * <p>
     * Argument order is {@code (lease, renewalInterval)} and the interval has to be the shorter of the two — the
     * builder rejects the inverse since Stage 3b. Every call site read {@code (10s, 30s)} until then: a renewal tick
     * first due 20s after the lease it exists to extend has already lapsed. The tests passed anyway because their
     * turns finish in milliseconds, which is precisely why nothing noticed.
     *
     * @param backend
     *            the backend under test
     * @param lockLease
     *            how long a won lease stays valid without renewal
     * @param lockExtendInterval
     *            renewal cadence, strictly shorter than {@code lockLease}
     */
    public TwoNodeSessionHarness(SessionBackendFactory backend, Duration lockLease, Duration lockExtendInterval) {
        this(backend, lockLease, lockExtendInterval, Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    /**
     * @param backend
     *            the backend under test
     * @param lockLease
     *            how long a won lease stays valid without renewal
     * @param lockExtendInterval
     *            renewal cadence, strictly shorter than {@code lockLease}
     * @param holderLossSweepInterval
     *            how often each node sweeps for stale {@code IN_FLIGHT} entries
     * @param idempotencySecondaryTtl
     *            how long an {@code IN_FLIGHT} entry may go untouched before a sweeper may claim it
     */
    public TwoNodeSessionHarness(SessionBackendFactory backend, Duration lockLease, Duration lockExtendInterval,
            Duration holderLossSweepInterval, Duration idempotencySecondaryTtl) {
        Objects.requireNonNull(backend, "backend must not be null");
        backend.reset();
        this.nodeA = buildNode(backend, "node-A", lockLease, lockExtendInterval, holderLossSweepInterval,
                idempotencySecondaryTtl);
        this.nodeB = buildNode(backend, "node-B", lockLease, lockExtendInterval, holderLossSweepInterval,
                idempotencySecondaryTtl);
    }

    private Node buildNode(SessionBackendFactory backend, String nodeId, Duration lockLease,
            Duration lockExtendInterval, Duration holderLossSweepInterval, Duration idempotencySecondaryTtl) {
        final SessionBackend spis = backend.createNode(nodeId, closeables::add);

        final ConcurrentMap<SessionId, RecordingTestSession> sessions = new ConcurrentHashMap<>();
        final LiveSessionOpener opener = (id, agentRuntimeId, options, openAttributes) -> sessions.computeIfAbsent(id,
                RecordingTestSession::new);

        final SessionRouter manager = SessionRouter.builder().sessionOpener(opener).sessionRecordStore(sharedRepository)
                .sessionLeaseStore(spis.leaseStore()).signalBus(spis.signalBus()).sessionInbox(spis.inbox())
                .idempotencyStore(spis.idempotencyStore()).mode(DeploymentMode.DISTRIBUTED).nodeId(nodeId)
                .idleTtl(Duration.ofMinutes(10)).maxCachedSessions(1000).lockLease(lockLease)
                .lockExtendInterval(lockExtendInterval).holderLossSweepInterval(holderLossSweepInterval)
                .idempotencyPrimaryTtl(Duration.ofHours(24)).idempotencySecondaryTtl(idempotencySecondaryTtl)
                .releaseInterruptTimeout(Duration.ofSeconds(2)).build();
        return new Node(nodeId, manager, sessions);
    }

    /** @return the first node */
    public Node nodeA() {
        return nodeA;
    }

    /** @return the second node */
    public Node nodeB() {
        return nodeB;
    }

    /** @return the record store both nodes point at */
    public SessionRecordStore sharedRepository() {
        return sharedRepository;
    }

    @Override
    public void close() {
        closeQuietly(nodeA.manager());
        closeQuietly(nodeB.manager());
        for (AutoCloseable c : closeables) {
            closeQuietly(c);
        }
        closeables.clear();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            /* harness teardown */
        }
    }

    /** A single simulated node: its router, and the sessions that router opened, for assertions. */
    public static final class Node {

        private final String nodeId;

        private final SessionRouter manager;

        private final ConcurrentMap<SessionId, RecordingTestSession> sessions;

        Node(String nodeId, SessionRouter manager, ConcurrentMap<SessionId, RecordingTestSession> sessions) {
            this.nodeId = nodeId;
            this.manager = manager;
            this.sessions = sessions;
        }

        /** @return this node's id */
        public String nodeId() {
            return nodeId;
        }

        /** @return this node's router */
        public SessionRouter manager() {
            return manager;
        }

        /**
         * @param id
         *            the session to look up
         * @return the session this node opened for {@code id}, or {@code null} if it never opened one
         */
        public RecordingTestSession session(SessionId id) {
            return sessions.get(id);
        }
    }
}
