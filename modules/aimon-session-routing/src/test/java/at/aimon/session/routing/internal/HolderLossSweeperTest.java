package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.idempotency.PutResult;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.InterruptedAt;

/**
 * Behaviour of holder-loss recovery now that it is turn-scoped.
 *
 * <p>
 * The sweeper used to end the whole session on the losing node's behalf: complete the local event stream and
 * broadcast an {@code EVICT} so every peer dropped its cached session. A lost holder is not a lost session — the
 * lease expires and a successor claims it, possibly before the sweep even runs — so these tests pin the narrower
 * contract: report the dead attempt, leave the session alone.
 *
 * <p>
 * The store here is {@link InMemoryIdempotencyStore}, so the CAS assertions below are evidence about that
 * implementation only; Mongo / Postgres / Redis pin the same {@code compareAndReset} contract in their own
 * {@code @Tag("docker")} suites, which {@code ./gradlew test} excludes.
 */
@DisplayName("HolderLossSweeper reports the lost turn without tearing the conversation down")
class HolderLossSweeperTest {

    private static final SessionId CONV = SessionId.of("c-sweep");
    private static final Duration SECONDARY_TTL = Duration.ofSeconds(30);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("a stale reservation is announced by key and reported as an interrupt, nothing more")
    void staleReservationIsAnnounced() {
        plantStale("k-1", "dead-node", Duration.ofMinutes(1));
        final RecordingSink sink = new RecordingSink();
        final RecordingAnnouncer announcer = new RecordingAnnouncer();

        sweeper(sink, announcer).sweepOnce();

        assertThat(announcer.announcements).as("the caller waiting on the lost turn must be failed by key")
                .containsExactly("c-sweep|k-1|dead-node");
        // No assertion that the stream was left open, because there is no longer a way for the sweeper to close it:
        // EventSink offers emit and nothing else. That the session has not ended — only one attempt at it has — is
        // now a fact about the type it depends on, and the manager-level guard is streamStaysOpenAcrossHolderLoss.
        assertThat(sink.emitted).hasSize(1);
        assertThat(sink.emitted.get(0)).isInstanceOf(InterruptedAt.class);
        assertThat(((InterruptedAt) sink.emitted.get(0)).getReason()).isEqualTo(InterruptReason.HOLDER_LOST);
    }

    @Test
    @DisplayName("the reset is not repeated: a second pass finds nothing to announce")
    void resetEntryIsNotSweptTwice() {
        plantStale("k-2", "dead-node", Duration.ofMinutes(1));
        final RecordingAnnouncer announcer = new RecordingAnnouncer();
        final HolderLossSweeper sweeper = sweeper(new RecordingSink(), announcer);

        sweeper.sweepOnce();
        sweeper.sweepOnce();

        assertThat(announcer.announcements).hasSize(1);
        assertThat(store.findStaleInFlight(Instant.now())).as("a swept reservation must stop looking like a live turn")
                .isEmpty();
    }

    @Test
    @DisplayName("two sweepers that both saw the same reservation produce exactly one announcement")
    void compareAndResetPicksOneWinner() {
        plantStale("k-3", "dead-node", Duration.ofMinutes(1));
        final List<IdempotencyEntry> bothSaw = store.findStaleInFlight(Instant.now());
        final RecordingAnnouncer first = new RecordingAnnouncer();
        final RecordingAnnouncer second = new RecordingAnnouncer();

        // Both sweepers are fed the pre-reset scan, which is what makes the second one a loser of the CAS rather than
        // a sweeper that found an empty stale list. Two plain passes assert much less than they look like they do:
        // the second one never reaches compareAndReset, so it agrees with this expectation no matter what answer
        // compareAndReset would have given it.
        sweeper(new ScriptedScanStore(store, bothSaw), new RecordingSink(), first).sweepOnce();
        sweeper(new ScriptedScanStore(store, bothSaw), new RecordingSink(), second).sweepOnce();

        assertThat(first.announcements).hasSize(1);
        assertThat(second.announcements).as("only the compareAndReset winner announces").isEmpty();
        assertThat(store.find("k-3")).as("the winner's reset must have removed the reservation").isEmpty();
    }

    @Test
    @DisplayName("a late sweep does not reset the reservation a successor now holds")
    void lateSweepDoesNotResetASuccessorsReservation() {
        plantStale("k-9", "dead-node", Duration.ofMinutes(1));
        final List<IdempotencyEntry> scannedBeforeHandover = store.findStaleInFlight(Instant.now());
        final RecordingSink sink = new RecordingSink();
        final RecordingAnnouncer announcer = new RecordingAnnouncer();
        // The ABA that expectedHolderId exists to stop, forced deterministically onto one thread: between this
        // sweeper's scan and its compareAndReset, a peer wins the reset and the client's retry re-reserves the same
        // key under a live holder. This sweeper is now holding a snapshot that names a holder nobody has any more, so
        // its reset must be refused — resetting on key alone would erase a running turn's reservation and let the
        // next retry execute the input a second time.
        final IdempotencyStore raced = new ScriptedScanStore(store, scannedBeforeHandover,
                () -> handOver("k-9", "dead-node", "successor-node"));

        sweeper(raced, sink, announcer).sweepOnce();

        assertThat(announcer.announcements).as("a turn that is running elsewhere must not be announced as lost")
                .isEmpty();
        assertThat(sink.emitted).as("no HOLDER_LOST frame for a live turn").isEmpty();
        assertThat(store.find("k-9").flatMap(IdempotencyEntry::getHolderId))
                .as("the successor's reservation must survive the late sweep").contains("successor-node");
    }

