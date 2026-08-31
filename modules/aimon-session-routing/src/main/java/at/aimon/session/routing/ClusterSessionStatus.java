package at.aimon.session.routing;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.SessionId;

/**
 * Cluster-aware result of {@link SessionRouter#status(SessionId)} — a {@link LiveSessionStatus} snapshot
 * together with provenance so the caller can reason about its freshness.
 *
 * <p>
 * A live session is node-local and exists only on the lock-holding node, so a status read resolves to one of
 * three {@link Source}s:
 *
 * <ul>
 * <li>{@link Source#LOCAL_HOLDER} — the querying node currently holds the session; the snapshot is the live,
 * authoritative {@link at.aimon.core.agent.session.LiveSession#status() status()} read with zero staleness.
 * <li>{@link Source#REMOTE_PROJECTION} — another node holds the session; the snapshot is the last value that node
 * pushed
 * onto the {@code STATUS} rail, tagged with the origin node and the {@code observedAt} instant so callers can judge how
 * stale it may be.
 * <li>{@link Source#UNKNOWN} — no node has been observed running this session (never started here, evicted, or not
 * yet projected); no snapshot is available.
 * </ul>
 *
 * <p>
 * Like {@link LiveSessionStatus} itself, this is a best-effort, point-in-time observability value — never a control
 * gate. Immutable value object.
 */
public final class ClusterSessionStatus {

    /** Provenance of a {@link ClusterSessionStatus}. */
    public enum Source {
        /** The querying node holds the session; the snapshot is the live local read. */
        LOCAL_HOLDER,
        /** Another node holds the session; the snapshot came from that node's last {@code STATUS} push. */
        REMOTE_PROJECTION,
        /** No node was observed running the session; no snapshot is available. */
        UNKNOWN
    }

    private final SessionId sessionId;
    private final LiveSessionStatus status;
    private final Source source;
    private final String originNodeId;
    private final Instant observedAt;

    private ClusterSessionStatus(SessionId sessionId, LiveSessionStatus status, Source source, String originNodeId,
            Instant observedAt) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.status = status;
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.originNodeId = originNodeId;
        this.observedAt = observedAt;
    }

    /**
     * The querying node is the holder — the snapshot is the live, authoritative local read.
     *
     * @param status
     *            the live snapshot from the local session (must not be null)
     * @param nodeId
     *            the local node id (must not be null)
     * @return a {@link Source#LOCAL_HOLDER} result (never null)
     */
    public static ClusterSessionStatus localHolder(LiveSessionStatus status, String nodeId) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return new ClusterSessionStatus(status.getSessionId(), status, Source.LOCAL_HOLDER, nodeId, null);
    }

    /**
     * Another node holds the session — the snapshot is its last pushed value.
     *
     * @param status
     *            the projected snapshot (must not be null)
     * @param originNodeId
     *            the node that produced the snapshot (must not be null)
     * @param observedAt
     *            the instant the snapshot was taken on the origin node (must not be null)
     * @return a {@link Source#REMOTE_PROJECTION} result (never null)
     */
    public static ClusterSessionStatus remote(LiveSessionStatus status, String originNodeId, Instant observedAt) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(originNodeId, "originNodeId must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        return new ClusterSessionStatus(status.getSessionId(), status, Source.REMOTE_PROJECTION, originNodeId,
                observedAt);
    }

    /**
     * No node was observed running the session.
     *
     * @param sessionId
     *            the queried session (must not be null)
     * @return a {@link Source#UNKNOWN} result (never null)
     */
    public static ClusterSessionStatus unknown(SessionId sessionId) {
        return new ClusterSessionStatus(sessionId, null, Source.UNKNOWN, null, null);
    }

    /**
     * @return the session this result describes (never null)
     */
    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * @return the provenance of this result (never null)
     */
    public Source getSource() {
        return source;
    }

    /**
     * @return the status snapshot, or empty for {@link Source#UNKNOWN}
     */
    public Optional<LiveSessionStatus> getStatus() {
        return Optional.ofNullable(status);
    }

    /**
     * @return the node that produced the snapshot ({@link Source#LOCAL_HOLDER} or {@link Source#REMOTE_PROJECTION}), or
     *         empty for {@link Source#UNKNOWN}
     */
    public Optional<String> getOriginNodeId() {
        return Optional.ofNullable(originNodeId);
    }

    /**
     * @return when the snapshot was taken on the origin node; present only for {@link Source#REMOTE_PROJECTION} (a
     *         {@link Source#LOCAL_HOLDER} read is live and carries no staleness stamp)
     */
    public Optional<Instant> getObservedAt() {
        return Optional.ofNullable(observedAt);
    }

    /**
     * @return {@code true} unless the result is {@link Source#UNKNOWN}
     */
    public boolean isKnown() {
        return source != Source.UNKNOWN;
    }

    @Override
    public String toString() {
        return "ClusterSessionStatus{sessionId=" + sessionId + ", source=" + source + ", originNodeId=" + originNodeId
                + ", observedAt=" + observedAt + ", status=" + status + '}';
    }
}
