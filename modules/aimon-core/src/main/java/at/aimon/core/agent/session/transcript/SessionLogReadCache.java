package at.aimon.core.agent.session.transcript;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SegmentId;

/**
 * Remembers the segments one caller already loaded through a {@link SessionLogReader}, so reading a long log in many
 * windows or pages fetches, hash-checks and decodes each sealed segment once rather than once per window.
 *
 * <p>
 * <b>Call-scoped.</b> Create one per operation — one tool call, one ingest pass — and drop it afterwards. It holds
 * decoded entries, which a segment never changes once written (ids are never reused), but a manifest does: a cache kept
 * across operations would keep segments alive that garbage collection has since removed. A segment that could not be
 * read is remembered as unreadable too, so the reader's WARN about it is logged once per operation.
 *
 * <p>
 * Not thread-safe.
 */
public final class SessionLogReadCache {

    /**
     * Keyed by session, then segment: a segment id is scoped by its session, so the same id in two sessions is two
     * segments, and a cache handed reads of two sessions must not answer one with the other's entries.
     */
    private final Map<SessionId, Map<SegmentId, Optional<List<SessionLogEntry>>>> loaded = new HashMap<>();

    private SessionLogReadCache() {
    }

    /**
     * @return an empty cache (never null)
     */
    public static SessionLogReadCache create() {
        return new SessionLogReadCache();
    }

    /**
     * @return how many segments this cache has seen, readable or not
     */
    public int size() {
        int size = 0;
        for (Map<SegmentId, Optional<List<SessionLogEntry>>> segments : loaded.values()) {
            size += segments.size();
        }
        return size;
    }

    /**
     * Three answers: {@code null} when the segment was not loaded yet, an empty {@code Optional} when it was and could
     * not be read, and its entries otherwise.
     */
    Optional<List<SessionLogEntry>> get(SessionId sessionId, SegmentId id) {
        final Map<SegmentId, Optional<List<SessionLogEntry>>> segments = loaded.get(sessionId);
        return segments == null ? null : segments.get(id);
    }

    void put(SessionId sessionId, SegmentId id, List<SessionLogEntry> entries) {
        loaded.computeIfAbsent(sessionId, key -> new HashMap<>()).put(id, Optional.ofNullable(entries));
    }
}