    @Test
    @DisplayName("compareAndReset refuses a holder id the entry does not carry")
    void compareAndResetHonoursExpectedHolderId() {
        // The one-winner property above rests entirely on this: whoever calls with a holder id that is no longer the
        // one on the entry loses. Asserted against the store directly, because a sweeper can only ever pass the id it
        // read a moment earlier, and a store that ignored the argument would look identical through it.
        plantStale("k-10", "dead-node", Duration.ofMinutes(1));

        assertThat(store.compareAndReset("k-10", "some-other-node")).as("a mismatched holder id must not win")
                .isFalse();
        assertThat(store.find("k-10")).as("a refused reset must leave the reservation intact").isPresent();
        assertThat(store.compareAndReset("k-10", "dead-node")).as("the observed holder id must win").isTrue();
        assertThat(store.find("k-10")).as("the winning reset removes the reservation").isEmpty();
    }

    @Test
    @DisplayName("a freshly touched reservation is left alone")
    void liveHolderIsNotDeclaredLost() {
        final Instant now = Instant.now();
        store.putIfAbsent("k-4",
                IdempotencyEntry.builder().key("k-4").sessionId(CONV).inputHash("h")
                        .status(IdempotencyEntry.Status.IN_FLIGHT).holderId("live-node").createdAt(now)
                        .lastTouchedAt(now).build(),
                Duration.ofMinutes(10));
        final RecordingSink sink = new RecordingSink();
        final RecordingAnnouncer announcer = new RecordingAnnouncer();

        sweeper(sink, announcer).sweepOnce();

        assertThat(announcer.announcements).isEmpty();
        assertThat(sink.emitted).isEmpty();
        assertThat(store.find("k-4")).as("a live turn's reservation must survive the sweep").isPresent();
    }

    @Test
    @DisplayName("a holderless reservation waiting in the inbox is not holder loss")
    void holderlessReservationIsIgnored() {
        final Instant old = Instant.now().minus(Duration.ofMinutes(1)).minus(SECONDARY_TTL);
        // What releaseHolder() leaves behind: the key stays reserved so a client retry cannot re-execute the input, but
        // no node is executing it, so nobody touches it and it is stale by construction rather than by failure.
        store.putIfAbsent("k-5",
                IdempotencyEntry.builder().key("k-5").sessionId(CONV).inputHash("h")
                        .status(IdempotencyEntry.Status.IN_FLIGHT).createdAt(old).lastTouchedAt(old).build(),
                Duration.ofMinutes(5));
        final RecordingAnnouncer announcer = new RecordingAnnouncer();

        sweeper(new RecordingSink(), announcer).sweepOnce();

        assertThat(announcer.announcements).isEmpty();
        assertThat(store.find("k-5")).as("the reservation must outlive the sweep").isPresent();
    }

    @Test
    @DisplayName("a throwing announcer does not abort the pass or undo the reset")
    void announcerFailureIsContained() {
        plantStale("k-6", "dead-node", Duration.ofMinutes(1));
        plantStale("k-7", "dead-node", Duration.ofMinutes(1));
        final RecordingAnnouncer announcer = new RecordingAnnouncer() {
            @Override
            public void announceHolderLost(SessionId sessionId, String idempotencyKey, String lostHolderId) {
                super.announceHolderLost(sessionId, idempotencyKey, lostHolderId);
                throw new IllegalStateException("rail down");
            }
        };

        sweeper(new RecordingSink(), announcer).sweepOnce();

        assertThat(announcer.announcements).as("one bad announcement must not cost the rest of the batch").hasSize(2);
        assertThat(store.findStaleInFlight(Instant.now())).isEmpty();
    }

    @Test
    @DisplayName("a throwing sink does not stop the announcement")
    void sinkFailureStillAnnounces() {
        plantStale("k-8", "dead-node", Duration.ofMinutes(1));
        final RecordingAnnouncer announcer = new RecordingAnnouncer();

        sweeper(new ThrowingSink(), announcer).sweepOnce();

        assertThat(announcer.announcements).containsExactly("c-sweep|k-8|dead-node");
    }

    private HolderLossSweeper sweeper(EventSink sink, LostTurnAnnouncer announcer) {
        return sweeper(store, sink, announcer);
    }

