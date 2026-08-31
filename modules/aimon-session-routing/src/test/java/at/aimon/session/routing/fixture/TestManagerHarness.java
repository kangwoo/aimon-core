package at.aimon.session.routing.fixture;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.DefaultSessionStore;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.session.routing.LiveSessionOpener;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.internal.DefaultSessionRouter;
import at.aimon.session.routing.internal.SessionRouterConfig;
import at.aimon.session.routing.metrics.SessionMetrics;

/**
 * Wires a {@link SessionRouter} for tests using the package-private opener constructor.
 *
 * <p>
 * The harness exposes the underlying SPIs so tests can inspect inbox state, drive interrupts, or replace one
 * collaborator (e.g. the lease store) to provoke specific scenarios.
 *
 * <p>
 * The lease store and the repository are handed in as two separate backends and composed into one
 * {@link SessionStore} in {@link Builder#build()}, mirroring what the production builder does. Two harnesses
 * sharing
 * both backends therefore behave like two nodes: each has its own store, so each tracks only the leases it won itself.
 */
public final class TestManagerHarness implements AutoCloseable {

    private final SessionRouter manager;
    private final SessionLeaseStore leaseStore;
    private final SessionSignalBus signalBus;
    private final SessionInbox inbox;
    private final IdempotencyStore idempotencyStore;
    private final SessionRecordStore repository;
    private final SessionApprovalStore sessionApprovals;
    private final ConcurrentMap<SessionId, TestLiveSession> sessions;
    private final ConcurrentMap<SessionId, AgentRuntimeId> openedRuntimeIds;

