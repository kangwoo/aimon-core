package at.aimon.core.agent.session.store;

import java.time.Instant;
import java.util.Objects;

/**
 * Who currently holds a session, as observed by {@link SessionLeaseStore#findHolder}.
 *
 * <p>
 * This is an <em>observational</em> answer, not a capability: it carries no fencing authority and must never be used to
 * decide whether the local node may write. It exists so a non-holder can name the holder — to route a hand-off request
 * at it, to report a rejection honestly instead of returning a bare empty {@code Optional}, and for diagnostics.
 *
 * <p>
 * A present {@code LeaseHolder} means "held at the moment the backend answered". The lease may expire a microsecond
 * later; the only way to learn that you hold a lease is to have won one.
 */
public final class LeaseHolder {

    private final String holderId;
    private final long fencingToken;
    private final Instant expiresAt;

    private LeaseHolder(Builder builder) {
        this.holderId = Objects.requireNonNull(builder.holderId, "holderId must not be null");
        this.fencingToken = builder.fencingToken;
        this.expiresAt = Objects.requireNonNull(builder.expiresAt, "expiresAt must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getHolderId() {
        return holderId;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    /**
     * When the observed lease expires, as reported by the backend. Backends that evaluate time server-side report their
     * own clock's instant here, so comparing this against a local clock is only ever approximate.
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "LeaseHolder{" + holderId + ", token=" + fencingToken + ", expiresAt=" + expiresAt + '}';
    }

    /** Builder for {@link LeaseHolder}. */
    public static final class Builder {

        private String holderId;
        private long fencingToken;
        private Instant expiresAt;

        private Builder() {
        }

        public Builder holderId(String holderId) {
            this.holderId = holderId;
            return this;
        }

        public Builder fencingToken(long fencingToken) {
            this.fencingToken = fencingToken;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public LeaseHolder build() {
            return new LeaseHolder(this);
        }
    }
}
