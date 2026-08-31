package at.aimon.session.routing.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.SessionId;

/**
 * Per-node, last-known-status projection: the local replica of cluster-wide {@link LiveSessionStatus} snapshots that
 * holder nodes broadcast on the {@link at.aimon.core.agent.session.signal.SessionSignal.SignalKind#STATUS} rail.
 *
 * <p>
 * A node folds every remote {@code STATUS} signal it receives (for sessions it is subscribed to) into this map so
 * {@link DefaultSessionRouter#status} can answer a query about a session running on another node without
 * locating the holder. Thread-safe; reads and folds happen on the signal-dispatch threads while
 * {@code status(...)} reads concurrently.
 *
 * <p>
 * <b>Ordering.</b> The signal bus guarantees no ordering, so {@link #apply} keeps the freshest snapshot per
 * session using a {@code seq} stamp: a snapshot is accepted when it comes from a <em>different</em> origin node
 * (a holder hand-off always wins, since the new holder's local {@code seq} is independent of the old holder's) or has a
 * strictly greater {@code seq} from the <em>same</em> origin (a newer push from the current holder). This prevents an
 * out-of-order or duplicate delivery from clobbering a newer snapshot while still letting hand-offs take effect.
 */
final class StatusProjection {

    private final ConcurrentMap<SessionId, RemoteStatus> entries = new ConcurrentHashMap<>();

    /**
     * Folds a remote snapshot in, keeping the freshest per session (see class-level ordering note).
     *
     * @param sessionId
     *            the session the snapshot describes (must not be null)
     * @param status
     *            the remote snapshot (must not be null)
     * @param originNodeId
     *            the node that produced the snapshot (must not be null)
     * @param observedAt
     *            when the snapshot was taken on the origin node (must not be null)
     * @param seq
     *            the origin node's monotonic stamp for this snapshot
     */
    void apply(SessionId sessionId, LiveSessionStatus status, String originNodeId, Instant observedAt, long seq) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(originNodeId, "originNodeId must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        final RemoteStatus incoming = new RemoteStatus(status, originNodeId, observedAt, seq);
        entries.merge(sessionId, incoming, (existing, candidate) -> {
            final boolean handoff = !existing.originNodeId.equals(candidate.originNodeId);
            return handoff || candidate.seq > existing.seq ? candidate : existing;
        });
    }

    /**
     * @param sessionId
     *            the session (must not be null)
     * @return the last-known remote snapshot, or empty if none has been projected here
     */
    Optional<RemoteStatus> lookup(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return Optional.ofNullable(entries.get(sessionId));
    }

    /**
     * Drops the projected entry for {@code sessionId} (e.g. on an {@code EVICT} signal or terminal teardown).
     *
     * @param sessionId
     *            the session (must not be null)
     */
    void remove(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        entries.remove(sessionId);
    }

    /** A projected snapshot plus the provenance the manager surfaces through {@code ClusterSessionStatus}. */
    static final class RemoteStatus {
        private final LiveSessionStatus status;
        private final String originNodeId;
        private final Instant observedAt;
        private final long seq;

        RemoteStatus(LiveSessionStatus status, String originNodeId, Instant observedAt, long seq) {
            this.status = Objects.requireNonNull(status, "status must not be null");
            this.originNodeId = Objects.requireNonNull(originNodeId, "originNodeId must not be null");
            this.observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            this.seq = seq;
        }

        LiveSessionStatus status() {
            return status;
        }

        String originNodeId() {
            return originNodeId;
        }

        Instant observedAt() {
            return observedAt;
        }

        long seq() {
            return seq;
        }
    }
}
