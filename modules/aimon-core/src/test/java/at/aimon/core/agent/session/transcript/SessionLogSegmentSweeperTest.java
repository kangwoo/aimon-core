package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;
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

    /**
     * The ordering rule the sweeper's safety rests on: the record is read <em>after</em> the segments are listed, so a
     * segment the record names by then is kept even if nothing named it when the pass began. The fake makes the record
     * name the segment as a side effect of the listing — the shape of a holder saving the turn that sealed it while the
     * sweep is running.
     */
    @Test
    @DisplayName("a segment named by the time the record is read is kept, even though the listing came first")
    void readsTheRecordAfterListing() {
        final SessionId session = SessionId.of("sweep-named-mid-pass");
        final SegmentId sealed = sealedSession(session);
        final SessionSnapshot naming = SessionSnapshot.from(records.load(session).orElseThrow());
        records.delete(session);
        now.set(NOW.plus(GRACE).plusSeconds(1));
        final ForwardingSegments savingDuringTheListing = new ForwardingSegments(segments) {
            @Override
            public List<SegmentInfo> list(SessionId sessionId) {
                final List<SegmentInfo> listed = super.list(sessionId);
                if (sessionId.equals(session)) {
                    records.mergeFromSnapshot(naming);
                }
                return listed;
            }
        };

        final int deleted = SessionLogSegmentSweeper.builder(savingDuringTheListing, records).grace(GRACE).clock(clock)
                .build().sweep();

        assertThat(deleted).isZero();
        assertThat(segments.get(session, sealed)).isPresent();
    }

    @Test
    @DisplayName("coordinated: one node sweeps per lease period; the others skip until the lease lapses")
    void coordinatedSweepRunsOnOneNode() {
        final InMemorySessionLeaseStore leases = new InMemorySessionLeaseStore(clock);
        final SessionLogSegmentSweeper nodeA = coordinated(leases, "node-a", segments);
        final SessionLogSegmentSweeper nodeB = coordinated(leases, "node-b", segments);
        orphan(SessionId.of("coord-1"), NOW.minus(GRACE).minusSeconds(1));

        assertThat(nodeA.isCoordinated()).isTrue();
        assertThat(nodeA.sweepIfClaimed()).hasValue(1);
        assertThat(leases.findHolder(SessionLogSegmentSweeper.SWEEP_LEASE_ID)).get()
                .extracting(LeaseHolder::getHolderId).isEqualTo("node-a");

        orphan(SessionId.of("coord-2"), NOW.minus(GRACE).minusSeconds(1));
        assertThat(nodeB.sweepIfClaimed()).as("not released after the pass: node-b skips").isEmpty();
        assertThat(segments.list(SessionId.of("coord-2"))).hasSize(1);

        // node-a dies here: nothing renews its lease. Once it lapses, the next node to tick takes over.
        now.set(now.get().plus(Duration.ofHours(1)));
        assertThat(nodeB.sweepIfClaimed()).hasValue(1);
        assertThat(segments.list(SessionId.of("coord-2"))).isEmpty();
    }

    @Test
    @DisplayName("coordinated: the holder's consecutive intervals sweep, whichever of its tick and its lease's expiry"
            + " comes first")
    void holderSweepsEveryInterval() {
        final InMemorySessionLeaseStore leases = new InMemorySessionLeaseStore(clock);
        final SessionLogSegmentSweeper nodeA = coordinated(leases, "node-a", segments);
        final SessionLogSegmentSweeper nodeB = coordinated(leases, "node-b", segments);

        for (int interval = 0; interval < 4; interval++) {
            orphan(SessionId.of("tick-" + interval), now.get().minus(GRACE).minusSeconds(1));
            assertThat(nodeA.sweepIfClaimed()).as("interval %d on the holder", interval).hasValue(1);
            assertThat(nodeB.sweepIfClaimed()).as("interval %d on the other node", interval).isEmpty();
            // Alternate a tick that fires a moment before the lease the last pass renewed expires (the store would
            // refuse a re-acquire) with one that fires a moment after it.
            now.set(now.get().plus(Duration.ofHours(1)).plusMillis(interval % 2 == 0 ? -1 : 1));
        }
    }

    @Test
    @DisplayName("coordinated: the in-memory store keeps a lapsed lease, so the holder extends it — same fencing token —"
            + " and a takeover makes that extend fail")
    void holderExtendsItsLapsedLeaseOnTheInMemoryStore() {
        final InMemorySessionLeaseStore leases = new InMemorySessionLeaseStore(clock);
        final SessionLogSegmentSweeper nodeA = coordinated(leases, "node-a", segments);
        final SessionLogSegmentSweeper nodeB = coordinated(leases, "node-b", segments);

        assertThat(nodeA.sweepIfClaimed()).isPresent();
        final long first = leases.findHolder(SessionLogSegmentSweeper.SWEEP_LEASE_ID).orElseThrow().getFencingToken();
        assertThat(nodeA.sweepIfClaimed()).as("second pass inside the lease").isPresent();

        now.set(now.get().plus(Duration.ofHours(1)).plusMillis(1));
        assertThat(nodeA.sweepIfClaimed()).as("pass after the lease lapsed").isPresent();
        assertThat(leases.findHolder(SessionLogSegmentSweeper.SWEEP_LEASE_ID)).get()
                .extracting(LeaseHolder::getFencingToken).as("extended, not reacquired").isEqualTo(first);
        assertThat(nodeB.sweepIfClaimed()).isEmpty();

        now.set(now.get().plus(Duration.ofHours(2)));
        assertThat(nodeB.sweepIfClaimed()).as("node-a stopped ticking; node-b takes over").isPresent();
        assertThat(nodeA.sweepIfClaimed()).as("the old holder").isEmpty();
        assertThat(leases.findHolder(SessionLogSegmentSweeper.SWEEP_LEASE_ID)).get()
                .extracting(LeaseHolder::getHolderId).isEqualTo("node-b");
    }

    @Test
    @DisplayName("coordinated: a holder whose backend forgets a lapsed lease acquires again; one taken over skips")
    void holderReacquiresOrYieldsWhenItsExtendFails() {
        final InMemorySessionLeaseStore inner = new InMemorySessionLeaseStore(clock);
        // Redis-shaped: an extend of a lapsed lease fails because the key is gone.
        final SessionLeaseStore forgetful = new SessionLeaseStore() {
            @Override
            public Optional<SessionLease> tryAcquire(SessionId id, String holderId, Duration lease) {
                return inner.tryAcquire(id, holderId, lease);
            }

            @Override
            public Optional<LeaseHolder> findHolder(SessionId id) {
                return inner.findHolder(id);
            }

            @Override
            public boolean extend(SessionLease lease, Duration duration) {
                return inner.findHolder(lease.getSessionId()).isPresent() && inner.extend(lease, duration);
            }

            @Override
            public void release(SessionLease lease) {
                inner.release(lease);
            }
        };
        final SessionLogSegmentSweeper nodeA = coordinated(forgetful, "node-a", segments);
        final SessionLogSegmentSweeper nodeB = coordinated(forgetful, "node-b", segments);
        orphan(SessionId.of("forget-1"), NOW.minus(GRACE).minusSeconds(1));
        assertThat(nodeA.sweepIfClaimed()).hasValue(1);

        now.set(now.get().plus(Duration.ofHours(1)).plusMillis(1));
        orphan(SessionId.of("forget-2"), NOW.minus(GRACE).minusSeconds(1));
        assertThat(nodeA.sweepIfClaimed()).as("lapsed and forgotten: node-a acquires afresh").hasValue(1);

        now.set(now.get().plus(Duration.ofHours(2)));
        orphan(SessionId.of("forget-3"), NOW.minus(GRACE).minusSeconds(1));
        assertThat(nodeB.sweepIfClaimed()).as("node-a stopped ticking; node-b takes over").hasValue(1);
        assertThat(nodeA.sweepIfClaimed()).as("taken over: node-a's extend and acquire both fail").isEmpty();
        assertThat(inner.findHolder(SessionLogSegmentSweeper.SWEEP_LEASE_ID)).get().extracting(LeaseHolder::getHolderId)
                .isEqualTo("node-b");
    }

    @Test
    @DisplayName("coordinated: a pass longer than the lease renews it page by page, so nobody starts a second one")
    void coordinatedSweepRenewsDuringALongPass() {
        final InMemorySessionLeaseStore leases = new InMemorySessionLeaseStore(clock);
        for (int i = 0; i < 3; i++) {
            orphan(SessionId.of("coord-long-" + i), NOW.minus(GRACE).minusSeconds(1));
        }
        final ForwardingSegments slowPages = new ForwardingSegments(segments) {
            @Override
            public SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit) {
                now.set(now.get().plus(Duration.ofMinutes(40)));
                return super.scanSessions(createdBefore, cursor, limit);
            }
        };

        assertThat(coordinated(leases, "node-a", slowPages).sweepIfClaimed()).hasValue(3);

        assertThat(coordinated(leases, "node-b", segments).sweepIfClaimed())
                .as("the lease was renewed after the last page, two hours into a one-hour lease").isEmpty();
    }

    @Test
    @DisplayName("coordinated: a lease store that cannot be reached skips the pass; uncoordinated always runs")
    void leaseStoreFailureSkipsThePass() {
        orphan(SessionId.of("coord-down"), NOW.minus(GRACE).minusSeconds(1));
        final SessionLeaseStore down = mock(SessionLeaseStore.class);
        when(down.tryAcquire(any(), any(), any())).thenThrow(new IllegalStateException("lease store down"));

        assertThat(coordinated(down, "node-a", segments).sweepIfClaimed()).isEmpty();
        assertThat(segments.list(SessionId.of("coord-down"))).hasSize(1);
        assertThat(sweeper().isCoordinated()).isFalse();
        assertThat(sweeper().sweepIfClaimed()).hasValue(1);
        assertThatThrownBy(() -> SessionLogSegmentSweeper.builder(segments, records)
                .coordination(down, "node-a", Duration.ZERO).build()).isInstanceOf(IllegalArgumentException.class);
    }

    private SessionLogSegmentSweeper coordinated(SessionLeaseStore leases, String holder,
            SessionLogSegmentStore store) {
        return SessionLogSegmentSweeper.builder(store, records).grace(GRACE).pageSize(1).clock(clock)
                .coordination(leases, holder, Duration.ofHours(1)).build();
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
        private final SessionLogSegmentStore delegate;

        ForwardingSegments(SessionLogSegmentStore delegate) {
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
