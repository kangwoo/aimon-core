package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.PutResult;
import at.aimon.core.agent.session.store.StoredAgentExecutionResult;
import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * Integration tests for {@link MongoIdempotencyStore} against a real MongoDB replica-set container.
 */
@DisplayName("MongoIdempotencyStore integration")
@Tag("docker")
class MongoIdempotencyStoreIntegrationTest {

    private MongoIdempotencyStore store;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        store = new MongoIdempotencyStore(MongoTestSupport.sharedDatabase(), DocumentKeys.COLL_IDEMPOTENCY,
                Duration.ofHours(24), Clock.systemUTC());
    }

    @Test
    @DisplayName("putIfAbsent inserts on first call and returns existing on subsequent calls")
    void putIfAbsentSemantics() {
        final IdempotencyEntry first = inFlight("k-1", "h-1");
        assertThat(store.putIfAbsent("k-1", first, Duration.ofSeconds(30)).getKind())
                .isEqualTo(PutResult.Kind.INSERTED);

        final IdempotencyEntry second = inFlight("k-1", "h-2");
        final PutResult res = store.putIfAbsent("k-1", second, Duration.ofSeconds(30));
        assertThat(res.getKind()).isEqualTo(PutResult.Kind.EXISTING);
        assertThat(res.getCurrent()).isPresent();
        assertThat(res.getCurrent().orElseThrow().getHolderId()).hasValue("h-1");
    }

    @Test
    @DisplayName("markDone transitions IN_FLIGHT → DONE and caches the result")
    void markDoneCachesResult() {
        store.putIfAbsent("k-2", inFlight("k-2", "h-1"), Duration.ofSeconds(30));

        final AgentExecutionResult result = StoredAgentExecutionResult.builder().success(true).finalAnswer("hello")
                .completionReason(CompletionReason.COMPLETED).build();
        store.markDone("k-2", result);

        final Optional<IdempotencyEntry> found = store.find("k-2");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
        assertThat(found.orElseThrow().getResult()).isPresent();
        assertThat(found.orElseThrow().getResult().orElseThrow().getFinalAnswer()).isEqualTo("hello");
    }

    @Test
    @DisplayName("touch refreshes an in-flight entry only when the holder matches")
    void touchHonorsHolder() {
        store.putIfAbsent("k-3", inFlight("k-3", "h-1"), Duration.ofSeconds(30));
        assertThat(store.touch("k-3", "h-1")).isTrue();
        assertThat(store.touch("k-3", "wrong-holder")).isFalse();
    }

    @Test
    @DisplayName("compareAndReset deletes the entry only when holder matches; loser sees no entry")
    void compareAndResetOneWinner() {
        store.putIfAbsent("k-4", inFlight("k-4", "h-stale"), Duration.ofSeconds(30));
        assertThat(store.compareAndReset("k-4", "h-not-the-holder")).isFalse();
        assertThat(store.compareAndReset("k-4", "h-stale")).isTrue();
        assertThat(store.find("k-4")).isEmpty();
    }

    @Test
    @DisplayName("findStaleInFlight returns IN_FLIGHT entries whose lastTouchedAt is before cutoff")
    void findStaleScansAndFilters() {
        // Pin the store's clock to "now" so the sweeper sees deterministic timestamps regardless of Mongo container
        // time.
        final Instant now = Instant.now();
        final MongoIdempotencyStore pinned = new MongoIdempotencyStore(MongoTestSupport.sharedDatabase(),
                DocumentKeys.COLL_IDEMPOTENCY, Duration.ofHours(24), Clock.fixed(now, ZoneOffset.UTC));

        pinned.putIfAbsent("k-old", inFlightAt("k-old", "h-1", now.minusSeconds(120)), Duration.ofSeconds(60));
        pinned.putIfAbsent("k-fresh", inFlightAt("k-fresh", "h-2", now), Duration.ofSeconds(60));

        final var stale = pinned.findStaleInFlight(now.minusSeconds(60));
        assertThat(stale).extracting(IdempotencyEntry::getKey).containsExactly("k-old");
    }

    @Test
    @DisplayName("releaseHolder keeps the entry reserved but clears the holder")
    void releaseHolderKeepsTheReservation() {
        store.putIfAbsent("k-rel", inFlight("k-rel", "h-1"), Duration.ofSeconds(30));

        assertThat(store.releaseHolder("k-rel", "h-1", Duration.ofMinutes(5))).isTrue();

        final Optional<IdempotencyEntry> found = store.find("k-rel");
        assertThat(found).as("the key must stay reserved for the node that will run the turn").isPresent();
        assertThat(found.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(found.orElseThrow().getHolderId()).as("nobody is executing it yet").isEmpty();
    }

    @Test
    @DisplayName("releaseHolder refuses a wrong holder and a missing key")
    void releaseHolderHonorsHolder() {
        store.putIfAbsent("k-rel-2", inFlight("k-rel-2", "h-1"), Duration.ofSeconds(30));

        assertThat(store.releaseHolder("k-rel-2", "h-other", Duration.ofMinutes(5))).isFalse();
        assertThat(store.find("k-rel-2").orElseThrow().getHolderId()).as("a losing caller must not disown the holder")
                .hasValue("h-1");
        assertThat(store.releaseHolder("k-absent", "h-1", Duration.ofMinutes(5))).isFalse();
    }

    @Test
    @DisplayName("markDone still caches the result of a turn whose holder was released")
    void markDoneWorksOnAReleasedReservation() {
        store.putIfAbsent("k-rel-3", inFlight("k-rel-3", "h-1"), Duration.ofSeconds(30));
        store.releaseHolder("k-rel-3", "h-1", Duration.ofMinutes(5));

        store.markDone("k-rel-3", StoredAgentExecutionResult.builder().success(true).finalAnswer("drained")
                .completionReason(CompletionReason.COMPLETED).build());

        final Optional<IdempotencyEntry> found = store.find("k-rel-3");
        assertThat(found.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
        assertThat(found.orElseThrow().getResult().orElseThrow().getFinalAnswer()).isEqualTo("drained");
    }

    @Test
    @DisplayName("findStaleInFlight skips a released reservation — it is queued, not lost")
    void findStaleSkipsReleasedReservations() {
        final Instant now = Instant.now();
        final MongoIdempotencyStore pinned = new MongoIdempotencyStore(MongoTestSupport.sharedDatabase(),
                DocumentKeys.COLL_IDEMPOTENCY, Duration.ofHours(24), Clock.fixed(now, ZoneOffset.UTC));

        pinned.putIfAbsent("k-held", inFlightAt("k-held", "h-1", now), Duration.ofMinutes(5));
        pinned.putIfAbsent("k-queued", inFlightAt("k-queued", "h-1", now), Duration.ofMinutes(5));
        pinned.releaseHolder("k-queued", "h-1", Duration.ofMinutes(5));

        // Everything is stale against a future cutoff; only the entry with a holder is evidence of a lost node.
        final var stale = pinned.findStaleInFlight(now.plusSeconds(600));
        assertThat(stale).extracting(IdempotencyEntry::getKey).containsExactly("k-held");
    }

    @Test
    @DisplayName("discardReservation frees the key a forwarded turn reserved once that turn failed for good")
    void discardReservationFreesAQueuedReservation() {
        store.putIfAbsent("k-disc", inFlight("k-disc", "h-1"), Duration.ofSeconds(30));
        // releaseHolder is the only route to a holderless entry, and it is the state this method exists for: the
        // message is waiting in an inbox with nobody executing it, so compareAndReset has no holder to match on.
        store.releaseHolder("k-disc", "h-1", Duration.ofMinutes(5));

        assertThat(store.discardReservation("k-disc")).isTrue();
        assertThat(store.find("k-disc")).as("the client's retry must find the key free, not still reserved").isEmpty();
    }

    @Test
    @DisplayName("discardReservation leaves a cached result replayable")
    void discardReservationSparesADoneEntry() {
        store.putIfAbsent("k-disc-done", inFlight("k-disc-done", "h-1"), Duration.ofSeconds(30));
        store.markDone("k-disc-done", StoredAgentExecutionResult.builder().success(true).finalAnswer("cached")
                .completionReason(CompletionReason.COMPLETED).build());

        assertThat(store.discardReservation("k-disc-done")).isFalse();

        // markDone clears the holder as well, so only the status guard tells a finished turn from a reservation —
        // and a false return would not by itself prove the document is still there to replay.
        final Optional<IdempotencyEntry> found = store.find("k-disc-done");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
        assertThat(found.orElseThrow().getResult().orElseThrow().getFinalAnswer()).isEqualTo("cached");
    }

    @Test
    @DisplayName("discardReservation refuses an entry with a live holder and a key that does not exist")
    void discardReservationSparesAHeldEntry() {
        store.putIfAbsent("k-disc-held", inFlight("k-disc-held", "h-1"), Duration.ofSeconds(30));

        assertThat(store.discardReservation("k-disc-held")).isFalse();
        assertThat(store.discardReservation("k-disc-absent")).isFalse();

        // A turn is executing on h-1 right now: the document must survive and stay touchable by its holder.
        assertThat(store.find("k-disc-held").orElseThrow().getHolderId()).hasValue("h-1");
        assertThat(store.touch("k-disc-held", "h-1")).isTrue();
    }

    @Test
    @DisplayName("acquireHolder takes over a queued reservation and puts it back on the sweeper's clock")
    void acquireHolderTakesOverAQueuedReservation() {
        store.putIfAbsent("k-acq", inFlight("k-acq", "h-submitter"), Duration.ofSeconds(30));
        // The state the take-over exists for: the submitter reserved the key, lost the election, and handed the turn
        // to the inbox. Whoever collects that message is the one now running it, and has to say so.
        store.releaseHolder("k-acq", "h-submitter", Duration.ofMinutes(5));

        assertThat(store.acquireHolder("k-acq", "h-drainer", Duration.ofSeconds(30))).isTrue();

        final Optional<IdempotencyEntry> found = store.find("k-acq");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(found.orElseThrow().getHolderId()).hasValue("h-drainer");
        // Naming a holder is only half of it — the new holder's lease renewer has to be able to keep it alive.
        assertThat(store.touch("k-acq", "h-drainer")).isTrue();
    }

    @Test
    @DisplayName("acquireHolder is what lets findStaleInFlight see the draining node die")
    void acquireHolderMakesTheEntryVisibleToTheSweeper() {
        final Instant now = Instant.now();
        // Pin the clock so lastTouchedAt is the store's own "now" regardless of container time.
        final MongoIdempotencyStore pinned = new MongoIdempotencyStore(MongoTestSupport.sharedDatabase(),
                DocumentKeys.COLL_IDEMPOTENCY, Duration.ofHours(24), Clock.fixed(now, ZoneOffset.UTC));

        pinned.putIfAbsent("k-acq-stale", inFlightAt("k-acq-stale", "h-submitter", now), Duration.ofMinutes(5));
        pinned.releaseHolder("k-acq-stale", "h-submitter", Duration.ofMinutes(5));
        assertThat(pinned.findStaleInFlight(now.plusSeconds(600)))
                .as("a message still waiting in the inbox is executed by nobody and must stay invisible").isEmpty();

        pinned.acquireHolder("k-acq-stale", "h-drainer", Duration.ofMinutes(5));

        assertThat(pinned.findStaleInFlight(now.plusSeconds(600))).singleElement()
                .satisfies(entry -> assertThat(entry.getHolderId()).hasValue("h-drainer"));
        // And the reset the sweeper follows its scan with matches on the name the take-over wrote.
        assertThat(pinned.compareAndReset("k-acq-stale", "h-drainer")).isTrue();
    }

    @Test
    @DisplayName("acquireHolder spares a cached result, an entry someone else holds, and a key that does not exist")
    void acquireHolderSparesEverythingElse() {
        store.putIfAbsent("k-acq-done", inFlight("k-acq-done", "h-1"), Duration.ofSeconds(30));
        store.markDone("k-acq-done", StoredAgentExecutionResult.builder().success(true).finalAnswer("cached")
                .completionReason(CompletionReason.COMPLETED).build());
        store.putIfAbsent("k-acq-held", inFlight("k-acq-held", "h-1"), Duration.ofSeconds(30));

        assertThat(store.acquireHolder("k-acq-done", "h-drainer", Duration.ofSeconds(30))).isFalse();
        assertThat(store.acquireHolder("k-acq-held", "h-drainer", Duration.ofSeconds(30))).isFalse();
        assertThat(store.acquireHolder("k-acq-absent", "h-drainer", Duration.ofSeconds(30))).isFalse();

        // markDone clears the holder too, so a DONE entry looks holderless exactly like a reservation — only the
        // status match keeps a cached answer from being dragged back into an executing state.
        final Optional<IdempotencyEntry> done = store.find("k-acq-done");
        assertThat(done.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
        assertThat(done.orElseThrow().getResult().orElseThrow().getFinalAnswer()).isEqualTo("cached");
        assertThat(store.find("k-acq-held").orElseThrow().getHolderId()).as("a turn is executing on h-1 right now")
                .hasValue("h-1");
    }

    // Sequential on one thread, and named for what it does — the guard, not the concurrency. Real simultaneity rests
    // on the backend's own CAS / conditional update, not on an interleaving this test could force.
    @Test
    @DisplayName("a reservation already taken over is refused to the next caller")
    void acquireHolderProducesOneWinner() {
        store.putIfAbsent("k-acq-race", inFlight("k-acq-race", "h-submitter"), Duration.ofSeconds(30));
        store.releaseHolder("k-acq-race", "h-submitter", Duration.ofMinutes(5));

        assertThat(store.acquireHolder("k-acq-race", "h-drainer-A", Duration.ofSeconds(30))).isTrue();
        // The second caller must be told, not allowed to overwrite: the first is the node actually running the turn,
        // and a stolen entry would have the sweeper reset the key against a holder that never had it.
        assertThat(store.acquireHolder("k-acq-race", "h-drainer-B", Duration.ofSeconds(30))).isFalse();
        assertThat(store.find("k-acq-race").orElseThrow().getHolderId()).hasValue("h-drainer-A");
    }

    private static IdempotencyEntry inFlight(String key, String holderId) {
        return inFlightAt(key, holderId, Instant.now());
    }

    private static IdempotencyEntry inFlightAt(String key, String holderId, Instant when) {
        return IdempotencyEntry.builder().key(key).sessionId(SessionId.of("c-" + key)).inputHash("hash-1")
                .status(IdempotencyEntry.Status.IN_FLIGHT).holderId(holderId).createdAt(when).lastTouchedAt(when)
                .build();
    }
}
