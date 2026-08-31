package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;

/**
 * Contract tests for {@link InMemorySessionLeaseStore} — the default election backend and the one every unit test
 * in
 * the codebase runs on.
 *
 * <p>
 * It had no tests of its own before this, which is how a defect in {@code extend} survived: it was the backend behind
 * hundreds of passing tests precisely because those tests exercised the layers above it and never asserted its contract
 * directly. Fencing monotonicity, the expiry-only acquire predicate, and the "extend is not a liveness check" carve-out
 * are
 * all stated in {@link SessionLeaseStore}'s javadoc and are all invisible unless somebody asserts them here.
 *
 * <p>
 * Expiry is driven by a mutable clock rather than by sleeping, so the timing cases are deterministic.
 */
@DisplayName("InMemorySessionLeaseStore")
class InMemorySessionLeaseStoreTest {

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    private MutableClock clock;
    private InMemorySessionLeaseStore store;
    private SessionId conv;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        store = new InMemorySessionLeaseStore(clock);
        conv = SessionId.of("conv-1");
    }

    @Nested
    @DisplayName("tryAcquire")
    class TryAcquire {

        @Test
        @DisplayName("an unheld conversation is acquired, and the lease describes the acquisition")
        void acquiresWhenUnheld() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            assertThat(lease.getSessionId()).isEqualTo(conv);
            assertThat(lease.getHolderId()).isEqualTo("node-A");
            assertThat(lease.getLease()).isEqualTo(TEN_SECONDS);
            assertThat(lease.getAcquiredAt()).isEqualTo(clock.instant());
            assertThat(lease.getFencingToken()).isPositive();
        }

        @Test
        @DisplayName("a live lease blocks a second acquirer")
        void rejectsWhenHeldByAnother() {
            store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            assertThat(store.tryAcquire(conv, "node-B", TEN_SECONDS)).isEmpty();
        }

        @Test
        @DisplayName("a live lease blocks the same holder too — acquisition is not re-entrant")
        void rejectsSameHolderTwice() {
            store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            // The SPI documents holder identity as node-derived and stable, which only works because acquire compares
            // expiry alone. If it compared identity as well, two concurrent turns on one node would both "win".
            assertThat(store.tryAcquire(conv, "node-A", TEN_SECONDS)).isEmpty();
        }

        @Test
        @DisplayName("an expired lease is taken over without an explicit release")
        void takesOverExpiredLease() {
            final SessionLease first = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            clock.advance(TEN_SECONDS);

            final SessionLease second = store.tryAcquire(conv, "node-B", TEN_SECONDS).orElseThrow();

            assertThat(second.getHolderId()).isEqualTo("node-B");
            assertThat(second.getFencingToken()).isGreaterThan(first.getFencingToken());
        }

        @Test
        @DisplayName("expiry is exclusive: the lease is still held at the instant before it lapses")
        void heldUntilExpiryInstant() {
            store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            clock.advance(TEN_SECONDS.minusNanos(1));
            assertThat(store.tryAcquire(conv, "node-B", TEN_SECONDS)).isEmpty();

            clock.advance(Duration.ofNanos(1));
            assertThat(store.tryAcquire(conv, "node-B", TEN_SECONDS)).isPresent();
        }

        @Test
        @DisplayName("different conversations do not contend")
        void conversationsAreIndependent() {
            store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            assertThat(store.tryAcquire(SessionId.of("conv-2"), "node-A", TEN_SECONDS)).isPresent();
        }

        @Test
        @DisplayName("null arguments are rejected")
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(() -> store.tryAcquire(null, "node-A", TEN_SECONDS));
            assertThatNullPointerException().isThrownBy(() -> store.tryAcquire(conv, null, TEN_SECONDS));
            assertThatNullPointerException().isThrownBy(() -> store.tryAcquire(conv, "node-A", null));
        }
    }

    @Nested
    @DisplayName("fencing tokens")
    class FencingTokens {

        @Test
        @DisplayName("strictly increase across release-and-reacquire")
        void increaseAcrossReleaseCycle() {
            final SessionLease first = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            store.release(first);
            final SessionLease second = store.tryAcquire(conv, "node-B", TEN_SECONDS).orElseThrow();

            // The contract that matters most: release deletes the entry outright here, so the counter must live
            // outside it. A per-entry counter would restart at 1 and fencing would silently stop working.
            assertThat(second.getFencingToken()).isGreaterThan(first.getFencingToken());
        }

        @Test
        @DisplayName("are drawn from one counter shared by all conversations")
        void areGloballyUnique() {
            final SessionLease a = store.tryAcquire(SessionId.of("a"), "node-A", TEN_SECONDS).orElseThrow();
            final SessionLease b = store.tryAcquire(SessionId.of("b"), "node-A", TEN_SECONDS).orElseThrow();

            assertThat(b.getFencingToken()).isGreaterThan(a.getFencingToken());
        }

        @Test
        @DisplayName("survive an extend — renewal is not a new acquisition")
        void unchangedByExtend() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            assertThat(store.extend(lease, TEN_SECONDS)).isTrue();

            assertThat(store.findHolder(conv).orElseThrow().getFencingToken()).isEqualTo(lease.getFencingToken());
        }
    }

    @Nested
    @DisplayName("findHolder")
    class FindHolder {

        @Test
        @DisplayName("reports the live holder with its token and expiry")
        void reportsLiveHolder() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            final LeaseHolder holder = store.findHolder(conv).orElseThrow();

            assertThat(holder.getHolderId()).isEqualTo("node-A");
            assertThat(holder.getFencingToken()).isEqualTo(lease.getFencingToken());
            assertThat(holder.getExpiresAt()).isEqualTo(clock.instant().plus(TEN_SECONDS));
        }

        @Test
        @DisplayName("is empty for a conversation nobody has ever held")
        void emptyWhenUnknown() {
            assertThat(store.findHolder(conv)).isEmpty();
        }

        @Test
        @DisplayName("is empty after release")
        void emptyAfterRelease() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            store.release(lease);

            assertThat(store.findHolder(conv)).isEmpty();
        }

        @Test
        @DisplayName("is empty once the lease has lapsed, even though the entry lingers")
        void emptyAfterExpiry() {
            store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            clock.advance(TEN_SECONDS);

            // This is the liveness check the fenced write path depends on, and the reason it uses findHolder rather
            // than extend: there is no sweeper, so the expired entry is still sitting in the map right now.
            assertThat(store.findHolder(conv)).isEmpty();
        }

        @Test
        @DisplayName("reflects an extend's new expiry")
        void reflectsExtendedExpiry() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            clock.advance(Duration.ofSeconds(9));
            store.extend(lease, TEN_SECONDS);

            assertThat(store.findHolder(conv).orElseThrow().getExpiresAt())
                    .isEqualTo(clock.instant().plus(TEN_SECONDS));
        }
    }

    @Nested
    @DisplayName("extend")
    class Extend {

        @Test
        @DisplayName("succeeds while the token is still the stored one")
        void succeedsForCurrentToken() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            assertThat(store.extend(lease, Duration.ofSeconds(30))).isTrue();
            assertThat(store.findHolder(conv).orElseThrow().getExpiresAt())
                    .isEqualTo(clock.instant().plus(Duration.ofSeconds(30)));
        }

        @Test
        @DisplayName("fails once another holder has taken over")
        void failsAfterTakeover() {
            final SessionLease mine = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            clock.advance(TEN_SECONDS);
            store.tryAcquire(conv, "node-B", TEN_SECONDS).orElseThrow();

            assertThat(store.extend(mine, TEN_SECONDS)).isFalse();
        }

        @Test
        @DisplayName("fails after the lease was released, because the entry is gone")
        void failsAfterRelease() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            store.release(lease);

            assertThat(store.extend(lease, TEN_SECONDS)).isFalse();
        }

        @Test
        @DisplayName("resurrects a lease that lapsed without a successor — extend is not a liveness check")
        void resurrectsLapsedLeaseWithNoSuccessor() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            clock.advance(TEN_SECONDS);
            assertThat(store.findHolder(conv)).isEmpty();

            // Documented, and shared with the Postgres and Mongo backends: the token still matches, so the CAS
            // succeeds. Pinning it here so a future backend rewrite cannot quietly change the contract in one place.
            assertThat(store.extend(lease, TEN_SECONDS)).isTrue();
            assertThat(store.findHolder(conv).orElseThrow().getHolderId()).isEqualTo("node-A");
        }

        @Test
        @DisplayName("fails for a conversation that was never acquired")
        void failsForUnknownConversation() {
            final SessionLease fabricated = SessionLease.builder().sessionId(conv).holderId("node-A").fencingToken(42L)
                    .acquiredAt(clock.instant()).lease(TEN_SECONDS).build();

            assertThat(store.extend(fabricated, TEN_SECONDS)).isFalse();
        }

        @Test
        @DisplayName("null arguments are rejected")
        void rejectsNulls() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            assertThatNullPointerException().isThrownBy(() -> store.extend(null, TEN_SECONDS));
            assertThatNullPointerException().isThrownBy(() -> store.extend(lease, null));
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("frees the conversation for the next acquirer")
        void freesTheSession() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            store.release(lease);

            assertThat(store.tryAcquire(conv, "node-B", TEN_SECONDS)).isPresent();
        }

        @Test
        @DisplayName("is a no-op for a stale token, so a superseded holder cannot evict the current one")
        void ignoresStaleToken() {
            final SessionLease stale = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();
            clock.advance(TEN_SECONDS);
            final SessionLease live = store.tryAcquire(conv, "node-B", TEN_SECONDS).orElseThrow();

            store.release(stale);

            assertThat(store.findHolder(conv).orElseThrow().getFencingToken()).isEqualTo(live.getFencingToken());
        }

        @Test
        @DisplayName("is idempotent")
        void isIdempotent() {
            final SessionLease lease = store.tryAcquire(conv, "node-A", TEN_SECONDS).orElseThrow();

            store.release(lease);
            store.release(lease);

            assertThat(store.findHolder(conv)).isEmpty();
        }

        @Test
        @DisplayName("null is rejected")
        void rejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> store.release(null));
        }
    }

    @Test
    @DisplayName("exactly one of many concurrent acquirers wins")
    void concurrentAcquireElectsOneWinner() throws Exception {
        final int contenders = 16;
        final ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            final CountDownLatch gate = new CountDownLatch(1);
            final List<Future<Optional<SessionLease>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                final String holderId = "node-" + i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    return store.tryAcquire(conv, holderId, TEN_SECONDS);
                }));
            }
            gate.countDown();

            int winners = 0;
            for (Future<Optional<SessionLease>> f : futures) {
                if (f.get(10, TimeUnit.SECONDS).isPresent()) {
                    winners++;
                }
            }

            // The CAS retry loop is the whole reason this class is not a synchronized map: it must never hand out two
            // live leases for one session, no matter how many threads arrive at once.
            assertThat(winners).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    /** A clock the test moves by hand, so lease expiry needs no sleeping. */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration amount) {
            now.updateAndGet(current -> current.plus(amount));
        }

        @Override
        public ZoneId getZone() {
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
    }
}
