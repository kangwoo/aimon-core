package at.aimon.core.agent.session.signal;

import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.session.SessionId;

/**
 * Cross-node signal payload broadcast through {@link SessionSignalBus}.
 *
 * <p>
 * Carries the {@link SignalKind} (interrupt, evict, yield, message-enqueued, event, turn-result, status), the
 * {@link SessionId} the signal applies to, the originating node id (so the receiving node can dedup
 * self-broadcast), and an opaque payload map. Immutable value object built via {@link Builder}.
 */
public final class SessionSignal {

    /**
     * Distinguishes the cross-node signal flavors per design §5.4.
     */
    public enum SignalKind {
        /** User-requested interrupt; trips the active turn on the holder node. */
        INTERRUPT,
        /** Cache eviction request; receiving nodes drop their local cache entry. */
        EVICT,
        /**
         * Hand this session over: the holder stops whatever turn is running, drops its cached live session, and returns
         * the session lease so the asking node can take it (design §7.4).
         *
         * <p>
         * The two neighbours above are what this is <em>not</em>. {@link #INTERRUPT} stops a turn and leaves the
         * session exactly where it was, so a peer that only interrupts waits forever for a lease the holder has no
         * reason to give up. {@link #EVICT} is terminal and after the fact: it says the session is *gone*, and its
         * handler completes subscribers, fails forwarded submissions, purges approvals and drops the status projection.
         * A yield must do none of that — the session still exists and is about to be served somewhere else, so
         * queued inbox messages stay queued for the new holder, forwarded futures stay pending, and subscribers keep
         * their streams. Reusing {@code EVICT} to move a session would tell every waiting caller it had been
         * deleted.
         *
         * <p>
         * No payload is required; the session id and the origin node are the whole request. The receiver decides
         * how to comply, and complying is best-effort by nature: a pinned session defers its close to the running
         * turn's end, so a yield costs the asker the remainder of one turn rather than taking effect instantly.
         *
         * <p>
         * <b>Rollout.</b> A subscriber that predates this kind cannot decode it — all three rails resolve the name
         * through {@code SignalKind.valueOf}, log, and drop the whole signal (redis and mongo codecs, postgres
         * {@code ListenDispatcher}). Publishers therefore send the legacy {@code INTERRUPT(SESSION_RELEASED)} alongside
         * it, and receivers keep honoring that legacy form, until every node in the cluster understands this kind. Both
         * halves of that shim are marked in {@code DefaultSessionRouter}.
         */
        YIELD,
        /** A new {@code InboundMessage} was delivered to the inbox; holder may collect. */
        MESSAGE_ENQUEUED,
        /** Streaming {@code AgentExecutionEvent} envelope for cross-node {@code events()} fan-out. */
        EVENT,
        /**
         * Terminal outcome of one forwarded turn, published by the holder so the node that accepted the submission can
         * complete the future it handed its caller (design §7.1 F6/F7).
         *
         * <p>
         * Published <em>after</em> {@code IdempotencyStore.markDone} so durability never lags the notification: a node
         * that hears this and crashes before acting can still recover the result from the store, whereas the reverse
         * order would announce a result that no longer exists after a holder crash.
         *
         * <p>
         * Payload is a flat primitive map (see {@code TurnResultPayload}) — never a typed object. Delivery is
         * <b>best-effort</b>: the Redis rail is a plain {@code PUBLISH} with no replay, and an older subscriber decodes
         * the kind with {@code SignalKind.valueOf} and drops the whole signal. Origin nodes therefore must also poll
         * the idempotency store; this rail only makes the common case prompt.
         */
        TURN_RESULT,
        /**
         * Holder-pushed {@code LiveSessionStatus} snapshot for cluster-wide observability. The holder broadcasts a
         * flat, JSON-primitive snapshot (see {@code StatusSnapshotPayload}) on phase transitions and a low-rate
         * heartbeat; every node folds it into a local projection so any node can answer a status query without
         * locating the holder. Payload is a flat primitive map — never a typed object — so it round-trips through the
         * real bus codecs.
         */
        STATUS
    }

    private final SessionId sessionId;
    private final SignalKind kind;
    private final String originNodeId;
    private final Map<String, Object> payload;

    private SessionSignal(Builder b) {
        this.sessionId = Objects.requireNonNull(b.sessionId, "sessionId must not be null");
        this.kind = Objects.requireNonNull(b.kind, "kind must not be null");
        this.originNodeId = Objects.requireNonNull(b.originNodeId, "originNodeId must not be null");
        this.payload = b.payload != null ? Map.copyOf(b.payload) : Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public SessionId getSessionId() {
        return sessionId;
    }

    public SignalKind getKind() {
        return kind;
    }

    public String getOriginNodeId() {
        return originNodeId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    /** Builder for {@link SessionSignal}. */
    public static final class Builder {
        private SessionId sessionId;
        private SignalKind kind;
        private String originNodeId;
        private Map<String, Object> payload;

        private Builder() {
        }

        public Builder sessionId(SessionId v) {
            this.sessionId = v;
            return this;
        }

        public Builder kind(SignalKind v) {
            this.kind = v;
            return this;
        }

        public Builder originNodeId(String v) {
            this.originNodeId = v;
            return this;
        }

        public Builder payload(Map<String, Object> v) {
            this.payload = v;
            return this;
        }

        public SessionSignal build() {
            return new SessionSignal(this);
        }
    }
}
