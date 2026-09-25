package at.aimon.core.agent.session.transcript;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionNotHeldException;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;

/**
 * Deletes a session's orphan segments (session-log §5.4).
 *
 * <p>
 * A segment is deleted only when all three hold:
 *
 * <ol>
 * <li><b>the caller owns the session</b> and is not running a turn of it — this is called on the thread that just
 * saved the turn, after the save; the delete goes through {@link SessionLogStorage#getDeleteStore()}, the fenced view
 * when the assembly has one;
 * <li><b>the saved record's manifest does not name it</b> — a segment a mid-turn sealing wrote is named by the time the
 * turn-end save has run, so it is never collected here;
 * <li><b>it is older than the grace period</b> — which protects the segment of a sealing still in progress on a node
 * that lost its lease without knowing it.
 * </ol>
 *
 * <p>
 * Never throws for a storage failure: a segment left behind is collected at the next opportunity.
 *
 * <p>
 * Stateless apart from its configuration; thread-safe.
 */
public final class SessionLogGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(SessionLogGarbageCollector.class);

    private final SessionLogStorage storage;

    /**
     * @param storage
     *            where the segments are and how long orphans are kept (must not be null)
     */
    public SessionLogGarbageCollector(SessionLogStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
    }

    /**
     * Deletes the orphans of {@code sessionId} that are past the grace period.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param saved
     *            the log as the record was just saved (must not be null)
     * @return how many segments were deleted
     */
    public int collect(SessionId sessionId, SessionLogState saved) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(saved, "saved cannot be null");
        final Set<SegmentId> named = new HashSet<>();
        for (SessionLogManifestEntry line : saved.getManifest()) {
            named.add(line.getSegmentId());
        }
        final List<SegmentInfo> stored;
        try {
            stored = storage.getSegmentStore().list(sessionId);
        } catch (RuntimeException e) {
            log.warn("Listing the segments of session {} failed; orphans are kept for now: {}", sessionId.value(),
                    e.toString());
            return 0;
        }
        final Instant cutoff = storage.getClock().instant().minus(storage.getSegmentGcGrace());
        int deleted = 0;
        for (SegmentInfo info : stored) {
            if (named.contains(info.getId()) || !info.getCreatedAt().isBefore(cutoff)) {
                continue;
            }
            try {
                storage.getDeleteStore().delete(sessionId, info.getId());
                deleted++;
            } catch (SessionNotHeldException e) {
                // The lease fence is doing its job — this node no longer holds the session, typically a save that ran
                // after the lease was returned. Not a fault, so not a warning; the holder collects its own orphans.
                log.debug("Orphan segment {} of session {} left to its holder: {}", info.getId(), sessionId.value(),
                        e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Deleting orphan segment {} of session {} failed: {}", info.getId(), sessionId.value(),
                        e.toString());
            }
        }
        if (deleted > 0) {
            log.debug("Deleted {} orphan segment(s) of session {}", deleted, sessionId.value());
        }
        return deleted;
    }

    /**
     * Deletes segments a {@code /clear} cut loose, right after the cleared record was saved. No grace period: the
     * saved record no longer names them, and they are this node's own (session-log §6.2).
     *
     * @param sessionId
     *            the session (must not be null)
     * @param ids
     *            the segments (must not be null)
     * @return the ids that were dealt with — deleted, or failed and left for {@link #collect} (never null)
     */
    public List<SegmentId> deleteCleared(SessionId sessionId, List<SegmentId> ids) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(ids, "ids cannot be null");
        for (SegmentId id : ids) {
            try {
                storage.getDeleteStore().delete(sessionId, id);
            } catch (SessionNotHeldException e) {
                log.debug("Cleared segment {} of session {} left to its holder: {}", id, sessionId.value(),
                        e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Deleting cleared segment {} of session {} failed; garbage collection will retry: {}", id,
                        sessionId.value(), e.toString());
            }
        }
        return List.copyOf(ids);
    }
}