    private HolderLossSweeper sweeper(IdempotencyStore backing, EventSink sink, LostTurnAnnouncer announcer) {
        return HolderLossSweeper.builder().store(backing).eventSink(sink).announcer(announcer).scheduler(scheduler)
                .sweepInterval(Duration.ofSeconds(15)).secondaryTtl(SECONDARY_TTL).build();
    }

    /** Plants an IN_FLIGHT entry whose {@code lastTouchedAt} sits {@code staleBy} past the secondary TTL cutoff. */
    private void plantStale(String key, String holderId, Duration staleBy) {
        final Instant touched = Instant.now().minus(SECONDARY_TTL).minus(staleBy);
        store.putIfAbsent(key,
                IdempotencyEntry.builder().key(key).sessionId(CONV).inputHash("h")
                        .status(IdempotencyEntry.Status.IN_FLIGHT).holderId(holderId).createdAt(touched)
                        .lastTouchedAt(touched).build(),
                Duration.ofMinutes(10));
    }

    /**
     * Replays the reset-then-re-reserve that a peer node and a client retry perform between one sweeper's scan and its
     * {@code compareAndReset}: the old reservation goes away and the key comes back under {@code newHolderId}, freshly
     * touched. Of the two fields {@code compareAndReset} compares — status and holder id — only the holder id then
     * distinguishes it from what the late sweeper is still holding a snapshot of; the timestamps differ too, and it is
     * exactly because the CAS does not look at them that the holder id has to be what saves the new reservation.
     */
    private void handOver(String key, String lostHolderId, String newHolderId) {
        store.compareAndReset(key, lostHolderId);
        final Instant now = Instant.now();
        store.putIfAbsent(key,
                IdempotencyEntry.builder().key(key).sessionId(CONV).inputHash("h")
                        .status(IdempotencyEntry.Status.IN_FLIGHT).holderId(newHolderId).createdAt(now)
                        .lastTouchedAt(now).build(),
                Duration.ofMinutes(10));
    }

    /**
     * Delegating store that pins down the interleaving a sweeper is otherwise at the mercy of.
     * {@code findStaleInFlight} ignores the delegate and returns a scan recorded up front, after running
     * {@code afterScan} — so anything a peer
     * would have done to the entry between the scan and the reset happens on this thread, in a fixed order, with no
     * sleeping and nothing to flake. Every other call goes straight through, so the reset the sweeper attempts is the
     * real one.
     */
    private static final class ScriptedScanStore implements IdempotencyStore {

        private final IdempotencyStore delegate;
        private final List<IdempotencyEntry> scan;
        private final Runnable afterScan;

        ScriptedScanStore(IdempotencyStore delegate, List<IdempotencyEntry> scan) {
            this(delegate, scan, () -> {
            });
        }

        ScriptedScanStore(IdempotencyStore delegate, List<IdempotencyEntry> scan, Runnable afterScan) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
            this.scan = Objects.requireNonNull(scan, "scan must not be null");
            this.afterScan = Objects.requireNonNull(afterScan, "afterScan must not be null");
        }

        @Override
        public List<IdempotencyEntry> findStaleInFlight(Instant cutoff) {
            afterScan.run();
            return scan;
        }

        @Override
        public PutResult putIfAbsent(String key, IdempotencyEntry entry, Duration ttl) {
            return delegate.putIfAbsent(key, entry, ttl);
        }

        @Override
        public void markDone(String key, AgentExecutionResult result) {
            delegate.markDone(key, result);
        }

        @Override
        public Optional<IdempotencyEntry> find(String key) {
            return delegate.find(key);
        }

        @Override
        public boolean touch(String key, String holderId) {
            return delegate.touch(key, holderId);
        }

        @Override
        public boolean releaseHolder(String key, String expectedHolderId, Duration ttl) {
            return delegate.releaseHolder(key, expectedHolderId, ttl);
        }

        @Override
        public boolean acquireHolder(String key, String holderId, Duration ttl) {
            return delegate.acquireHolder(key, holderId, ttl);
        }

        @Override
        public boolean discardReservation(String key) {
            return delegate.discardReservation(key);
        }

        @Override
        public boolean compareAndReset(String key, String expectedHolderId) {
            return delegate.compareAndReset(key, expectedHolderId);
        }
    }

    private static class RecordingAnnouncer implements LostTurnAnnouncer {

        final List<String> announcements = new CopyOnWriteArrayList<>();

        @Override
        public void announceHolderLost(SessionId sessionId, String idempotencyKey, String lostHolderId) {
            announcements.add(sessionId.value() + "|" + idempotencyKey + "|" + lostHolderId);
        }
    }

    private static final class RecordingSink implements EventSink {

        final List<AgentExecutionEvent> emitted = new CopyOnWriteArrayList<>();

        @Override
        public void emit(SessionId id, AgentExecutionEvent event) {
            emitted.add(event);
        }
    }

    private static final class ThrowingSink implements EventSink {

        @Override
        public void emit(SessionId id, AgentExecutionEvent event) {
            throw new IllegalStateException("no subscribers, no publisher");
        }
    }
}
