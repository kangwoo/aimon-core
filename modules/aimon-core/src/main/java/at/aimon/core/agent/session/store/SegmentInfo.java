package at.aimon.core.agent.session.store;

import java.time.Instant;
import java.util.Objects;

/**
 * What {@link SessionLogSegmentStore#list(at.aimon.core.agent.session.SessionId)} reports about one stored segment:
 * its id and when it was written. Exactly what garbage collection needs to decide whether a segment no manifest points
 * at is old enough to delete (session-log §5.4) — and no payload, so listing a session does not read its history.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class SegmentInfo {

    private final SegmentId id;
    private final Instant createdAt;

    private SegmentInfo(SegmentId id, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }

    /**
     * @param id
     *            the segment id (must not be null)
     * @param createdAt
     *            when the segment was written (must not be null)
     * @return the info (never null)
     */
    public static SegmentInfo of(SegmentId id, Instant createdAt) {
        return new SegmentInfo(id, createdAt);
    }

    /**
     * @return the segment id (never null)
     */
    public SegmentId getId() {
        return id;
    }

    /**
     * @return when the segment was written (never null)
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof SegmentInfo other && id.equals(other.id) && createdAt.equals(other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, createdAt);
    }

    @Override
    public String toString() {
        return "SegmentInfo{id=" + id + ", createdAt=" + createdAt + "}";
    }
}
