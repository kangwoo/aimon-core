package at.aimon.session.routing.internal;

import java.time.Duration;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.SessionStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.session.routing.metrics.SessionMetrics;

/**
 * Immutable holder grouping the shared collaborators the two {@link DefaultSessionRouter} constructors take
 * APART from the session source (the {@code LiveSessionOpener} / {@code LiveSessionFactory}).
 *
 * <p>
 * Internal to the module; constructed by {@link at.aimon.session.routing.builder.SessionRouterBuilder}. Field
 * semantics and validation are unchanged from the previous flat constructor argument list.
 */
public final class SessionRouterConfig {

    private final SessionStore store;
    private final SessionSignalBus signalBus;
    private final SessionInbox inbox;
    private final IdempotencyStore idempotencyStore;
    private final String nodeId;
    private final Duration idleTtl;
    private final int maxCachedSessions;
    private final Duration lockLease;
    private final Duration lockExtendInterval;
    private final Duration statusHeartbeatInterval;
    private final Duration holderLossSweepInterval;
    private final Duration idempotencyPrimaryTtl;
    private final Duration idempotencySecondaryTtl;
    private final Duration idempotencyForwardTtl;
    private final Duration releaseInterruptTimeout;
    private final SessionMetrics metrics;
    private final SessionApprovalStore sessionApprovalStore;

