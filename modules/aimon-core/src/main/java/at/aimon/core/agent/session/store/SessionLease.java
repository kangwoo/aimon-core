package at.aimon.core.agent.session.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.session.SessionId;

/**
 * Immutable proof that this node is the elected holder for one session.
 *
 * <p>
 * Returned by a successful {@link SessionLeaseStore#tryAcquire} and, in the merged form, inside
 * {@link ClaimResult.Acquired}. The {@code fencingToken} is strictly monotonic per {@link SessionId} for the whole
 * lifetime of that session, which is what lets a backend reject an {@code extend} / {@code release} from a holder
 * that has already lost the lease, and what lets {@link SessionStore#records()} re-prove holdership immediately
 * before a record write.
 *
 * <p>
 * Formerly {@code ConversationLock.LockHandle} in {@code aimon-session-base}. It moved into {@code aimon-core} for the
 * same reason the whole SPI did: it is keyed by {@link SessionId}, so the dependency can only point this way.
 */
public final class SessionLease {

    private final SessionId sessionId;
    private final String holderId;
    private final long fencingToken;
    private final Instant acquiredAt;
    private final Duration lease;

    private SessionLease(Builder builder) {
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId must not be null");
        this.holderId = Objects.requireNonNull(builder.holderId, "holderId must not be null");
        this.fencingToken = builder.fencingToken;
        this.acquiredAt = Objects.requireNonNull(builder.acquiredAt, "acquiredAt must not be null");
        this.lease = Objects.requireNonNull(builder.lease, "lease must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * The elected holder's identity. Node-derived and stable across turns and threads — see
     * {@link SessionLeaseStore} for why the backends never compare it on acquire.
     */
    public String getHolderId() {
        return holderId;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    /**
     * The lease duration requested at acquire time. Not an expiry instant: the authoritative expiry lives at the
     * backend, which evaluates it against its own clock so node clock skew cannot affect a fencing decision.
     */
    public Duration getLease() {
        return lease;
    }

    @Override
    public String toString() {
        return "SessionLease{" + sessionId.value() + ", holder=" + holderId + ", token=" + fencingToken + '}';
    }

    /** Builder for {@link SessionLease}. */
    public static final class Builder {

        private SessionId sessionId;
        private String holderId;
        private long fencingToken;
        private Instant acquiredAt;
        private Duration lease;

        private Builder() {
        }

        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder holderId(String holderId) {
            this.holderId = holderId;
            return this;
        }

        public Builder fencingToken(long fencingToken) {
            this.fencingToken = fencingToken;
            return this;
        }

        public Builder acquiredAt(Instant acquiredAt) {
            this.acquiredAt = acquiredAt;
            return this;
        }

        public Builder lease(Duration lease) {
            this.lease = lease;
            return this;
        }

        public SessionLease build() {
            return new SessionLease(this);
        }
    }
}
