package at.aimon.core.agent.session.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.StoredAgentExecutionResult;

/**
 * Take-over contract for the in-process idempotency store.
 *
 * <p>
 * {@code acquireHolder} is {@code releaseHolder}'s inverse: it puts a name back on the holderless {@code IN_FLIGHT}
 * entry a forwarded turn leaves behind, at the moment some node takes that message out of the inbox and starts running
 * it. Without it the executing node is anonymous, {@code findStaleInFlight} skips the entry because it names nobody,
 * and a crash mid-turn is reported by no sweeper — the caller learns of it only when its own forward deadline lapses.
 *
 * <p>
 * The three states it must <em>not</em> take are the interesting half, and each is asserted on what survives rather
 * than only on the {@code false} return: a {@code DONE} entry is a cached answer that must stay replayable, an entry
 * with a holder is a turn executing elsewhere, and a lapsed one is a reservation whose submitter has already given up
 * — reviving it would put a key back in flight that {@code find} has been reporting as free.
 */
@DisplayName("InMemoryIdempotencyStore takes over only reservations nobody is running")
class InMemoryIdempotencyStoreAcquireHolderTest {

    private static final String KEY = "idem-1";
    private static final Instant NOW = Instant.parse("2026-04-27T10:00:00Z");
    private static final Duration SECONDARY = Duration.ofSeconds(30);
    private static final Duration FORWARD = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);

    @Test
    @DisplayName("the holderless reservation a forwarded turn leaves behind is taken over by the node running it")
    void takesOverAQueuedReservation() {
        store.putIfAbsent(KEY, inFlight(KEY, "submitter"), SECONDARY);
        assertThat(store.releaseHolder(KEY, "submitter", FORWARD)).isTrue();

        assertThat(store.acquireHolder(KEY, "drainer", SECONDARY)).isTrue();

        final IdempotencyEntry found = store.find(KEY).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(found.getHolderId()).hasValue("drainer");
        // Naming a holder is only half of it: the entry has to be one the new holder's lease renewer can keep alive,
        // or the take-over just swapped a silent five-minute wait for a noisy thirty-second one.
        assertThat(store.touch(KEY, "drainer")).isTrue();
    }

    @Test
    @DisplayName("the taken-over reservation is what makes the sweeper able to see the drainer die")
    void aTakenOverReservationBecomesVisibleToTheSweeper() {
        store.putIfAbsent(KEY, inFlight(KEY, "submitter"), SECONDARY);
        store.releaseHolder(KEY, "submitter", FORWARD);

        // Queued: stale by construction — nobody touches a message waiting in an inbox — and deliberately invisible,
        // or every healthy forward would be reported as a lost holder.
        assertThat(store.findStaleInFlight(NOW.plusSeconds(1))).isEmpty();

        store.acquireHolder(KEY, "drainer", SECONDARY);
        clock.advance(Duration.ofSeconds(31));

        assertThat(store.findStaleInFlight(clock.instant())).singleElement()
                .satisfies(entry -> assertThat(entry.getHolderId()).hasValue("drainer"));
        // And the reset the sweeper follows the scan with matches on the name this store put there.
        assertThat(store.compareAndReset(KEY, "drainer")).isTrue();
    }

    @Test
    @DisplayName("the entry is re-armed on the ttl handed in, not left on the long inbox-wait one")
    void reArmsTheEntryOnTheGivenTtl() {
        store.putIfAbsent(KEY, inFlight(KEY, "submitter"), SECONDARY);
        // A forward TTL of five minutes: what the entry carries while it waits to be collected.
        store.releaseHolder(KEY, "submitter", FORWARD);

        store.acquireHolder(KEY, "drainer", SECONDARY);
        clock.advance(Duration.ofSeconds(31));

        // Still under the forward TTL, already past the secondary one. An implementation that kept the old expiry
        // would leave a dead drainer's entry standing for minutes — the very wait this take-over exists to end.
        assertThat(store.find(KEY)).isEmpty();
    }

    @Test
    @DisplayName("a cached result is left alone — a replayable answer is not an executable turn")
    void refusesToTakeOverACachedResult() {
        store.putIfAbsent(KEY, inFlight(KEY, "submitter"), SECONDARY);
        store.markDone(KEY, StoredAgentExecutionResult.builder().success(true).finalAnswer("cached")
                .completionReason(CompletionReason.COMPLETED).build());

        assertThat(store.acquireHolder(KEY, "drainer", SECONDARY)).isFalse();

        // markDone drops the holder too, so a DONE entry is holderless exactly like a queued reservation — only the
        // status tells them apart, and the return value alone would not catch an implementation that took it anyway.
        final Optional<IdempotencyEntry> found = store.find(KEY);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
        assertThat(found.orElseThrow().getResult().orElseThrow().getFinalAnswer()).isEqualTo("cached");
    }

    @Test
    @DisplayName("an entry another holder still owns is left alone, holder and all")
    void refusesToTakeOverAHeldEntry() {
        store.putIfAbsent(KEY, inFlight(KEY, "submitter"), SECONDARY);

        assertThat(store.acquireHolder(KEY, "drainer", SECONDARY)).isFalse();

        assertThat(store.find(KEY).orElseThrow().getHolderId()).as("someone else is running this turn right now")
                .hasValue("submitter");
        // Surviving has to mean still usable: the original holder's renewer must keep finding the entry it touches.
        assertThat(store.touch(KEY, "submitter")).isTrue();
    }

    @Test
    @DisplayName("a lapsed reservation is not revived — find has been calling it absent for as long as it was")
    void refusesToReviveALapsedReservation() {
        store.putIfAbsent(KEY, inFlight(KEY, "submitter"), SECONDARY);
        store.releaseHolder(KEY, "submitter", FORWARD);
        clock.advance(Duration.ofMinutes(6));

        assertThat(store.acquireHolder(KEY, "drainer", SECONDARY)).isFalse();
        assertThat(store.find(KEY)).isEmpty();
        // The client's retry has to read as a first arrival, which it cannot if a drain pass just put the old
        // reservation back in flight under its own name.
        assertThat(store.putIfAbsent(KEY, inFlight(KEY, "retry"), SECONDARY).getKind())
                .isEqualTo(PutResult.Kind.INSERTED);
    }

    @Test
    @DisplayName("a key nobody ever reserved reports that there was nothing to take over")
    void reportsNothingForAnUnknownKey() {
        assertThat(store.acquireHolder("k-never-seen", "drainer", SECONDARY)).isFalse();
    }

    // Sequential on one thread, and named for what it does. It pins the guard rather than the concurrency: real
    // simultaneity is the backends' job, argued from their CAS / conditional-update mechanisms rather than from a
    // two-thread test whose interleaving nothing here can force.
    @Test
    @DisplayName("a reservation already taken over is refused to the next caller")
    void onlyOneAcquirerWins() {
        store.putIfAbsent(KEY, inFlight(KEY, "submitter"), SECONDARY);
        store.releaseHolder(KEY, "submitter", FORWARD);

        assertThat(store.acquireHolder(KEY, "drainer-A", SECONDARY)).isTrue();
        // The loser must be told so rather than silently overwriting the winner's name: the winner is running the turn,
        // and a stolen entry would leave the sweeper resetting a key against a holder that never had it.
        assertThat(store.acquireHolder(KEY, "drainer-B", SECONDARY)).isFalse();
        assertThat(store.find(KEY).orElseThrow().getHolderId()).hasValue("drainer-A");
    }

    private static IdempotencyEntry inFlight(String key, String holderId) {
        return IdempotencyEntry.builder().key(key).sessionId(SessionId.of("c-" + key)).inputHash("hash-1")
                .status(IdempotencyEntry.Status.IN_FLIGHT).holderId(holderId).createdAt(NOW).lastTouchedAt(NOW).build();
    }

    /** A clock the test moves by hand, so every expiry assertion is decided by the test rather than by wall time. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
