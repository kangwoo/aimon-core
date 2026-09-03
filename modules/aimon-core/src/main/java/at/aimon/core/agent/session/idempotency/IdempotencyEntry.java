package at.aimon.core.agent.session.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.SessionId;

/**
 * Immutable {@link IdempotencyStore} entry.
 *
 * <p>
 * Tracks the lifecycle of one {@code idempotencyKey}: which session it targeted, the input hash, current
 * {@link Status}, the holder that owns the in-flight turn, the cached final result (when {@code DONE}), and timestamps
 * used for primary / secondary TTL bookkeeping (design §9.2).
 *
 * <h2>Invariants</h2>
 * <ul>
 * <li>{@link Status#IN_FLIGHT} with a non-null {@code holderId} means <b>a turn is executing on that holder right
 * now</b>. The holder-loss sweeper watches exactly these: the holder's lease renewer is expected to
 * {@link IdempotencyStore#touch} the entry, so going quiet past the secondary TTL is evidence the holder died.
 * <li>{@link Status#IN_FLIGHT} with a null {@code holderId} means <b>reserved, but nobody is executing it</b> — the
 * turn is sitting in a {@code SessionInbox} waiting for whichever node holds the session lock to drain it
 * (see {@link IdempotencyStore#releaseHolder}). Nobody is expected to touch such an entry, and the sweeper skips it;
 * treating it as holder loss would evict a perfectly healthy session. The node that collects the message is expected
 * to write its own name back with {@link IdempotencyStore#acquireHolder} before executing it, returning the entry to
 * the first case above — but that take-over can be refused or fail, and a turn then runs against a holderless entry.
 * So this is the state of a turn <b>nobody is known to be executing</b>, which is not quite the same as one nobody is
 * executing; see {@link IdempotencyStore#acquireHolder} for what the difference costs.
 * <li>{@link Status#DONE} entries may carry a null {@code holderId} once cleanup releases it.
 * </ul>
 */
public final class IdempotencyEntry {

    /** Lifecycle status of an idempotency entry. */
    public enum Status {
        /** A turn keyed by this idempotency key is currently executing. */
        IN_FLIGHT,
        /** A turn completed and its result is cached for replay. */
        DONE
    }

    private final String key;
    private final SessionId sessionId;
    private final String inputHash;
    private final Status status;
    private final String holderId;
    private final AgentExecutionResult result;
    private final Instant createdAt;
    private final Instant lastTouchedAt;

    private IdempotencyEntry(Builder b) {
        this.key = Objects.requireNonNull(b.key, "key must not be null");
        this.sessionId = Objects.requireNonNull(b.sessionId, "sessionId must not be null");
        this.inputHash = Objects.requireNonNull(b.inputHash, "inputHash must not be null");
        this.status = Objects.requireNonNull(b.status, "status must not be null");
        this.holderId = b.holderId;
        this.result = b.result;
        this.createdAt = Objects.requireNonNull(b.createdAt, "createdAt must not be null");
        this.lastTouchedAt = Objects.requireNonNull(b.lastTouchedAt, "lastTouchedAt must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getKey() {
        return key;
    }

    public SessionId getSessionId() {
        return sessionId;
    }

    public String getInputHash() {
        return inputHash;
    }

    public Status getStatus() {
        return status;
    }

    public Optional<String> getHolderId() {
        return Optional.ofNullable(holderId);
    }

    public Optional<AgentExecutionResult> getResult() {
        return Optional.ofNullable(result);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastTouchedAt() {
        return lastTouchedAt;
    }

    /** Builder for {@link IdempotencyEntry}. */
    public static final class Builder {
        private String key;
        private SessionId sessionId;
        private String inputHash;
        private Status status;
        private String holderId;
        private AgentExecutionResult result;
        private Instant createdAt;
        private Instant lastTouchedAt;

        private Builder() {
        }

        public Builder key(String v) {
            this.key = v;
            return this;
        }

        public Builder sessionId(SessionId v) {
            this.sessionId = v;
            return this;
        }

        public Builder inputHash(String v) {
            this.inputHash = v;
            return this;
        }

        public Builder status(Status v) {
            this.status = v;
            return this;
        }

        public Builder holderId(String v) {
            this.holderId = v;
            return this;
        }

        public Builder result(AgentExecutionResult v) {
            this.result = v;
            return this;
        }

        public Builder createdAt(Instant v) {
            this.createdAt = v;
            return this;
        }

        public Builder lastTouchedAt(Instant v) {
            this.lastTouchedAt = v;
            return this;
        }

        public IdempotencyEntry build() {
            return new IdempotencyEntry(this);
        }
    }
}
