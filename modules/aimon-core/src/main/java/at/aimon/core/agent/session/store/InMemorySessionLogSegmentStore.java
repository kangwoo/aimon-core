package at.aimon.core.agent.session.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;

/**
 * The default {@link SessionLogSegmentStore}: segments held in this JVM's heap, keyed by session and then by id.
 *
 * <p>
 * The reference implementation of the contract and the right pairing for an {@link InMemorySessionRecordStore} — the
 * two lose everything on restart together, so no manifest can outlive the segments it names. Pairing it with a durable
 * record store is the one wiring to avoid: after a restart every sealed range would read back as a gap.
 *
 * <p>
 * Thread-safe.
 */
public final class InMemorySessionLogSegmentStore implements SessionLogSegmentStore {

    /**
     * Both levels are {@link ConcurrentHashMap}s. Every write that can create or detach an inner map runs inside a
     * {@code compute} on the outer one, so a {@link #put} can never land in an inner map that a concurrent
     * {@link #delete} of the session's last segment has just detached — which would lose the segment and leave its
     * manifest entry dangling.
     */
    private final Map<SessionId, Map<SegmentId, SessionLogSegment>> segments = new ConcurrentHashMap<>();

    @Override
    public void put(SessionLogSegment segment) {
        Objects.requireNonNull(segment, "segment cannot be null");
        final boolean[] duplicate = new boolean[1];
        segments.compute(segment.getSessionId(), (key, held) -> {
            final Map<SegmentId, SessionLogSegment> target = held != null ? held : new ConcurrentHashMap<>();
            duplicate[0] = target.putIfAbsent(segment.getId(), segment) != null;
            return target;
        });
        if (duplicate[0]) {
            throw new SessionLogSegmentStoreException("Segment " + segment.getId() + " already exists for session "
                    + segment.getSessionId() + "; segment ids are never reused");
        }
    }

    @Override
    public Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(id, "id cannot be null");
        final Map<SegmentId, SessionLogSegment> held = segments.get(sessionId);
        return held == null ? Optional.empty() : Optional.ofNullable(held.get(id));
    }

    @Override
    public List<SegmentInfo> list(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        final Map<SegmentId, SessionLogSegment> held = segments.get(sessionId);
        if (held == null) {
            return List.of();
        }
        final List<SegmentInfo> infos = new ArrayList<>(held.size());
        for (SessionLogSegment segment : held.values()) {
            infos.add(segment.info());
        }
        return List.copyOf(infos);
    }

    /**
     * Exact: only sessions holding a segment older than {@code createdBefore}, in ascending id order, each once per
     * pass. The cursor is the last id of the previous page.
     */
    @Override
    public SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit) {
        Objects.requireNonNull(createdBefore, "createdBefore cannot be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        final List<SessionId> matching = new ArrayList<>();
        for (Map.Entry<SessionId, Map<SegmentId, SessionLogSegment>> e : segments.entrySet()) {
            if (cursor != null && e.getKey().value().compareTo(cursor) <= 0) {
                continue;
            }
            for (SessionLogSegment segment : e.getValue().values()) {
                if (segment.getCreatedAt().isBefore(createdBefore)) {
                    matching.add(e.getKey());
                    break;
                }
            }
        }
        matching.sort(Comparator.comparing(SessionId::value));
        if (matching.size() <= limit) {
            return SegmentScanPage.of(matching, null);
        }
        final List<SessionId> page = matching.subList(0, limit);
        return SegmentScanPage.of(page, page.get(limit - 1).value());
    }

    @Override
    public void delete(SessionId sessionId, SegmentId id) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(id, "id cannot be null");
        segments.computeIfPresent(sessionId, (key, held) -> {
            held.remove(id);
            return held.isEmpty() ? null : held;
        });
    }

    @Override
    public void deleteAll(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        segments.remove(sessionId);
    }
}