    private SessionRouterConfig(Builder builder) {
        this.store = builder.store;
        this.signalBus = builder.signalBus;
        this.inbox = builder.inbox;
        this.idempotencyStore = builder.idempotencyStore;
        this.nodeId = builder.nodeId;
        this.idleTtl = builder.idleTtl;
        this.maxCachedSessions = builder.maxCachedSessions;
        this.lockLease = builder.lockLease;
        this.lockExtendInterval = builder.lockExtendInterval;
        this.statusHeartbeatInterval = builder.statusHeartbeatInterval;
        this.holderLossSweepInterval = builder.holderLossSweepInterval;
        this.idempotencyPrimaryTtl = builder.idempotencyPrimaryTtl;
        this.idempotencySecondaryTtl = builder.idempotencySecondaryTtl;
        this.idempotencyForwardTtl = builder.idempotencyForwardTtl;
        this.releaseInterruptTimeout = builder.releaseInterruptTimeout;
        this.metrics = builder.metrics;
        this.sessionApprovalStore = builder.sessionApprovalStore;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The single door to session state: holder election, the durable record, and the agent binding.
     *
     * <p>
     * One store per manager. Two managers in one JVM must not share one — the store remembers which leases the local
     * node holds, and sharing it would make each manager see the other's leases as its own.
     *
     * @return the session store
     */
    public SessionStore store() {
        return store;
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

    public String nodeId() {
        return nodeId;
    }

    public Duration idleTtl() {
        return idleTtl;
    }

    public int maxCachedSessions() {
        return maxCachedSessions;
    }

    public Duration lockLease() {
        return lockLease;
    }

    public Duration lockExtendInterval() {
        return lockExtendInterval;
    }

    /**
     * Cadence of the holder-side {@code STATUS} heartbeat. Independent of {@link #lockExtendInterval()} — it was an
     * alias of it until the two decisions were separated, so a deployment that tuned one no longer moves the other.
     *
     * @return the heartbeat cadence
     */
    public Duration statusHeartbeatInterval() {
        return statusHeartbeatInterval;
    }

    public Duration holderLossSweepInterval() {
        return holderLossSweepInterval;
    }

    public Duration idempotencyPrimaryTtl() {
        return idempotencyPrimaryTtl;
    }

    public Duration idempotencySecondaryTtl() {
        return idempotencySecondaryTtl;
    }

    /**
     * How long a key stays reserved after its submit lost the lock and forwarded the turn to the inbox. Covers the
     * expected inbox wait rather than a lease length — nobody touches the entry while it is queued.
     *
     * @return the forward-reservation TTL
     */
    public Duration idempotencyForwardTtl() {
        return idempotencyForwardTtl;
    }

    public Duration releaseInterruptTimeout() {
        return releaseInterruptTimeout;
    }

    public SessionMetrics metrics() {
        return metrics;
    }

    /**
     * The session-scoped skill approval store to purge when a session is released or deleted, or {@code null}
     * when the deployment does not cache approvals per session.
     *
     * @return the store, or {@code null}
     */
    public SessionApprovalStore sessionApprovalStore() {
        return sessionApprovalStore;
    }

    public static final class Builder {

        private SessionStore store;
        private SessionSignalBus signalBus;
        private SessionInbox inbox;
        private IdempotencyStore idempotencyStore;
        private String nodeId;
        private Duration idleTtl;
        private int maxCachedSessions;
        private Duration lockLease;
        private Duration lockExtendInterval;
        private Duration statusHeartbeatInterval;
        private Duration holderLossSweepInterval;
        private Duration idempotencyPrimaryTtl;
        private Duration idempotencySecondaryTtl;
        private Duration idempotencyForwardTtl;
        private Duration releaseInterruptTimeout;
        private SessionMetrics metrics;
        private SessionApprovalStore sessionApprovalStore;

        private Builder() {
        }

        public Builder store(SessionStore store) {
            this.store = store;
            return this;
        }

        public Builder signalBus(SessionSignalBus signalBus) {
            this.signalBus = signalBus;
            return this;
        }

        public Builder inbox(SessionInbox inbox) {
            this.inbox = inbox;
            return this;
        }

        public Builder idempotencyStore(IdempotencyStore idempotencyStore) {
            this.idempotencyStore = idempotencyStore;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder idleTtl(Duration idleTtl) {
            this.idleTtl = idleTtl;
            return this;
        }

        public Builder maxCachedSessions(int maxCachedSessions) {
            this.maxCachedSessions = maxCachedSessions;
            return this;
        }

        public Builder lockLease(Duration lockLease) {
            this.lockLease = lockLease;
            return this;
        }

        public Builder lockExtendInterval(Duration lockExtendInterval) {
            this.lockExtendInterval = lockExtendInterval;
            return this;
        }

        public Builder statusHeartbeatInterval(Duration statusHeartbeatInterval) {
            this.statusHeartbeatInterval = statusHeartbeatInterval;
            return this;
        }

        public Builder holderLossSweepInterval(Duration holderLossSweepInterval) {
            this.holderLossSweepInterval = holderLossSweepInterval;
            return this;
        }

        public Builder idempotencyPrimaryTtl(Duration idempotencyPrimaryTtl) {
            this.idempotencyPrimaryTtl = idempotencyPrimaryTtl;
            return this;
        }

        public Builder idempotencySecondaryTtl(Duration idempotencySecondaryTtl) {
            this.idempotencySecondaryTtl = idempotencySecondaryTtl;
            return this;
        }

        public Builder idempotencyForwardTtl(Duration idempotencyForwardTtl) {
            this.idempotencyForwardTtl = idempotencyForwardTtl;
            return this;
        }

        public Builder releaseInterruptTimeout(Duration releaseInterruptTimeout) {
            this.releaseInterruptTimeout = releaseInterruptTimeout;
            return this;
        }

        public Builder metrics(SessionMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder sessionApprovalStore(SessionApprovalStore sessionApprovalStore) {
            this.sessionApprovalStore = sessionApprovalStore;
            return this;
        }

        /**
         * Validates the lease timings and freezes the config.
         *
         * <p>
         * The checks live here rather than in {@code SessionRouterBuilder} alone because this is the one door
         * every construction path goes through — the fluent builder, the test harnesses, and any embedder holding the
         * internal type. The manager is what breaks when the invariant is violated, so the type it consumes is where
         * the violation has to be refused.
         *
         * @return the config (never null)
         * @throws IllegalStateException
         *             if a duration is non-positive, or if {@code lockExtendInterval >= lockLease}
         */
        public SessionRouterConfig build() {
            requirePositive(lockLease, "lockLease");
            requirePositive(lockExtendInterval, "lockExtendInterval");
            requirePositive(statusHeartbeatInterval, "statusHeartbeatInterval");
            // Documented on SessionRouterBuilder#lockLease since that builder existed, enforced nowhere until
            // now. An interval at or above the lease is not a slow configuration, it is a broken one: the renewer's
            // first tick fires after the lease it exists to extend has already expired, so every holder loses its
            // session on a timer and a peer is free to start a second turn on it.
            if (lockExtendInterval.compareTo(lockLease) >= 0) {
                throw new IllegalStateException(
                        "lockExtendInterval (" + lockExtendInterval + ") must be strictly less than lockLease ("
                                + lockLease + ") — otherwise the lease expires before the first renewal tick fires");
            }
            return new SessionRouterConfig(this);
        }

        private static void requirePositive(Duration value, String name) {
            if (value == null) {
                throw new IllegalStateException(name + " must not be null");
            }
            if (value.isZero() || value.isNegative()) {
                throw new IllegalStateException(name + " must be positive: " + value);
            }
        }
    }
}
