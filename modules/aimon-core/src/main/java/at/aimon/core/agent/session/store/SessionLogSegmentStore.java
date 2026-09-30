package at.aimon.core.agent.session.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;

/**
 * Where the sealed ranges of session logs live, outside the records (session-log §5.6).
 *
 * <p>
 * <b>The manifest decides what exists.</b> A segment here is valid only while the record's manifest points at it by
 * id. Storing one does not make it part of any session, and finding one does not make it readable: a reader follows
 * the manifest and nothing else, and a segment no manifest points at is an orphan that garbage collection deletes.
 * That is also why writes are not fenced — a late {@link #put} from a node that lost its lease writes a segment the new
 * holder's manifest never names, which is harmless.
 *
 * <p>
 * <b>Deletes are.</b> Hand {@link #delete} and {@link #deleteAll} only the view {@link SessionStore#segments} returns
 * when a {@link SessionStore} exists, so a node that no longer holds a session cannot delete segments the new holder's
 * record still points at. An assembly with no lease (a single node, the CLI) deletes through the store itself.
 *
 * <p>
 * <b>Contract for implementations.</b>
 *
 * <ul>
 * <li><b>Read-after-write.</b> A {@link #get} that follows a successful {@link #put} on any node returns the segment.
 * The record that names it is written right after, and a reader following that record must find it.
 * <li><b>New ids only.</b> Every {@code put} carries a freshly minted {@link SegmentId}. A backend may reject a
 * duplicate id; it must never overwrite one.
 * <li><b>Scoped by session.</b> {@link #get}, {@link #list} and {@link #delete} see only the given session's segments,
 * even if an id were shared.
 * <li><b>Same protection as the record.</b> A segment is data moved out of the record, so it must be stored at least as
 * safely as the record is.
 * <li><b>Failures</b> surface as {@link SessionLogSegmentStoreException}; absence is never a failure — an empty
 * {@code Optional}, an empty list, a no-op delete.
 * </ul>
 *
 * <p>
 * Application-scoped, like {@link SessionRecordStore}: the store outlives every session it holds segments for, and a
 * segment's lifetime is its session's.
 */
public interface SessionLogSegmentStore {

    /**
     * Stores a new segment. Not fenced — see the class javadoc for why that is safe.
     *
     * @param segment
     *            the segment (must not be null; its id must be new)
     * @throws SessionLogSegmentStoreException
     *             if the backend fails, or rejects a duplicate id
     */
    void put(SessionLogSegment segment);

    /**
     * Reads one segment of a session.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param id
     *            the segment id (must not be null)
     * @return the segment, or empty when that session has no segment with that id (never null)
     * @throws SessionLogSegmentStoreException
     *             if the backend fails
     */
    Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id);

    /**
     * Lists the segments stored for a session — id and creation time only, for garbage collection.
     *
     * @param sessionId
     *            the session (must not be null)
     * @return the segments, in no particular order (never null, may be empty)
     * @throws SessionLogSegmentStoreException
     *             if the backend fails
     */
    List<SegmentInfo> list(SessionId sessionId);

    /**
     * Pages through the sessions that hold segments, for the store-wide orphan sweep (session-log §11).
     *
     * <p>
     * Over a full pass — from a null cursor until {@link SegmentScanPage#getNextCursor()} is empty — every session that
     * held a segment created before {@code createdBefore} when the pass started, and still holds it, appears at least
     * once. The answer is allowed to be looser than that, because the sweeper checks each session itself: a backend may
     * also return sessions whose segments are all newer, may return a session on more than one page, and may treat
     * {@code limit} as a hint rather than a bound (Redis {@code SCAN} does all three). What it may not do is skip a
     * qualifying session, which would leave its orphans for good.
     *
     * <p>
     * Not fenced: it is a read, and the sweep deletes nothing a record's manifest names.
     *
     * @param createdBefore
     *            only sessions with a segment older than this need to be reported (must not be null)
     * @param cursor
     *            where to continue, as returned by the previous page; null to start a pass
     * @param limit
     *            how many sessions a page should hold (must be positive; a hint for some backends)
     * @return the page (never null)
     * @throws SessionLogSegmentStoreException
     *             if the backend fails
     */
    SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit);

    /**
     * Deletes one segment of a session. Call it only through the fenced view when a {@link SessionStore} exists.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param id
     *            the segment id (must not be null)
     * @throws SessionLogSegmentStoreException
     *             if the backend fails
     */
    void delete(SessionId sessionId, SegmentId id);

    /**
     * Deletes every segment of a session — the session itself is being deleted.
     *
     * @param sessionId
     *            the session (must not be null)
     * @throws SessionLogSegmentStoreException
     *             if the backend fails
     */
    void deleteAll(SessionId sessionId);
}