    private TestManagerHarness(SessionRouter manager, SessionLeaseStore leaseStore, SessionSignalBus signalBus,
            SessionInbox inbox, IdempotencyStore idempotencyStore, SessionRecordStore repository,
            SessionApprovalStore sessionApprovals, ConcurrentMap<SessionId, TestLiveSession> sessions,
            ConcurrentMap<SessionId, AgentRuntimeId> openedRuntimeIds) {
        this.manager = manager;
        this.leaseStore = leaseStore;
        this.signalBus = signalBus;
        this.inbox = inbox;
        this.idempotencyStore = idempotencyStore;
        this.repository = repository;
        this.sessionApprovals = sessionApprovals;
        this.sessions = sessions;
        this.openedRuntimeIds = openedRuntimeIds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public SessionRouter manager() {
        return manager;
    }

    /**
     * The election backend, for tests that need to steal or inspect holdership from outside the manager.
     *
     * <p>
     * Deliberately the backend and not the manager's {@code SessionStore}: a lease won here is won by an outsider,
     * which is the point — acquiring through the manager's own store would register the lease as locally held and let
     * the
     * manager's fenced writes sail through.
     *
     * @return the shared lease store
     */
    public SessionLeaseStore leaseStore() {
        return leaseStore;
    }

    public SessionSignalBus signalBus() {
        return signalBus;
    }

    public SessionInbox inbox() {
        return inbox;
    }

    public IdempotencyStore idempotencyStore() {
        return idempotencyStore;
    }

    public SessionRecordStore repository() {
        return repository;
    }

    /**
     * The session-scoped approval store handed to the manager, or {@code null} when the test did not wire one.
     *
     * @return the store, or {@code null}
     */
    public SessionApprovalStore sessionApprovals() {
        return sessionApprovals;
    }

    public TestLiveSession session(SessionId id) {
        return sessions.get(id);
    }

    /**
     * The {@link AgentRuntimeId} the manager last handed the opener for {@code id}.
     *
     * <p>
     * The one thing a caller cannot read back off the opened session: {@link TestLiveSession} knows its
     * {@link SessionId} and nothing about which runtime it was opened against, so a test that cares whether the
     * composite {@code agent:<ref>:<discriminator>} or the bare {@code agent:<ref>} was chosen has to be told here.
     *
     * @param id
     *            the session
     * @return the runtime id, or {@code null} when the opener was never invoked for that session
     */
    public AgentRuntimeId openedRuntimeId(SessionId id) {
        return openedRuntimeIds.get(id);
    }

    @Override
    public void close() {
        manager.close();
    }

    public static final class Builder {
        private SessionLeaseStore leaseStore = new InMemorySessionLeaseStore();
        private SessionSignalBus signalBus = new InMemorySignalBus();
        private SessionInbox inbox = new InMemorySessionInbox();
        private IdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
        private SessionRecordStore repository = new InMemorySessionRecordStore();
        private Duration idleTtl = Duration.ofMinutes(10);
        private int maxCachedSessions = 1000;
        private Duration lockLease = Duration.ofSeconds(30);
        private Duration lockExtendInterval = Duration.ofSeconds(10);
        private Duration statusHeartbeatInterval = Duration.ofSeconds(10);
        private Duration holderLossSweepInterval = Duration.ofSeconds(15);
        private Duration idempotencyPrimaryTtl = Duration.ofHours(24);
        private Duration idempotencySecondaryTtl = Duration.ofSeconds(30);
        private Duration idempotencyForwardTtl = Duration.ofMinutes(5);
        private Duration releaseInterruptTimeout = Duration.ofSeconds(5);
        private String nodeId = "test-node";
        private SessionMetrics metrics = SessionMetrics.NOOP;
        private SessionApprovalStore sessionApprovals;

        private Builder() {
        }

        /**
         * Shares an election backend with another harness, so the two managers compete for the same sessions.
         *
         * @param v
         *            the shared lease store (must not be null)
         * @return this builder
         */
        public Builder leaseStore(SessionLeaseStore v) {
            this.leaseStore = Objects.requireNonNull(v);
            return this;
        }

        public Builder idempotencyStore(IdempotencyStore v) {
            this.idempotencyStore = Objects.requireNonNull(v);
            return this;
        }

        /**
         * Shares a signal bus with another harness, so the two managers behave like two nodes on one rail.
         *
         * @param v
         *            the bus both nodes publish to and subscribe on (must not be null)
         * @return this builder
         */
        public Builder signalBus(SessionSignalBus v) {
            this.signalBus = Objects.requireNonNull(v);
            return this;
        }

        /**
         * Shares an inbox with another harness — required alongside {@link #leaseStore(SessionLeaseStore)}
         * whenever a test
         * needs the non-holder node to queue a message for the holder rather than open its own session.
         *
         * @param v
         *            the shared inbox (must not be null)
         * @return this builder
         */
        public Builder inbox(SessionInbox v) {
            this.inbox = Objects.requireNonNull(v);
            return this;
        }

        /**
         * Shares a session repository with another harness, so both nodes resolve the same aggregate.
         *
         * @param v
         *            the shared repository (must not be null)
         * @return this builder
         */
        public Builder repository(SessionRecordStore v) {
            this.repository = Objects.requireNonNull(v);
            return this;
        }

        public Builder idleTtl(Duration v) {
            this.idleTtl = v;
            return this;
        }

        public Builder maxCachedSessions(int v) {
            this.maxCachedSessions = v;
            return this;
        }

        public Builder lockLease(Duration v) {
            this.lockLease = v;
            return this;
        }

        public Builder lockExtendInterval(Duration v) {
            this.lockExtendInterval = v;
            return this;
        }

        /**
         * Sets the {@code STATUS} heartbeat cadence, which is no longer tied to {@link #lockExtendInterval(Duration)} —
         * a test that shortens the renewal tick no longer floods the signal bus with status snapshots as a side effect.
         *
         * @param v
         *            the heartbeat cadence
         * @return this builder
         */
        public Builder statusHeartbeatInterval(Duration v) {
            this.statusHeartbeatInterval = v;
            return this;
        }

        public Builder holderLossSweepInterval(Duration v) {
            this.holderLossSweepInterval = v;
            return this;
        }

        public Builder releaseInterruptTimeout(Duration v) {
            this.releaseInterruptTimeout = v;
            return this;
        }

        /**
         * Shortens the secondary idempotency TTL, which is also what the manager derives its forwarded-turn poll
         * interval from — the knob a test needs to exercise the polling fallback without waiting out the production
         * cadence. The manager floors the interval at one second, so values below {@code 2s} all poll once per second.
         *
         * @param v
         *            the TTL applied to a fresh {@code IN_FLIGHT} reservation (must not be null)
         * @return this builder
         */
        public Builder idempotencySecondaryTtl(Duration v) {
            this.idempotencySecondaryTtl = Objects.requireNonNull(v);
            return this;
        }

        /**
         * Bounds how long a forwarded turn may go unresolved before its future fails with a timeout.
         *
         * @param v
         *            the deadline measured from the moment the message is handed to the inbox (must not be null)
         * @return this builder
         */
        public Builder idempotencyForwardTtl(Duration v) {
            this.idempotencyForwardTtl = Objects.requireNonNull(v);
            return this;
        }

        public Builder nodeId(String v) {
            this.nodeId = v;
            return this;
        }

        public Builder metrics(SessionMetrics v) {
            this.metrics = Objects.requireNonNull(v);
            return this;
        }

        /**
         * Wires the session-scoped skill approval store the manager purges on release / delete / peer EVICT.
         * Leaving it unset keeps the pre-Phase-5 behavior (no purge).
         *
         * @param v
         *            the store (may be null)
         * @return this builder
         */
        public Builder sessionApprovals(SessionApprovalStore v) {
            this.sessionApprovals = v;
            return this;
        }

        public TestManagerHarness build() {
            final ConcurrentMap<SessionId, TestLiveSession> sessions = new ConcurrentHashMap<>();
            final ConcurrentMap<SessionId, AgentRuntimeId> openedRuntimeIds = new ConcurrentHashMap<>();
            final LiveSessionOpener opener = (id, agentRuntimeId, options, openAttributes) -> {
                openedRuntimeIds.put(id, agentRuntimeId);
                return sessions.computeIfAbsent(id, TestLiveSession::new);
            };
            final SessionStore store = new DefaultSessionStore(leaseStore, repository);
            final SessionRouterConfig config = SessionRouterConfig.builder().store(store).signalBus(signalBus)
                    .inbox(inbox).idempotencyStore(idempotencyStore).nodeId(nodeId).idleTtl(idleTtl)
                    .maxCachedSessions(maxCachedSessions).lockLease(lockLease).lockExtendInterval(lockExtendInterval)
                    .statusHeartbeatInterval(statusHeartbeatInterval).holderLossSweepInterval(holderLossSweepInterval)
                    .idempotencyPrimaryTtl(idempotencyPrimaryTtl).idempotencySecondaryTtl(idempotencySecondaryTtl)
                    .idempotencyForwardTtl(idempotencyForwardTtl).releaseInterruptTimeout(releaseInterruptTimeout)
                    .metrics(metrics).sessionApprovalStore(sessionApprovals).build();
            final SessionRouter manager = new DefaultSessionRouter(opener, config);
            return new TestManagerHarness(manager, leaseStore, signalBus, inbox, idempotencyStore, repository,
                    sessionApprovals, sessions, openedRuntimeIds);
        }
    }
}
