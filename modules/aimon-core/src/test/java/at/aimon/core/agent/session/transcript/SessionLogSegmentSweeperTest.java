package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.core.agent.session.store.SessionRecordView;

/**
 * The store-wide orphan sweep (session-log §11): it deletes what no record names once the grace has passed, and
 * nothing else.
 */
class SessionLogSegmentSweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final Duration GRACE = Duration.ofHours(24);

    private final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
    private final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private SessionLogSegmentSweeper sweeper() {
        return SessionLogSegmentSweeper.builder(segments, records).grace(GRACE).pageSize(1).clock(clock).build();
    }

    /** Saves a turn whose first three entries were summarized, so the record names one sealed segment. */
    private SegmentId sealedSession(SessionId session) {
        final DefaultTranscriptManager manager = new DefaultTranscriptManager(records,
                SessionCheckpointMailbox.disabled(), SessionLogFormat.V2,
                SessionLogStorage.builder(segments).minSealTokens(0).clock(clock).build());
        final TranscriptBuffer buffer = manager.initialize(session, "system");
        buffer.addUserMessage("q1");
        buffer.addAssistantMessage("a1");
        buffer.addUserMessage("q2");
        buffer.addAssistantMessage("a2");
        buffer.summarizeView(
                SummarySpan.builder().fromSeq(0).toSeq(3).summaryText("s").boundaryId("b").trigger("AUTO").build());
        manager.saveSilently(buffer);
        return records.load(session).orElseThrow().getLogState().getManifest().get(0).getSegmentId();
    }

    private SegmentId orphan(SessionId session, Instant createdAt) {
        final SegmentId id = SegmentId.generate();
        segments.put(SessionLogSegment.builder().sessionId(session).id(id).fromSeq(0).toSeq(1).entryCount(1)
                .payload("[]").createdAt(createdAt).build());
        return id;
    }

    @Test
    @DisplayName("an unnamed segment past the grace goes; the one the record names stays")
    void deletesOnlyUnnamedOldSegments() {
        final SessionId session = SessionId.of("sweep-named");
        final SegmentId named = sealedSession(session);
        final SegmentId orphan = orphan(session, NOW);
        now.set(NOW.plus(GRACE).plusSeconds(1));

        assertThat(sweeper().sweep()).isEqualTo(1);

        assertThat(segments.list(session)).extracting(SegmentInfo::getId).containsExactly(named);
        assertThat(segments.get(session, orphan)).isEmpty();
    }

    @Test
    @DisplayName("an unnamed segment inside the grace is kept — its sealing may not have been saved yet")
    void keepsYoungOrphans() {
        final SessionId session = SessionId.of("sweep-young");
        final SegmentId old = orphan(session, NOW.minus(GRACE).minusSeconds(1));
        final SegmentId young = orphan(session, NOW.minus(GRACE).plusSeconds(60));

        assertThat(sweeper().sweep()).isEqualTo(1);

        assertThat(segments.list(session)).extracting(SegmentInfo::getId).containsExactly(young);
        assertThat(segments.get(session, old)).isEmpty();
    }

    @Test
    @DisplayName("the segments of a deleted record go once past the grace")
    void sweepsSessionsWithNoRecord() {
        final SessionId session = SessionId.of("sweep-deleted");
        sealedSession(session);
        records.delete(session);
        now.set(NOW.plus(GRACE).plusSeconds(1));

        assertThat(sweeper().sweep()).isEqualTo(1);

        assertThat(segments.list(session)).isEmpty();
    }

    @Test
    @DisplayName("a record that cannot be read keeps its session's segments; other sessions are still swept")
    void unreadableRecordIsSkipped() {
        final SessionId unreadable = SessionId.of("sweep-a-unreadable");
        final SessionId readable = SessionId.of("sweep-b-readable");
        orphan(unreadable, NOW.minus(GRACE).minusSeconds(1));
        orphan(readable, NOW.minus(GRACE).minusSeconds(1));
        final InMemorySessionRecordStore failing = new InMemorySessionRecordStore() {
            @Override
            public Optional<SessionRecordView> load(SessionId sessionId) {
                if (sessionId.equals(unreadable)) {
                    throw new IllegalStateException("Inconsistent session log");
                }
                return super.load(sessionId);
            }
        };

        final int deleted = SessionLogSegmentSweeper.builder(segments, failing).grace(GRACE).clock(clock).build()
                .sweep();

        assertThat(deleted).isEqualTo(1);
        assertThat(segments.list(unreadable)).hasSize(1);
        assertThat(segments.list(readable)).isEmpty();
    }

    @Test
    @DisplayName("pages through every session, one per page")
    void pagesThroughTheStore() {
        for (int i = 0; i < 5; i++) {
            orphan(SessionId.of("sweep-page-" + i), NOW.minus(GRACE).minusSeconds(1));
        }

        assertThat(sweeper().sweep()).isEqualTo(5);

        for (int i = 0; i < 5; i++) {
            assertThat(segments.list(SessionId.of("sweep-page-" + i))).isEmpty();
        }
    }

    @Test
    @DisplayName("a failing scan ends the pass without throwing; a failing delete skips that segment")
    void storageFailuresAreNotThrown() {
        orphan(SessionId.of("sweep-broken"), NOW.minus(GRACE).minusSeconds(1));
        final SessionLogSegmentSweeper scanFails = SessionLogSegmentSweeper
                .builder(new FailingSegments(segments, true, false), records).grace(GRACE).clock(clock).build();
        final SessionLogSegmentSweeper deleteFails = SessionLogSegmentSweeper
                .builder(new FailingSegments(segments, false, true), records).grace(GRACE).clock(clock).build();

        assertThat(scanFails.sweep()).isZero();
        assertThat(deleteFails.sweep()).isZero();
        assertThat(segments.list(SessionId.of("sweep-broken"))).hasSize(1);
    }

    @Test
    @DisplayName("a zero grace or page size is refused")
    void rejectsNonPositiveSettings() {
        assertThatThrownBy(() -> SessionLogSegmentSweeper.builder(segments, records).grace(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionLogSegmentSweeper.builder(segments, records).pageSize(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Delegates to a real store, failing the scan or the deletes on request. */
    private static final class FailingSegments extends ForwardingSegments {
        private final boolean failScan;
        private final boolean failDelete;

        private FailingSegments(InMemorySessionLogSegmentStore delegate, boolean failScan, boolean failDelete) {
            super(delegate);
            this.failScan = failScan;
            this.failDelete = failDelete;
        }

        @Override
        public SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit) {
            if (failScan) {
                throw new SessionLogSegmentStoreException("scan down");
            }
            return super.scanSessions(createdBefore, cursor, limit);
        }

        @Override
        public void delete(SessionId sessionId, SegmentId id) {
            if (failDelete) {
                throw new SessionLogSegmentStoreException("delete down");
            }
            super.delete(sessionId, id);
        }
    }

    private static class ForwardingSegments implements SessionLogSegmentStore {
        private final InMemorySessionLogSegmentStore delegate;

        ForwardingSegments(InMemorySessionLogSegmentStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(SessionLogSegment segment) {
            delegate.put(segment);
        }

        @Override
        public Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id) {
            return delegate.get(sessionId, id);
        }

        @Override
        public List<SegmentInfo> list(SessionId sessionId) {
            return delegate.list(sessionId);
        }

        @Override
        public SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit) {
            return delegate.scanSessions(createdBefore, cursor, limit);
        }

        @Override
        public void delete(SessionId sessionId, SegmentId id) {
            delegate.delete(sessionId, id);
        }

        @Override
        public void deleteAll(SessionId sessionId) {
            delegate.deleteAll(sessionId);
        }
    }
}
