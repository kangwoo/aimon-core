package at.aimon.core.agent.session.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.StoredAgentExecutionResult;

/**
 * Discard contract for the in-process idempotency store.
 *
 * <p>
 * {@code discardReservation} is {@code compareAndReset}'s counterpart for the forwarded path: it drops the holderless
 * {@code IN_FLIGHT} entry {@code releaseHolder} leaves for a message waiting in an inbox, once that message's turn has
 * failed for good. {@code compareAndReset} cannot reach those entries because it matches on a holder and a queued
 * reservation deliberately has none, so without this method a failed forwarded turn keeps its key reserved for the
 * whole forward TTL and the client's retry is told it collapsed onto an attempt that is already dead.
 *
 * <p>
 * The three states it must <em>not</em> touch are the interesting half: a {@code DONE} entry is a result worth
 * replaying, an entry with a holder is a turn executing somewhere else, and neither is this caller's to erase. Those
 * cases assert that the entry survives rather than only that {@code false} came back — a {@code false} return says
 * nothing about whether the entry is still there.
 */
@DisplayName("InMemoryIdempotencyStore discards only reservations nobody is running")
class InMemoryIdempotencyStoreTest {

    private static final String KEY = "idem-1";
    private static final Instant NOW = Instant.parse("2026-04-27T10:00:00Z");

    // Fixed clock: every TTL below is comfortably in the future, so no assertion here can be decided by lazy expiry.
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("the holderless reservation a forwarded turn leaves behind is removed")
    void discardsAQueuedReservation() {
        store.putIfAbsent(KEY, inFlight(KEY, "h-1"), Duration.ofSeconds(30));
        // releaseHolder is the only route to a holderless IN_FLIGHT entry, and it is the exact state this method
        // exists for: the submitter reserved the key, lost the session lock, and handed the turn to the inbox.
        assertThat(store.releaseHolder(KEY, "h-1", Duration.ofMinutes(5))).isTrue();

        assertThat(store.discardReservation(KEY)).isTrue();
        assertThat(store.find(KEY)).as("a turn that failed for good must leave its key free, not reserved").isEmpty();
    }

    @Test
    @DisplayName("a cached result is left alone — a replayable answer is not this caller's to erase")
    void refusesToDiscardACachedResult() {
        store.putIfAbsent(KEY, inFlight(KEY, "h-1"), Duration.ofSeconds(30));
        store.markDone(KEY, StoredAgentExecutionResult.builder().success(true).finalAnswer("cached")
                .completionReason(CompletionReason.COMPLETED).build());

        assertThat(store.discardReservation(KEY)).isFalse();

        // markDone drops the holder too, so a DONE entry is holderless exactly like a reservation — only the status
        // check tells them apart. And the return value alone would not catch an implementation that deleted the
        // entry and then answered false: what matters is that the result is still there to replay.
        final Optional<IdempotencyEntry> found = store.find(KEY);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
        assertThat(found.orElseThrow().getResult().orElseThrow().getFinalAnswer()).isEqualTo("cached");
    }

    @Test
    @DisplayName("an entry a live holder still owns is left alone")
    void refusesToDiscardAHeldEntry() {
        store.putIfAbsent(KEY, inFlight(KEY, "h-1"), Duration.ofSeconds(30));

        assertThat(store.discardReservation(KEY)).isFalse();

        final Optional<IdempotencyEntry> found = store.find(KEY);
        assertThat(found).as("another node is running this turn right now").isPresent();
        assertThat(found.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(found.orElseThrow().getHolderId()).hasValue("h-1");
        // Surviving has to mean still usable, not merely still present: the holder's lease renewer must keep finding
        // the entry it is expected to touch.
        assertThat(store.touch(KEY, "h-1")).isTrue();
    }

    @Test
    @DisplayName("a key nobody ever reserved reports that there was nothing to discard")
    void reportsNothingForAnUnknownKey() {
        assertThat(store.discardReservation("k-never-seen")).isFalse();
    }

    @Test
    @DisplayName("the discarded key is free for the client's retry to reserve from scratch")
    void aDiscardedKeyIsReservableAgain() {
        store.putIfAbsent(KEY, inFlight(KEY, "h-1"), Duration.ofSeconds(30));
        store.releaseHolder(KEY, "h-1", Duration.ofMinutes(5));
        store.discardReservation(KEY);

        // Deleting rather than marking the entry failed is the whole point: a cached failure would make every later
        // retry of this key inherit it, and a surviving reservation would answer "already in flight" for an attempt
        // that is dead. Only a fresh INSERTED lets the retry actually re-execute.
        final PutResult retry = store.putIfAbsent(KEY, inFlight(KEY, "h-2"), Duration.ofSeconds(30));
        assertThat(retry.getKind()).isEqualTo(PutResult.Kind.INSERTED);
        assertThat(store.find(KEY).orElseThrow().getHolderId()).hasValue("h-2");
    }

    @Test
    @DisplayName("discarding the same reservation twice reports the removal only once")
    void aSecondDiscardFindsNothingLeft() {
        store.putIfAbsent(KEY, inFlight(KEY, "h-1"), Duration.ofSeconds(30));
        store.releaseHolder(KEY, "h-1", Duration.ofMinutes(5));

        assertThat(store.discardReservation(KEY)).isTrue();
        // The router calls this best-effort on a path that holder-loss recovery may already have swept, so a caller
        // that finds nothing must say so rather than claim a removal it did not make.
        assertThat(store.discardReservation(KEY)).isFalse();
    }

    @Test
    @DisplayName("discarding one reservation leaves another key's reservation standing")
    void discardIsScopedToItsOwnKey() {
        store.putIfAbsent(KEY, inFlight(KEY, "h-1"), Duration.ofSeconds(30));
        store.releaseHolder(KEY, "h-1", Duration.ofMinutes(5));
        store.putIfAbsent("idem-2", inFlight("idem-2", "h-1"), Duration.ofSeconds(30));
        store.releaseHolder("idem-2", "h-1", Duration.ofMinutes(5));

        assertThat(store.discardReservation(KEY)).isTrue();

        // Two turns of the same session are forwarded under different keys; one failing must not free the other's
        // reservation and let a duplicate submit of it through.
        assertThat(store.find("idem-2")).isPresent();
        assertThat(store.find("idem-2").orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
    }

    private static IdempotencyEntry inFlight(String key, String holderId) {
        return IdempotencyEntry.builder().key(key).sessionId(SessionId.of("c-" + key)).inputHash("hash-1")
                .status(IdempotencyEntry.Status.IN_FLIGHT).holderId(holderId).createdAt(NOW).lastTouchedAt(NOW).build();
    }
}
