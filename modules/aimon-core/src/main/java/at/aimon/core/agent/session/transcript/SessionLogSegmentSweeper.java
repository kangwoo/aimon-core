package at.aimon.core.agent.session.transcript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;

/**
 * Deletes orphan segments store-wide, for the sessions per-session garbage collection never reaches again
 * (session-log §11).
 *
 * <p>
 * {@link SessionLogGarbageCollector} runs on the node that just saved a turn, so it only ever sees sessions that are
 * still in use. The orphans it cannot reach are those of sessions nobody resumes: segments a rewind cut, cleared
 * segments whose delete failed, a sealing whose record was never saved, and every segment of a record that was deleted
 * while its segment delete failed. This sweeper walks the whole store instead.
 *
 * <h2>What it deletes</h2>
 *
 * <p>
 * For each session {@link SessionLogSegmentStore#scanSessions} reports, a segment is deleted only when both hold:
 *
 * <ol>
 * <li><b>it is older than the grace period</b> — the grace covers the time between a sealing writing a segment and
 * the record write that names it, on whichever node is doing that;
 * <li><b>the session's record, read now, does not name it</b> — or there is no record at all.
 * </ol>
 *
 * <p>
 * It deletes nothing it is unsure of: when the record cannot be read (a backend failure, or a document the codec
 * rejects), the session is skipped for this pass.
 *
 * <h2>Why it is not fenced, and why several nodes may run it</h2>
 *
 * <p>
 * The sweeper holds no session, so a fenced delete would refuse everything; it deletes through the raw store. What
 * makes that safe is the same pair that protects per-session collection from a peer's in-progress sealing — the
 * manifest check and the grace period — with the record read <em>after</em> the segments are listed, so a segment
 * named by the time the record is read is kept. Running it on every node of a cluster at once is therefore safe; the
 * nodes merely repeat each other's work, and a delete of a segment already gone is a no-op. The grace must exceed the
 * longest a sealed segment can wait for the record write that names it — a turn-end save or a checkpoint — which is why
 * the default is a day rather than the hour per-session collection uses.
 *
 * <p>
 * Application-scoped and thread-safe; {@link #sweep()} is one full pass and holds no state between passes.
 */
public final class SessionLogSegmentSweeper {

    /** The default grace period: long enough that no sealing in progress anywhere can still be waiting on its save. */
    public static final Duration DEFAULT_GRACE = Duration.ofHours(24);

    /** The default number of sessions asked for per page of the scan. */
    public static final int DEFAULT_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(SessionLogSegmentSweeper.class);

    private final SessionLogSegmentStore segmentStore;
    private final SessionRecordStore recordStore;
    private final Duration grace;
    private final int pageSize;
    private final Clock clock;

    private SessionLogSegmentSweeper(Builder builder) {
        this.segmentStore = Objects.requireNonNull(builder.segmentStore, "segmentStore cannot be null");
        this.recordStore = Objects.requireNonNull(builder.recordStore, "recordStore cannot be null");
        this.grace = Objects.requireNonNull(builder.grace, "grace cannot be null");
        this.clock = Objects.requireNonNull(builder.clock, "clock cannot be null");
        this.pageSize = builder.pageSize;
        if (grace.isZero() || grace.isNegative()) {
            throw new IllegalArgumentException("grace must be positive: a zero grace deletes the segment of a sealing"
                    + " whose record has not been written yet, got " + grace);
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive, got " + pageSize);
        }
    }

    /**
     * @param segmentStore
     *            the raw segment store to sweep (must not be null)
     * @param recordStore
     *            the record store whose manifests say which segments are live (must not be null)
     * @return a builder (never null)
     */
    public static Builder builder(SessionLogSegmentStore segmentStore, SessionRecordStore recordStore) {
        return new Builder().segmentStore(segmentStore).recordStore(recordStore);
    }

    /**
     * @return the grace period (never null)
     */
    public Duration getGrace() {
        return grace;
    }

