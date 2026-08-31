package at.aimon.core.agent.compact;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionNotHeldException;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;

/**
 * {@link CompactionFailureStore} backed by the durable session record, so every node serving a session breaks the same
 * circuit.
 *
 * <p>
 * The counter lives in {@code SessionRecord.compactionFailureCount} and is written through the two
 * {@link SessionRecordStore} primitives built for it — {@code incrementCompactionFailureCount} (a delta, because
 * consecutive failures may be observed by different nodes across the turns of one session, so no writer holds a copy
 * to restate) and {@code resetCompactionFailureCount}. Reads come from {@link SessionRecordStore#load(SessionId)}.
 *
 * <h2>Give it the fenced view</h2>
 *
 * <p>
 * Construct this over {@code sessionStore.records()}, not over the raw record store: the writes are then rejected
 * unless this node still holds the session's lease, which is the same guarantee every other write on a session's
 * record gets. Passing the raw store works and is not checked for, but it lets an evicted node keep writing.
 *
 * <h2>Two ways this degrades, both deliberate</h2>
 *
 * <p>
 * <b>No record.</b> A run without a session of its own — a subagent fork, a skill fork, a scheduled routine — is
 * identified by a fabricated {@link SessionId} that nothing provisioned, so the increment lands nowhere and reports
 * {@code 0}. Such a run never trips the shared breaker. That is the honest outcome: there is no session whose
 * compaction streak it belongs to. A deployment that wants those runs guarded per-process anyway hands the fork path
 * an {@link InMemoryCompactionFailureStore}.
 *
 * <p>
 * <b>Lease lost.</b> A fenced write from a node that no longer holds the session throws
 * {@link SessionNotHeldException}. That happens exactly when this node was evicted mid-turn — the turn is already
 * doomed, and the breaker must not be what reports it, because a failure to <em>record</em> a compaction failure is
 * not itself a compaction failure. The exception is swallowed with a log line and the operation reports "nothing
 * counted". Every other backend failure propagates: a record store that is broken should be visible, not silently
 * turned into an open circuit.
 *
 * <p>
 * Thread-safe if the supplied {@link SessionRecordStore} is, which the SPI requires.
 */
public final class SessionRecordCompactionFailureStore implements CompactionFailureStore {

    private static final Logger log = LoggerFactory.getLogger(SessionRecordCompactionFailureStore.class);

    private final SessionRecordStore records;

    /**
     * @param records
     *            the record store to count in — pass {@code sessionStore.records()} so writes are lease-fenced (must
     *            not be null)
     * @throws NullPointerException
     *             if {@code records} is null
     */
    public SessionRecordCompactionFailureStore(SessionRecordStore records) {
        this.records = Objects.requireNonNull(records, "records cannot be null");
    }

    @Override
    public int get(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return records.load(sessionId).map(SessionRecordView::getCompactionFailureCount).orElse(0);
    }

    @Override
    public int recordFailure(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        try {
            return records.incrementCompactionFailureCount(sessionId);
        } catch (SessionNotHeldException e) {
            log.debug("Not counting a compaction failure for session {}: this node no longer holds it ({})",
                    sessionId.value(), e.getMessage());
            return 0;
        }
    }

    @Override
    public void reset(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        try {
            records.resetCompactionFailureCount(sessionId);
        } catch (SessionNotHeldException e) {
            log.debug("Not clearing the compaction failure count for session {}: this node no longer holds it ({})",
                    sessionId.value(), e.getMessage());
        }
    }

    @Override
    public String toString() {
        return "SessionRecordCompactionFailureStore{records=" + records.getClass().getSimpleName() + "}";
    }
}
