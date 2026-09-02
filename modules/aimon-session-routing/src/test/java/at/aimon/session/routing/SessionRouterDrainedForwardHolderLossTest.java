package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.idempotency.PutResult;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * The half of holder loss that a forwarded turn used to fall outside of (design §6.3 D).
 *
 * <p>
 * A submission that loses the election hands its reservation over holderless — deliberately, because nobody executes a
 * message while it waits in the inbox and a sweeper that saw it would declare a healthy session lost. The cost was
 * that the hand-over never came back: the node that finally collected the message ran the turn anonymously, so
 * {@code findStaleInFlight} — which reports only entries that name a holder — could not see that node die. Its caller
 * was answered by nothing faster than the five-minute forward deadline, while a turn submitted to its own node was
 * answered in seconds.
 *
 * <p>
 * The drain pass now takes the reservation back over before running each message, which is what these two tests pin
 * from both ends: that the entry names the node executing it while the turn runs, and that its going quiet is reported
 * as {@code HOLDER_LOST} to the caller waiting elsewhere.
 */
@DisplayName("SessionRouter holder loss for a collected forward")
class SessionRouterDrainedForwardHolderLossTest {

    private static final String KEY = "k-drained";

    private SessionLeaseStore leaseStore;
    private SessionSignalBus bus;
    private SessionInbox inbox;
    private SessionRecordStore repository;

    private final List<TestManagerHarness> nodes = new ArrayList<>();

    @BeforeEach
    void wireSharedBackend() {
        leaseStore = new InMemorySessionLeaseStore();
        bus = new InMemorySignalBus();
        inbox = new InMemorySessionInbox();
        repository = new InMemorySessionRecordStore();
    }

    @AfterEach
    void closeNodes() {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).close();
        }
    }

    @Test
    @DisplayName("a queued message's reservation names the node that drains it, for as long as its turn runs")
    void aDrainedTurnsReservationNamesItsHolder() throws Exception {
        final IdempotencyStore idempotency = new InMemoryIdempotencyStore();
        final TestManagerHarness holder = node("node-A", idempotency, b -> {
        });
        final TestManagerHarness peer = node("node-B", idempotency, b -> {
        });
        final SessionId id = SessionId.of("c-drained-1");

        holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(keyed(id, "second"));
        assertThat(forwarded.getKind()).as("node-A holds the session, so this submission can only be queued")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(entry(idempotency).getHolderId())
                .as("a message waiting in the inbox is executed by nobody, so it must name nobody").isEmpty();

        // The holder's post-turn re-collect takes the message out of the inbox and runs it.
        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).isTrue();

        final IdempotencyEntry running = entry(idempotency);
        assertThat(running.getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(running.getHolderId()).as("the node executing the turn is the one whose death has to be visible")
                .isPresent();
        assertThat(running.getHolderId().orElseThrow()).startsWith("node-A/");

        session.completeCurrentTurn(TestLiveSession.ok("second-done"));
        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("second-done");
        // The take-over must not cost the entry its ordinary ending: markDone matches on the key alone, so a settled
        // turn still leaves a replayable result rather than a reservation named after a node that has moved on.
        assertThat(entry(idempotency).getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
    }

    @Test
    @DisplayName("the caller is failed with HOLDER_LOST when the node draining its message dies mid-turn")
    void aDrainerThatDiesMidTurnIsReportedAsHolderLoss() throws Exception {
        // Suppressing touch is exactly what a crash looks like from every other node's side: the entry stops being
        // refreshed while nothing else about the store changes. Killing the JVM is the only more faithful version and
        // it is not available to a test.
        final AtomicBoolean drainerAlive = new AtomicBoolean(true);
        final IdempotencyStore idempotency = new CrashableIdempotencyStore(new InMemoryIdempotencyStore(),
                drainerAlive);
        // Only the surviving node sweeps — a dead one runs no scheduled task of its own, and letting node-A sweep
        // would let it report a loss it is supposed to be unable to notice.
        final TestManagerHarness holder = node("node-A", idempotency,
                b -> b.holderLossSweepInterval(Duration.ofMinutes(5)));
        final TestManagerHarness peer = node("node-B", idempotency,
                b -> b.holderLossSweepInterval(Duration.ofMillis(200)).idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-drained-2");

        holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(keyed(id, "second"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).as("the queued message has to be out of the inbox and running").isTrue();
        assertThat(entry(idempotency).getHolderId()).isPresent();

        // node-A stops renewing here, with the turn still in flight and the message long gone from the inbox — the
        // one shape the orphaned-forward doorbell retry cannot help with, because there is nothing left to re-announce.
        drainerAlive.set(false);

        assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOLDER_LOST").hasMessageContaining(KEY);

        // Let the parked turn finish so the harnesses close without waiting out their shutdown grace. Its markDone
        // lands on a key the sweeper already reset, which is a no-op by design rather than an error.
        session.completeCurrentTurn(TestLiveSession.ok("second-done"));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------------------------------------------

    private TestManagerHarness node(String nodeId, IdempotencyStore idempotency,
            Consumer<TestManagerHarness.Builder> customizer) {
        final TestManagerHarness.Builder builder = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency);
        customizer.accept(builder);
        final TestManagerHarness harness = builder.build();
        nodes.add(harness);
        return harness;
    }

    private static TestLiveSession awaitSession(TestManagerHarness harness, SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("a session for %s should have been opened", id).isNotNull();
        return session;
    }

    private static SubmitRequest keyed(SessionId id, String input) {
        return SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput(input)
                .initiator(Principal.user("tester")).idempotencyKey(KEY).build();
    }

    private static IdempotencyEntry entry(IdempotencyStore store) {
        return store.find(KEY).orElseThrow(() -> new AssertionError("the reservation for " + KEY + " is gone"));
    }

    private static Throwable awaitFailure(CompletionStage<AgentExecutionResult> stage) throws Exception {
        try {
            stage.toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            return ee.getCause() != null ? ee.getCause() : ee;
        }
        throw new AssertionError("Expected the forwarded future to fail, but it completed");
    }

    /** Delegates everything, but stops applying {@code touch} once the node holding the turn is declared dead. */
    private static final class CrashableIdempotencyStore implements IdempotencyStore {

        private final IdempotencyStore delegate;
        private final AtomicBoolean alive;

        CrashableIdempotencyStore(IdempotencyStore delegate, AtomicBoolean alive) {
            this.delegate = delegate;
            this.alive = alive;
        }

        @Override
        public boolean touch(String key, String holderId) {
            return alive.get() && delegate.touch(key, holderId);
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

        @Override
        public List<IdempotencyEntry> findStaleInFlight(Instant cutoff) {
            return delegate.findStaleInFlight(cutoff);
        }
    }
}