    /**
     * Runs one full pass over the store. Never throws for a storage failure: a failed scan ends the pass early, and a
     * failed read or delete skips that session or segment until the next pass.
     *
     * @return how many segments were deleted
     */
    public int sweep() {
        final Instant cutoff = clock.instant().minus(grace);
        int deleted = 0;
        String cursor = null;
        do {
            final SegmentScanPage page;
            try {
                page = segmentStore.scanSessions(cutoff, cursor, pageSize);
            } catch (RuntimeException e) {
                log.warn("Scanning the segment store failed; the sweep stops here and resumes next pass: {}",
                        e.toString());
                break;
            }
            for (SessionId sessionId : page.getSessionIds()) {
                deleted += sweepSession(sessionId, cutoff);
            }
            cursor = page.getNextCursor().orElse(null);
        } while (cursor != null);
        if (deleted > 0) {
            log.info("Segment sweep deleted {} orphan segment(s)", deleted);
        }
        return deleted;
    }

    private int sweepSession(SessionId sessionId, Instant cutoff) {
        final List<SegmentInfo> stored;
        try {
            stored = segmentStore.list(sessionId);
        } catch (RuntimeException e) {
            log.warn("Listing the segments of session {} failed; skipped this pass: {}", sessionId.value(),
                    e.toString());
            return 0;
        }
        boolean anyOld = false;
        for (SegmentInfo info : stored) {
            anyOld |= info.getCreatedAt().isBefore(cutoff);
        }
        if (!anyOld) {
            return 0;
        }
        // Read after listing: a segment named by the time the record is read is kept, whatever the listing saw.
        final Set<SegmentId> named;
        try {
            named = namedSegments(sessionId);
        } catch (RuntimeException e) {
            log.warn("Reading the record of session {} failed; its segments are kept this pass: {}", sessionId.value(),
                    e.toString());
            return 0;
        }
        int deleted = 0;
        for (SegmentInfo info : stored) {
            if (named.contains(info.getId()) || !info.getCreatedAt().isBefore(cutoff)) {
                continue;
            }
            try {
                segmentStore.delete(sessionId, info.getId());
                deleted++;
            } catch (RuntimeException e) {
                log.warn("Deleting orphan segment {} of session {} failed; retried next pass: {}", info.getId(),
                        sessionId.value(), e.toString());
            }
        }
        if (deleted > 0) {
            log.debug("Swept {} orphan segment(s) of session {}", deleted, sessionId.value());
        }
        return deleted;
    }

    private Set<SegmentId> namedSegments(SessionId sessionId) {
        final Optional<SessionRecordView> record = recordStore.load(sessionId);
        final Set<SegmentId> named = new HashSet<>();
        if (record.isPresent()) {
            for (SessionLogManifestEntry line : record.get().getLogState().getManifest()) {
                named.add(line.getSegmentId());
            }
        }
        return named;
    }

    /**
     * Builder for {@link SessionLogSegmentSweeper}.
     */
    public static final class Builder {

        private SessionLogSegmentStore segmentStore;
        private SessionRecordStore recordStore;
        private Duration grace = DEFAULT_GRACE;
        private int pageSize = DEFAULT_PAGE_SIZE;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * @param segmentStore
         *            the raw segment store (must not be null)
         * @return this builder
         */
        public Builder segmentStore(SessionLogSegmentStore segmentStore) {
            this.segmentStore = segmentStore;
            return this;
        }

        /**
         * @param recordStore
         *            the record store (must not be null)
         * @return this builder
         */
        public Builder recordStore(SessionRecordStore recordStore) {
            this.recordStore = recordStore;
            return this;
        }

        /**
         * @param grace
         *            how old an unnamed segment must be before it is deleted (must be positive; default
         *            {@link #DEFAULT_GRACE})
         * @return this builder
         */
        public Builder grace(Duration grace) {
            this.grace = grace;
            return this;
        }

        /**
         * @param pageSize
         *            sessions asked for per scan page (must be positive; default {@link #DEFAULT_PAGE_SIZE})
         * @return this builder
         */
        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        /**
         * @param clock
         *            the clock the grace is measured on (must not be null)
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * @return the sweeper (never null)
         * @throws IllegalArgumentException
         *             if the grace or page size is not positive
         */
        public SessionLogSegmentSweeper build() {
            return new SessionLogSegmentSweeper(this);
        }
    }
}
