package at.aimon.session.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

/**
 * Integration tests for {@link PostgresIdempotencyStore} against a real Postgres container.
 */
@DisplayName("PostgresIdempotencyStore integration")
@Tag("docker")
class PostgresIdempotencyStoreIntegrationTest {

    private PostgresIdempotencyStore store;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        store = new PostgresIdempotencyStore(PostgresTestSupport.dataSource());
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
        assertThat(found.orElseThrow().getHolderId()).isEmpty();
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
    @DisplayName("touch re-arms the secondary TTL the caller configured, not a hardcoded window")
    void touchReArmsTheConfiguredTtl() throws SQLException {
        // A deployment whose secondary TTL is well above the design's nominal 30s — the deployment guide tells
        // operators to raise it to >= 2 x lockLease. A hardcoded slide would clamp the row back down to 30s here and
        // expire the entry mid-turn under a perfectly healthy holder.
        store.putIfAbsent("k-touch-ttl", inFlight("k-touch-ttl", "h-1"), Duration.ofMinutes(4));

        assertThat(store.touch("k-touch-ttl", "h-1")).isTrue();

        assertThat(remainingTtl("k-touch-ttl")).isGreaterThan(Duration.ofMinutes(3));
    }

    @Test
    @DisplayName("putIfAbsent reclaims a key whose TTL lapsed — Postgres expires nothing on its own")
    void putIfAbsentReclaimsLapsedRows() {
        store.putIfAbsent("k-lapsed", inFlight("k-lapsed", "h-dead"), Duration.ofMillis(10));
        try {
            Thread.sleep(50L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        final PutResult res = store.putIfAbsent("k-lapsed", inFlight("k-lapsed", "h-new"), Duration.ofSeconds(30));

        assertThat(res.getKind()).as("a lapsed entry must read as absent, as it does on Mongo and Redis")
                .isEqualTo(PutResult.Kind.INSERTED);
        // The reclaim has to replace the row, not merely report success: the new holder owns the reservation and must
        // be able to touch it, and the stale holder must not be reported to the holder-loss sweeper.
        assertThat(store.find("k-lapsed").orElseThrow().getHolderId()).hasValue("h-new");
        assertThat(store.touch("k-lapsed", "h-new")).isTrue();
    }

    @Test
    @DisplayName("compareAndReset deletes the entry only when holder matches; loser sees no entry afterwards")
    void compareAndResetOneWinner() {
        store.putIfAbsent("k-4", inFlight("k-4", "h-stale"), Duration.ofSeconds(30));
        assertThat(store.compareAndReset("k-4", "h-not-the-holder")).isFalse();
        assertThat(store.compareAndReset("k-4", "h-stale")).isTrue();
        assertThat(store.find("k-4")).isEmpty();
    }

    @Test
    @DisplayName("findStaleInFlight returns IN_FLIGHT entries whose lastTouchedAt is before cutoff")
    void findStaleScansAndFilters() {
        final Instant now = Instant.now();
        store.putIfAbsent("k-old", inFlightAt("k-old", "h-1", now.minusSeconds(120)), Duration.ofSeconds(60));
        store.putIfAbsent("k-fresh", inFlightAt("k-fresh", "h-2", now), Duration.ofSeconds(60));

        final List<IdempotencyEntry> stale = store.findStaleInFlight(now.minusSeconds(60));
        assertThat(stale).extracting(IdempotencyEntry::getKey).containsExactly("k-old");
    }

    @Test
    @DisplayName("find ignores rows whose primary TTL has expired (live-only read)")
    void findIgnoresExpiredRows() {
        // Put an entry with a tiny TTL, then sleep past it.
        store.putIfAbsent("k-ttl", inFlight("k-ttl", "h-1"), Duration.ofMillis(10));
        try {
            Thread.sleep(50L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        assertThat(store.find("k-ttl")).isEmpty();
    }

    @Test
    @DisplayName("sweepExpired reaps DONE rows past their primary TTL but leaves live rows alone")
    void sweepExpiredReapsExpiredOnly() {
        store.putIfAbsent("k-live", inFlight("k-live", "h-1"), Duration.ofMinutes(10));
        store.putIfAbsent("k-stale", inFlight("k-stale", "h-2"), Duration.ofMillis(5));
        try {
            Thread.sleep(30L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        final int deleted = store.sweepExpired(Instant.now());
        assertThat(deleted).isEqualTo(1);
        assertThat(store.find("k-live")).isPresent();
        assertThat(store.find("k-stale")).isEmpty();
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
    @DisplayName("releaseHolder extends the row's TTL so the reservation outlives the original lease")
    void releaseHolderExtendsTheTtl() {
        // A short original TTL sized for a live turn; the queue wait is budgeted separately.
        store.putIfAbsent("k-rel-ttl", inFlight("k-rel-ttl", "h-1"), Duration.ofMillis(10));
        assertThat(store.releaseHolder("k-rel-ttl", "h-1", Duration.ofMinutes(5))).isTrue();
        try {
            Thread.sleep(50L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        assertThat(store.find("k-rel-ttl")).as("find is live-only, so the row must carry the new expiry").isPresent();
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
        store.putIfAbsent("k-held", inFlightAt("k-held", "h-1", now), Duration.ofMinutes(5));
        store.putIfAbsent("k-queued", inFlightAt("k-queued", "h-1", now), Duration.ofMinutes(5));
        store.releaseHolder("k-queued", "h-1", Duration.ofMinutes(5));

        // Everything is stale against a future cutoff; only the entry with a holder is evidence of a lost node.
        final List<IdempotencyEntry> stale = store.findStaleInFlight(now.plusSeconds(600));
        assertThat(stale).extracting(IdempotencyEntry::getKey).containsExactly("k-held");
    }

    @Test
    @DisplayName("discardReservation frees the key a forwarded turn reserved once that turn failed for good")
    void discardReservationFreesAQueuedReservation() {
        store.putIfAbsent("k-disc", inFlight("k-disc", "h-1"), Duration.ofSeconds(30));
        // releaseHolder is the only route to a holder_id IS NULL row, and it is the state this method exists for: the
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

        // markDone nulls holder_id as well, so only the status predicate tells a finished turn from a reservation —
        // and a false return would not by itself prove the row is still there to replay.
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

        // A turn is executing on h-1 right now: the row must survive and stay touchable by its holder.
        assertThat(store.find("k-disc-held").orElseThrow().getHolderId()).hasValue("h-1");
        assertThat(store.touch("k-disc-held", "h-1")).isTrue();
    }

    /** Reads {@code expires_at} straight off the row — the entry itself does not carry it. */
    private static Duration remainingTtl(String key) throws SQLException {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT expires_at FROM idempotency_entry WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row must exist").isTrue();
                return Duration.between(Instant.now(), rs.getTimestamp("expires_at").toInstant());
            }
        }
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
        store.putIfAbsent("k-acq-stale", inFlightAt("k-acq-stale", "h-submitter", now), Duration.ofMinutes(5));
        store.releaseHolder("k-acq-stale", "h-submitter", Duration.ofMinutes(5));
        assertThat(store.findStaleInFlight(now.plusSeconds(600)))
                .as("a message still waiting in the inbox is executed by nobody and must stay invisible").isEmpty();

        store.acquireHolder("k-acq-stale", "h-drainer", Duration.ofMinutes(5));

        assertThat(store.findStaleInFlight(now.plusSeconds(600))).singleElement()
                .satisfies(entry -> assertThat(entry.getHolderId()).hasValue("h-drainer"));
        // And the reset the sweeper follows its scan with matches on the name the take-over wrote.
        assertThat(store.compareAndReset("k-acq-stale", "h-drainer")).isTrue();
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

    @Test
    @DisplayName("two nodes racing on one reservation produce exactly one holder")
    void acquireHolderProducesOneWinner() {
        store.putIfAbsent("k-acq-race", inFlight("k-acq-race", "h-submitter"), Duration.ofSeconds(30));
        store.releaseHolder("k-acq-race", "h-submitter", Duration.ofMinutes(5));

        assertThat(store.acquireHolder("k-acq-race", "h-drainer-A", Duration.ofSeconds(30))).isTrue();
        // The loser must be told, not allowed to overwrite: the winner is the node actually running the turn, and a
        // stolen entry would have the sweeper reset the key against a holder that never had it.
        assertThat(store.acquireHolder("k-acq-race", "h-drainer-B", Duration.ofSeconds(30))).isFalse();
        assertThat(store.find("k-acq-race").orElseThrow().getHolderId()).hasValue("h-drainer-A");
    }

    @Test
    @DisplayName("acquireHolder refuses a lapsed reservation rather than reviving a key find calls free")
    void acquireHolderRefusesALapsedReservation() {
        store.putIfAbsent("k-acq-lapsed", inFlight("k-acq-lapsed", "h-1"), Duration.ofSeconds(30));
        store.releaseHolder("k-acq-lapsed", "h-1", Duration.ofMillis(10));
        sleepPastExpiry();

        assertThat(store.acquireHolder("k-acq-lapsed", "h-drainer", Duration.ofSeconds(30))).isFalse();
        assertThat(store.find("k-acq-lapsed")).as("find has been reporting this key free the whole time").isEmpty();
    }

    private static void sleepPastExpiry() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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
