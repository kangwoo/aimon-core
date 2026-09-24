package at.aimon.core.agent.session.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;

/**
 * One page of {@link SessionLogSegmentStore#scanSessions}: the sessions found, and where the next page starts.
 *
 * <p>
 * The cursor is opaque — a session id in one backend, a Redis {@code SCAN} cursor in another — and only the store
 * that issued it can read it. An empty {@link #getNextCursor()} means the pass is complete. A page may be empty while
 * the pass is not (a {@code SCAN} step can match nothing), so a caller loops on the cursor, never on the page size.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class SegmentScanPage {

    private static final SegmentScanPage LAST_EMPTY = new SegmentScanPage(List.of(), null);

    private final List<SessionId> sessionIds;
    private final String nextCursor;

    private SegmentScanPage(List<SessionId> sessionIds, String nextCursor) {
        this.sessionIds = List.copyOf(Objects.requireNonNull(sessionIds, "sessionIds cannot be null"));
        this.nextCursor = nextCursor;
    }

    /**
     * @param sessionIds
     *            the sessions on this page (must not be null or contain null)
     * @param nextCursor
     *            where the next page starts, or null when this is the last page
     * @return the page (never null)
     */
    public static SegmentScanPage of(List<SessionId> sessionIds, String nextCursor) {
        return new SegmentScanPage(sessionIds, nextCursor);
    }

    /**
     * @return a last page with no sessions — the pass over an empty store (never null)
     */
    public static SegmentScanPage empty() {
        return LAST_EMPTY;
    }

    /**
     * @return the sessions on this page; may repeat a session from an earlier page (never null)
     */
    public List<SessionId> getSessionIds() {
        return sessionIds;
    }

    /**
     * @return where the next page starts, or empty when the pass is complete (never null)
     */
    public Optional<String> getNextCursor() {
        return Optional.ofNullable(nextCursor);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof SegmentScanPage other && sessionIds.equals(other.sessionIds)
                && Objects.equals(nextCursor, other.nextCursor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionIds, nextCursor);
    }

    @Override
    public String toString() {
        return "SegmentScanPage{sessionIds=" + sessionIds + ", nextCursor=" + nextCursor + "}";
    }
}
