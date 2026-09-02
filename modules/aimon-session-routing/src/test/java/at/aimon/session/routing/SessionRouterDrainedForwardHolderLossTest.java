package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
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
import at.aimon.core.agent.session.signal.SessionSignal;
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
 * The drain pass now takes the reservation back over before running each message. That has two halves and both are
 * pinned here, because the first without the second is a regression rather than a fix: naming the holder is what makes
 * a <em>dead</em> drainer visible, and keeping that name touched through the lease renewer is what keeps a
 * <em>live</em> one from being swept. The renewal tick is the only caller of {@code IdempotencyTouchSlot#touch}, so
 * these tests set {@code lockExtendInterval} short enough for it to actually fire — at the harness default of 10 s
 * against a 1 s stale window it never does, and a crash simulation then changes nothing about the outcome.
 */
@DisplayName("SessionRouter holder loss for a collected forward")
class SessionRouterDrainedForwardHolderLossTest {

    private static final String KEY = "k-drained";

    /** Short enough that the renewal tick — and so the idempotency touch — fires many times inside a test. */
    private static final Duration RENEW_FAST = Duration.ofMillis(200);

    /** The sweeper's staleness window on the observing node, and the cadence it scans at. */
    private static final Duration STALE_AFTER = Duration.ofSeconds(1);
    private static final Duration SWEEP_FAST = Duration.ofMillis(200);

    /** Long enough that a node whose touches stopped would have been swept several times over. */
    private static final Duration LONGER_THAN_STALE = Duration.ofSeconds(3);

    /** Given to a node that must not sweep, so a test observes only the sweeper it means to. */
    private static final Duration NEVER_SWEEPS = Duration.ofMinutes(5);

    private SessionLeaseStore leaseStore;
    private SessionSignalBus bus;
    private SessionInbox inbox;
    private SessionRecordStore repository;

    private final List<TestManagerHarness> nodes = new ArrayList<>();
    private final List<SessionSignalBus.Subscription> taps = new ArrayList<>();

    @BeforeEach
    void wireSharedBackend() {
        leaseStore = new InMemorySessionLeaseStore();
        bus = new InMemorySignalBus();
        inbox = new InMemorySessionInbox();
        repository = new InMemorySessionRecordStore();
    }

    @AfterEach
    void closeNodes() {
        for (SessionSignalBus.Subscription tap : taps) {
            tap.close();
        }
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
        // The take-over must not cost the entry its ordinary ending: a turn this node does hold the reservation for
        // still caches its result rather than leaving a reservation named after a node that has moved on.
        assertThat(entry(idempotency).getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
    }

    @Test
    @DisplayName("a healthy drainer's turn outlasting the stale window is not swept, because the renewer touches it")
    void aHealthyDrainersLongTurnIsNotSweptAsALostHolder() throws Exception {
        final IdempotencyStore idempotency = new InMemoryIdempotencyStore();
        // node-A renews often enough for the idempotency touch that rides on renewal to actually fire; node-B watches
        // with a one-second staleness window. Nothing here is crashed: the whole point is that nothing should happen.
        final TestManagerHarness holder = node("node-A", idempotency,
                b -> b.lockExtendInterval(RENEW_FAST).holderLossSweepInterval(NEVER_SWEEPS));
        final TestManagerHarness peer = node("node-B", idempotency,
                b -> b.holderLossSweepInterval(SWEEP_FAST).idempotencySecondaryTtl(STALE_AFTER));
        final SessionId id = SessionId.of("c-drained-3");

        holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(keyed(id, "second"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).as("the queued message has to be out of the inbox and running").isTrue();

        // An ordinary LLM turn runs far longer than the thirty-second default secondary TTL, so this is the common
        // case rather than an edge one. Taking the reservation over without also keeping it alive would turn every
        // such turn into a reported holder loss: the peer resets the key, the caller is failed, and its retry finds
        // the key free and executes the same request a second time while the original turn is still running.
        assertStaysPending(forwarded.getFuture(), LONGER_THAN_STALE);

        final IdempotencyEntry stillRunning = entry(idempotency);
        assertThat(stillRunning.getStatus()).as("the reservation has to have survived, not merely the future")
                .isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(stillRunning.getHolderId()).isPresent();

        session.completeCurrentTurn(TestLiveSession.ok("second-done"));
        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("second-done");
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
        // node-A renews fast, so while it is alive its touches really are what keeps the reservation fresh — without
        // that the sweep below would fire on its own timer and this test would pass with the "crash" never happening.
        // Only node-B sweeps: a dead node runs no scheduled task, and letting node-A sweep would let it report a loss
        // it is supposed to be unable to notice.
        final TestManagerHarness holder = node("node-A", idempotency,
                b -> b.lockExtendInterval(RENEW_FAST).holderLossSweepInterval(NEVER_SWEEPS));
        final TestManagerHarness peer = node("node-B", idempotency,
                b -> b.holderLossSweepInterval(SWEEP_FAST).idempotencySecondaryTtl(STALE_AFTER));
        final SessionId id = SessionId.of("c-drained-2");
        final List<Map<String, Object>> announced = tapTurnResults(id);

        holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(keyed(id, "second"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).as("the queued message has to be out of the inbox and running").isTrue();
        assertThat(entry(idempotency).getHolderId()).isPresent();

        // The precondition that makes the crash below load-bearing rather than decorative: while node-A is alive the
        // sweep must not fire, so whatever fires after the flip fired *because of* the flip.
        assertStaysPending(forwarded.getFuture(), LONGER_THAN_STALE);

        // node-A stops renewing here, with the turn still in flight and the message long gone from the inbox — the
        // one shape the orphaned-forward doorbell retry cannot help with, because there is nothing left to re-announce.
        drainerAlive.set(false);

        // The announcement is the deterministic half: the sweeper publishes it on every detection. The message the
        // caller ends up with is not, quite — node-B's own forward poll runs every second, and one landing in the
        // sub-millisecond window between compareAndReset deleting the entry and announceHolderLost completing the
        // future would read the key as absent and fail it as "lost its idempotency reservation" instead. Both mean
        // recovery rather than a five-minute deadline, so the claim that must not flake is made on the announcement.
        assertThat(awaitHolderLostAnnouncement(announced)).as("the sweeper must announce the loss it detected")
                .isTrue();
        assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class);

        // Let the parked turn finish so the harnesses close without waiting out their shutdown grace. Its markDone
        // lands on a key the sweeper already reset, which is a no-op by design rather than an error.
        session.completeCurrentTurn(TestLiveSession.ok("second-done"));
    }

    @Test
    @DisplayName("a turn whose take-over was refused does not overwrite the cached answer it was refused for")
    void aRefusedTakeOverDoesNotOverwriteTheCachedAnswer() throws Exception {
        final IdempotencyStore idempotency = new InMemoryIdempotencyStore();
        final TestManagerHarness holder = node("node-A", idempotency, b -> b.holderLossSweepInterval(NEVER_SWEEPS));
        final TestManagerHarness peer = node("node-B", idempotency,
                b -> b.holderLossSweepInterval(NEVER_SWEEPS).idempotencySecondaryTtl(STALE_AFTER));
        final SessionId id = SessionId.of("c-drained-4");

        holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(keyed(id, "second"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // Stands in for the sequence #19 exists because of, compressed: the queued message went uncollected past its
        // forward deadline, the client retried the key, that retry ran somewhere and cached its answer. Planted
        // rather than played out because the real version needs a five-minute TTL to lapse — the mechanism under test
        // is what the drain pass does when it finally reaches a message whose key is already DONE.
        idempotency.markDone(KEY, TestLiveSession.ok("answer-the-client-got"));
        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .as("the waiting caller is answered from the cache, as a replay").isEqualTo("answer-the-client-got");

        // Now the original message is drained. acquireHolder refuses the DONE entry, and the message runs anyway
        // because it is already out of the at-most-once inbox and no successor could recover it.
        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).as("a refused take-over must not stop the message from running").isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("answer-nobody-asked-for"));

        // markDone matches on the key alone in every backend, so nothing but this node declining to call it keeps the
        // duplicate's answer out of the cache. Letting it land would replace an answer a client has already been
        // given with one it will never see, and every later replay of the key would return the wrong one.
        final IdempotencyEntry cached = awaitCachedAnswer(idempotency, "answer-the-client-got");
        assertThat(cached.getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
        assertThat(cached.getResult().orElseThrow().getFinalAnswer()).isEqualTo("answer-the-client-got");
    }

    @Test
    @DisplayName("a doorbell drain takes the reservation over too, not just the holder's post-turn re-collect")
    void aDoorbellDrainAlsoTakesOverTheReservation() throws Exception {
        final IdempotencyStore idempotency = new InMemoryIdempotencyStore();
        // The forward poll is what re-rings the doorbell, and its interval is derived from the secondary TTL.
        final TestManagerHarness node = node("node-A", idempotency,
                b -> b.holderLossSweepInterval(NEVER_SWEEPS).idempotencySecondaryTtl(STALE_AFTER));
        final SessionId id = SessionId.of("c-drained-6");

        // A lease held by a node that is gone. Nothing releases it — it lapses on its own TTL — so no holder will ever
        // re-collect this message and the pass that finally runs it is runDrainOnly, whose take-over travels a
        // different argument list from runTurnLoop's and is therefore separately breakable.
        leaseStore.tryAcquire(id, "dead-node", Duration.ofMillis(400));

        final SubmitDisposition forwarded = node.manager().submit(keyed(id, "queued"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(entry(idempotency).getHolderId()).isEmpty();

        final TestLiveSession session = awaitSession(node, id);
        assertThat(session.awaitTurnStarted()).isTrue();
        assertThat(entry(idempotency).getHolderId())
                .as("a doorbell pass runs a real turn, so it owes the same visibility as a submitted one").isPresent();
        assertThat(entry(idempotency).getHolderId().orElseThrow()).startsWith("node-A/");

        session.completeCurrentTurn(TestLiveSession.ok("queued-done"));
        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("queued-done");
        assertThat(awaitStatus(idempotency, IdempotencyEntry.Status.DONE).getStatus())
                .isEqualTo(IdempotencyEntry.Status.DONE);
    }

    @Test
    @DisplayName("a take-over that could not read the store still caches its result — nothing said the entry was not ours")
    void aTakeOverThatCouldNotReadTheStoreStillCachesItsResult() throws Exception {
        // One connection reset, landing on acquireHolder and nowhere else. By the time the turn ends the store is
        // healthy again, so markDone would succeed — the question is only whether it is attempted.
        final AtomicBoolean blipArmed = new AtomicBoolean(true);
        final IdempotencyStore idempotency = new BlipOnAcquireIdempotencyStore(new InMemoryIdempotencyStore(),
                blipArmed);
        final TestManagerHarness holder = node("node-A", idempotency, b -> b.holderLossSweepInterval(NEVER_SWEEPS));
        final TestManagerHarness peer = node("node-B", idempotency, b -> b.holderLossSweepInterval(NEVER_SWEEPS));
        final SessionId id = SessionId.of("c-drained-5");

        holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(keyed(id, "second"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).isTrue();
        assertThat(blipArmed.get()).as("the blip must actually have been spent on the take-over").isFalse();
        session.completeCurrentTurn(TestLiveSession.ok("second-done"));

        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("second-done");

        // Withholding the cache write is safe only where the store *said* the entry belongs to someone else. A store
        // that threw said nothing, and treating silence as refusal leaves a turn that succeeded looking unfinished:
        // the entry stays holderless IN_FLIGHT for the whole forward TTL, invisible to the sweeper because it names
        // nobody, so a node that missed the rail polls it for five minutes and then times out on a turn that
        // succeeded four minutes earlier — while every retry in that window attaches to the dead reservation.
        final IdempotencyEntry cached = awaitStatus(idempotency, IdempotencyEntry.Status.DONE);
        assertThat(cached.getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
        assertThat(cached.getResult().orElseThrow().getFinalAnswer()).isEqualTo("second-done");
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

    /**
     * Fails the moment the forward resolves, and otherwise returns after {@code dwell}. Polling rather than sleeping
     * so a regression reports at the point it happens instead of at the end of the window.
     */
    private static void assertStaysPending(CompletionStage<AgentExecutionResult> stage, Duration dwell)
            throws InterruptedException {
        final CompletableFuture<AgentExecutionResult> future = stage.toCompletableFuture();
        final long deadline = System.currentTimeMillis() + dwell.toMillis();
        while (System.currentTimeMillis() < deadline) {
            assertThat(future.isDone())
                    .as("the forward was resolved while its drainer was healthy and still running the turn").isFalse();
            Thread.sleep(25L);
        }
    }

    private static IdempotencyEntry awaitStatus(IdempotencyStore store, IdempotencyEntry.Status expected)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            final Optional<IdempotencyEntry> found = store.find(KEY);
            if (found.isPresent() && found.get().getStatus() == expected) {
                return found.orElseThrow();
            }
            Thread.sleep(25L);
        }
        return entry(store);
    }

    private static IdempotencyEntry awaitCachedAnswer(IdempotencyStore store, String expected)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            final Optional<IdempotencyEntry> found = store.find(KEY);
            if (found.isPresent()
                    && !expected.equals(found.get().getResult().map(r -> r.getFinalAnswer()).orElse(null))) {
                // Report the overwrite where it happens rather than after the wait.
                return found.orElseThrow();
            }
            Thread.sleep(25L);
        }
        return entry(store);
    }

    private List<Map<String, Object>> tapTurnResults(SessionId id) {
        final List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
        taps.add(bus.subscribe(id, signal -> {
            if (signal.getKind() == SessionSignal.SignalKind.TURN_RESULT) {
                seen.add(signal.getPayload());
            }
        }));
        return seen;
    }

    private static boolean awaitHolderLostAnnouncement(List<Map<String, Object>> announced)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (Map<String, Object> payload : announced) {
                if ("HOLDER_LOST".equals(payload.get("outcome")) && KEY.equals(payload.get("idem"))) {
                    return true;
                }
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static Throwable awaitFailure(CompletionStage<AgentExecutionResult> stage) throws Exception {
        try {
            stage.toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            return ee.getCause() != null ? ee.getCause() : ee;
        }
        throw new AssertionError("Expected the forwarded future to fail, but it completed");
    }

    /** Forwards every operation to a delegate, so a subclass overrides only the one it means to disturb. */
    private abstract static class DelegatingIdempotencyStore implements IdempotencyStore {

        private final IdempotencyStore delegate;

        DelegatingIdempotencyStore(IdempotencyStore delegate) {
            this.delegate = delegate;
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

        @Override
        public List<IdempotencyEntry> findStaleInFlight(Instant cutoff) {
            return delegate.findStaleInFlight(cutoff);
        }
    }

    /** Stops applying {@code touch} once the node holding the turn is declared dead. */
    private static final class CrashableIdempotencyStore extends DelegatingIdempotencyStore {

        private final AtomicBoolean alive;

        CrashableIdempotencyStore(IdempotencyStore delegate, AtomicBoolean alive) {
            super(delegate);
            this.alive = alive;
        }

        @Override
        public boolean touch(String key, String holderId) {
            return alive.get() && super.touch(key, holderId);
        }
    }

    /** Fails the first {@code acquireHolder} — one connection reset, precisely placed. */
    private static final class BlipOnAcquireIdempotencyStore extends DelegatingIdempotencyStore {

        private final AtomicBoolean armed;

        BlipOnAcquireIdempotencyStore(IdempotencyStore delegate, AtomicBoolean armed) {
            super(delegate);
            this.armed = armed;
        }

        @Override
        public boolean acquireHolder(String key, String holderId, Duration ttl) {
            if (armed.getAndSet(false)) {
                throw new IllegalStateException("simulated transient idempotency backend blip");
            }
            return super.acquireHolder(key, holderId, ttl);
        }
    }

}
