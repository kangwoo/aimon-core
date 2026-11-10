package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.admission.TurnAdmission;
import at.aimon.session.routing.exception.NodeDrainingException;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Drain mode (routing.md §8.4): a node stops starting turns, keeps the ones it has, and hands its sessions to peers —
 * without closing anything.
 *
 * <p>
 * Two harnesses over one set of shared in-memory SPIs stand in for two cluster nodes, as in
 * {@code SessionRouterDrainingHandoffTest}, which covers the same hand-off mechanism under {@code closeGracefully}.
 */
@DisplayName("SessionRouter drain mode")
class SessionRouterDrainModeTest {

    private SessionLeaseStore leaseStore;
    private SessionSignalBus bus;
    private SessionInbox inbox;
    private SessionRecordStore repository;
    private IdempotencyStore idempotency;

    private final List<TestManagerHarness> nodes = new ArrayList<>();
    private final List<SessionSignalBus.Subscription> taps = new ArrayList<>();

    @BeforeEach
    void wireSharedBackend() {
        leaseStore = new InMemorySessionLeaseStore();
        bus = new InMemorySignalBus();
        inbox = new InMemorySessionInbox();
        repository = new InMemorySessionRecordStore();
        idempotency = new InMemoryIdempotencyStore();
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
    @DisplayName("no turn starts uncounted when beginDrain races concurrent submits")
    void beginDrainRacesConcurrentSubmits() throws Exception {
        final TestManagerHarness node = node("node-A");
        final Map<SessionId, SubmitDisposition> dispositions = new ConcurrentHashMap<>();
        final AtomicInteger local = new AtomicInteger();
        final AtomicInteger forwardedCount = new AtomicInteger();
        final AtomicInteger seq = new AtomicInteger();
        final AtomicBoolean stop = new AtomicBoolean();
        final List<Thread> submitters = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            final Thread thread = new Thread(() -> {
                while (!stop.get()) {
                    final SessionId id = SessionId.of("c-race-" + seq.incrementAndGet());
                    final SubmitDisposition d = node.manager().submit(RequestFixtures.submit(id, "alpha", "hi"));
                    dispositions.put(id, d);
                    if (d.getKind() == SubmitDisposition.Kind.EXECUTED_LOCALLY) {
                        local.incrementAndGet();
                    } else {
                        forwardedCount.incrementAndGet();
                    }
                }
            }, "submitter-" + t);
            thread.setDaemon(true);
            submitters.add(thread);
            thread.start();
        }
        awaitTrue(() -> local.get() >= 10, "some turns must start before the drain");

        final DrainHandle handle = node.manager().beginDrain();
        // Keep submitting until the drain has visibly turned at least one submit away, so the assertions below never
        // depend on how the scheduler happened to interleave the stop with the flag.
        awaitTrue(() -> forwardedCount.get() >= 1, "some submit must reach the draining node");
        stop.set(true);
        for (Thread thread : submitters) {
            thread.join(TestLiveSession.DEFAULT_AWAIT_MS);
        }

        final List<SessionId> ranHere = new ArrayList<>();
        final List<SessionId> forwarded = new ArrayList<>();
        dispositions.forEach(
                (id, d) -> (d.getKind() == SubmitDisposition.Kind.EXECUTED_LOCALLY ? ranHere : forwarded).add(id));
        assertThat(forwarded).as("submits after beginDrain are forwarded, never run here").isNotEmpty();
        for (SessionId id : forwarded) {
            assertThat(node.session(id)).as("a forwarded submit must not open a session here: %s", id).isNull();
        }
        // Every turn that started is still blocked inside its session, so each one must be counted: a turn that
        // started without being counted would show up here as a count lower than the turns actually running.
        for (SessionId id : ranHere) {
            assertThat(node.session(id).awaitTurnInFlight()).isTrue();
        }
        awaitTrue(() -> handle.snapshot().getInFlightTurns() == ranHere.size(),
                "every started turn must be counted in flight");
        assertThat(handle.awaitIdle(Duration.ofMillis(100))).isFalse();

        for (SessionId id : ranHere) {
            completeWhenReady(node.session(id), TestLiveSession.ok("done"));
        }
        assertThat(handle.awaitIdle(Duration.ofSeconds(5))).as(String.valueOf(handle.snapshot())).isTrue();
        for (SessionId id : forwarded) {
            assertThat(node.session(id)).as("nothing may start here once the node is quiescent").isNull();
        }
        for (SessionId id : ranHere) {
            assertThat(leaseStore.findHolder(id)).as("each session's lease went back when its turn ended").isEmpty();
        }
    }

    @Test
    @DisplayName("awaitIdle times out without interrupting; the turn completes, persists and keeps its lease")
    void quiescenceTimeoutDoesNotInterrupt() throws Exception {
        final TestManagerHarness node = TestManagerHarness.builder().nodeId("node-A").leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency)
                .lockLease(Duration.ofSeconds(2)).lockExtendInterval(Duration.ofMillis(300)).build();
        nodes.add(node);
        final SessionId id = SessionId.of("c-long");
        final SubmitDisposition d = node.manager().submit(SubmitRequest.builder().sessionId(id).agentRef("alpha")
                .userInput("long").initiator(Principal.user("tester")).idempotencyKey("k-long").build());
        final TestLiveSession session = awaitSession(node, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final DrainHandle handle = node.manager().beginDrain();
        assertThat(handle.awaitIdle(Duration.ofMillis(300))).isFalse();
        // Past the lease's whole length (2s, renewed every 300ms): the turn's lease must still be renewed while the
        // node
        // drains.
        Thread.sleep(2_500L);
        assertThat(leaseStore.findHolder(id).map(LeaseHolder::getHolderId)).contains("node-A");
        assertThat(session.recordedInterrupts()).as("waiting must never interrupt").isEmpty();
        assertThat(session.isClosed()).isFalse();
        assertThat(handle.snapshot().getInFlightTurns()).isEqualTo(1);
        assertThat(handle.snapshot().getHeldLeases()).isEqualTo(1);

        completeWhenReady(session, TestLiveSession.ok("long-done"));
        assertThat(d.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("long-done");
        assertThat(handle.awaitIdle(Duration.ofSeconds(5))).as(String.valueOf(handle.snapshot())).isTrue();
        assertThat(idempotency.find("k-long").map(IdempotencyEntry::getStatus)).as("the result is persisted as usual")
                .contains(IdempotencyEntry.Status.DONE);
        assertThat(leaseStore.findHolder(id)).as("the lease goes back once the turn ends").isEmpty();
        assertThat(session.isClosed()).as("the session is handed over, not kept for a next turn").isTrue();
        assertThat(node.manager().closeGracefully(Duration.ofSeconds(1))).as("close still works after a drain")
                .isTrue();
    }

    @Test
    @DisplayName("an idle held session is handed over on drain, its inbox kept, and a peer collects it")
    void idleSessionIsHandedOverAndPeerCollects() throws Exception {
        final TestManagerHarness departing = node("node-A");
        final TestManagerHarness successor = node("node-B");
        final SessionId id = SessionId.of("c-idle");
        final List<SessionSignal.SignalKind> kinds = tapKinds(id);

        final SubmitDisposition first = departing.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession held = awaitSession(departing, id);
        completeWhenReady(held, TestLiveSession.ok("first-done"));
        first.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);
        awaitTrue(() -> leaseStore.findHolder(id).map(LeaseHolder::getHolderId).filter("node-A"::equals).isPresent(),
                "node-A keeps the lease of its idle session");
        final CountDownLatch observerCompleted = observe(departing, id);
        successor.manager().events(id);

        // Queued without a doorbell: only the drain's own relay can tell a peer to look.
        final TurnId queuedTurn = TurnId.generate();
        inbox.deliver(queued(id, "queued", queuedTurn));

        final DrainHandle handle = departing.manager().beginDrain();
        final TestLiveSession taken = awaitSession(successor, id);
        assertThat(taken.awaitTurnStarted()).isTrue();
        completeWhenReady(taken, TestLiveSession.ok("queued-done"));

        assertThat(handle.awaitIdle(Duration.ofSeconds(5))).as(String.valueOf(handle.snapshot())).isTrue();
        assertThat(taken.submittedInputs()).containsExactly("queued");
        assertThat(taken.submittedTurnIds()).containsExactly(queuedTurn);
        assertThat(held.submittedInputs()).as("the draining node ran nothing new").containsExactly("first");
        assertThat(held.isClosed()).isTrue();
        assertThat(kinds).as("a hand-over is not a release: no EVICT").doesNotContain(SessionSignal.SignalKind.EVICT);
        assertThat(observerCompleted.getCount()).as("observers keep their stream").isEqualTo(1L);
        awaitTrue(() -> inbox.isEmpty(id), "the peer collected the queued message");
    }

    @Test
    @DisplayName("a session with a running turn hands over when that turn ends")
    void runningSessionHandsOverAfterItsTurn() throws Exception {
        final TestManagerHarness departing = node("node-A");
        final TestManagerHarness successor = node("node-B");
        final SessionId id = SessionId.of("c-running");

        final SubmitDisposition running = departing.manager().submit(RequestFixtures.submit(id, "alpha", "running"));
        final TestLiveSession first = awaitSession(departing, id);
        assertThat(first.awaitTurnStarted()).isTrue();

        final DrainHandle handle = departing.manager().beginDrain();
        final SubmitDisposition queued = successor.manager().submit(RequestFixtures.submit(id, "alpha", "queued"));
        assertThat(queued.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        Thread.sleep(200L);
        assertThat(successor.session(id)).as("the session is still node-A's while its turn runs").isNull();
        assertThat(handle.awaitIdle(Duration.ZERO)).isFalse();

        completeWhenReady(first, TestLiveSession.ok("running-done"));
        assertThat(running.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("running-done");

        final TestLiveSession second = awaitSession(successor, id);
        assertThat(second.awaitTurnStarted()).isTrue();
        completeWhenReady(second, TestLiveSession.ok("queued-done"));
        assertThat(queued.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("queued-done");
        assertThat(first.submittedInputs()).as("node-A did not collect the queued message").containsExactly("running");
        assertThat(handle.awaitIdle(Duration.ofSeconds(5))).as(String.valueOf(handle.snapshot())).isTrue();
        assertThat(departing.manager().isDraining()).isTrue();
    }

    @Test
    @DisplayName("a draining single-node router refuses a submit with NodeDrainingException before reserving anything")
    void singleNodeRefusesWhileDraining() {
        final TestManagerHarness node = TestManagerHarness.builder().nodeId("node-A").leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency)
                .forwardSubmitsWhileDraining(false).build();
        nodes.add(node);
        final SessionId id = SessionId.of("c-single");
        node.manager().beginDrain();

        assertThatThrownBy(() -> node.manager()
                .submit(SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("hi")
                        .initiator(Principal.user("tester")).idempotencyKey("k-single").build()))
                .isInstanceOf(NodeDrainingException.class).hasMessageContaining("node-A");
        assertThat(node.session(id)).isNull();
        assertThat(inbox.isEmpty(id)).isTrue();
        assertThat(idempotency.find("k-single")).as("a refused submit leaves its key free for a retry elsewhere")
                .isEmpty();
    }

    @Test
    @DisplayName("the SINGLE_NODE builder wires the refusing behaviour")
    void singleNodeBuilderRefuses() throws InterruptedException {
        final SessionRouter router = SessionRouter.builder()
                .sessionOpener((id, runtime, options, attrs) -> new TestLiveSession(id))
                .sessionRecordStore(new InMemorySessionRecordStore()).build();
        try {
            final DrainHandle handle = router.beginDrain();
            assertThat(router.beginDrain()).as("idempotent: the same handle").isSameAs(handle);
            assertThat(handle.awaitIdle(Duration.ZERO)).isTrue();
            assertThatThrownBy(() -> router.submit(RequestFixtures.submit(SessionId.of("c-b"), "alpha", "hi")))
                    .isInstanceOf(NodeDrainingException.class);
        } finally {
            router.close();
        }
    }

    @Test
    @DisplayName("a drain that begins between admission and the count stops the submit: forwarded, permit released once")
    void drainBetweenAdmissionAndCountDefers() throws Exception {
        final AtomicReference<SessionRouter> router = new AtomicReference<>();
        final List<AtomicInteger> releases = new CopyOnWriteArrayList<>();
        // Admits, but begins the drain first — the one interleaving the post-count re-read exists for, made
        // deterministic: admitHere has already read "not draining", and the turn is not counted yet.
        final TurnAdmission drainingGate = (sessionId, path) -> {
            router.get().beginDrain();
            final AtomicInteger released = new AtomicInteger();
            releases.add(released);
            return Optional.of(released::incrementAndGet);
        };
        final TestManagerHarness node = TestManagerHarness.builder().nodeId("node-A").leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency)
                .turnAdmission(drainingGate).build();
        nodes.add(node);
        router.set(node.manager());
        final SessionId id = SessionId.of("c-post-admit");

        final SubmitDisposition d = node.manager().submit(RequestFixtures.submit(id, "alpha", "hi"));

        assertThat(d.getKind()).as("the turn must not run here once draining is seen after the count")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(node.session(id)).as("nothing opened locally").isNull();
        assertThat(releases).as("admitted exactly once").hasSize(1);
        assertThat(releases.get(0).get()).as("the permit is released exactly once").isEqualTo(1);
        assertThat(leaseStore.findHolder(id)).as("the lease claimed for the turn went back").isEmpty();
        assertThat(inbox.isEmpty(id)).as("the input waits in the inbox for a peer").isFalse();
        assertThat(node.manager().beginDrain().snapshot().getInFlightTurns()).isZero();
    }

    @Test
    @DisplayName("a draining single node runs a message queued behind its running turn instead of stranding it")
    void singleNodeRunsWorkQueuedBehindARunningTurn() throws Exception {
        final TestManagerHarness node = singleNode(TurnAdmission.alwaysAdmit());
        final SessionId id = SessionId.of("c-single-queued");

        final SubmitDisposition running = node.manager().submit(RequestFixtures.submit(id, "alpha", "running"));
        final TestLiveSession session = awaitSession(node, id);
        assertThat(session.awaitTurnInFlight()).isTrue();
        final SubmitDisposition queued = node.manager().submit(RequestFixtures.submit(id, "alpha", "queued"));
        assertThat(queued.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        final DrainHandle handle = node.manager().beginDrain();
        assertThatThrownBy(() -> node.manager().submit(RequestFixtures.submit(id, "alpha", "late")))
                .as("new submits are still refused").isInstanceOf(NodeDrainingException.class);
        session.completeCurrentTurn(TestLiveSession.ok("running-done"));

        // The pass keeps collecting: the queued message runs here, in the same pass, because no peer exists to take it.
        assertThat(session.awaitTurnCount(2)).as("the queued message runs here").isTrue();
        assertThat(handle.awaitIdle(Duration.ofMillis(100))).as("a queued turn still running is not idle").isFalse();
        completeWhenReady(session, TestLiveSession.ok("queued-done"));
        assertThat(running.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("running-done");
        assertThat(queued.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("queued-done");
        assertThat(session.submittedInputs()).containsExactly("running", "queued");
        assertThat(handle.awaitIdle(Duration.ofSeconds(5))).as(String.valueOf(handle.snapshot())).isTrue();
        assertThat(inbox.isEmpty(id)).isTrue();
    }

    @Test
    @DisplayName("a draining single node runs a message its gate deferred before the drain, once the gate frees")
    void singleNodeRunsAdmissionDeferredWork() throws Exception {
        final AtomicBoolean allow = new AtomicBoolean(false);
        final TestManagerHarness node = singleNode((sessionId, path) -> allow.get() ? Optional.of(() -> {
        }) : Optional.empty());
        final SessionId id = SessionId.of("c-single-deferred");

        final SubmitDisposition deferred = node.manager().submit(RequestFixtures.submit(id, "alpha", "deferred"));
        assertThat(deferred.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        final DrainHandle handle = node.manager().beginDrain();
        assertThat(handle.snapshot().isForwardsRunHere()).isTrue();
        assertThat(handle.awaitIdle(Duration.ofMillis(200)))
                .as("a queued turn only this node can run keeps a single node from being idle").isFalse();

        allow.set(true);
        // The forward's poll (1s here) asks the gate again and runs the message locally.
        final TestLiveSession session = awaitSession(node, id);
        completeWhenReady(session, TestLiveSession.ok("deferred-done"));
        assertThat(deferred.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("deferred-done");
        assertThat(handle.awaitIdle(Duration.ofSeconds(5))).as(String.valueOf(handle.snapshot())).isTrue();
    }

    @Test
    @DisplayName("awaitIdle with a timeout too long for nanoseconds waits rather than throwing")
    void hugeTimeoutDoesNotOverflow() throws Exception {
        final TestManagerHarness node = node("node-A");
        final DrainHandle handle = node.manager().beginDrain();
        assertThat(handle.awaitIdle(Duration.ofSeconds(Long.MAX_VALUE))).isTrue();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------------------------------

    private TestManagerHarness node(String nodeId) {
        final TestManagerHarness harness = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency).build();
        nodes.add(harness);
        return harness;
    }

    private TestManagerHarness singleNode(TurnAdmission admission) {
        final TestManagerHarness harness = TestManagerHarness.builder().nodeId("node-A").leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency)
                .idempotencySecondaryTtl(Duration.ofSeconds(2)).forwardSubmitsWhileDraining(false)
                .turnAdmission(admission).build();
        nodes.add(harness);
        return harness;
    }

    private static InboundMessage queued(SessionId id, String input, TurnId turnId) {
        return InboundMessage.builder().sessionId(id).agentRef("alpha").userInput(input).turnId(turnId)
                .priority(QueuedInputPriority.LATER).initiator(Principal.user("tester")).deliveredAt(Instant.now())
                .build();
    }

    private List<SessionSignal.SignalKind> tapKinds(SessionId id) {
        final List<SessionSignal.SignalKind> seen = new CopyOnWriteArrayList<>();
        taps.add(bus.subscribe(id, signal -> seen.add(signal.getKind())));
        return seen;
    }

    private static CountDownLatch observe(TestManagerHarness harness, SessionId id) {
        final CountDownLatch completed = new CountDownLatch(1);
        harness.manager().events(id).subscribe(new Flow.Subscriber<AgentExecutionEvent>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentExecutionEvent item) {
                // not inspected
            }

            @Override
            public void onError(Throwable throwable) {
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });
        return completed;
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    static TestLiveSession awaitSession(TestManagerHarness harness, SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("a session for %s should have been opened", id).isNotNull();
        return session;
    }

    static void completeWhenReady(TestLiveSession session, AgentExecutionResult result) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                session.completeCurrentTurn(result);
                return;
            } catch (IllegalStateException notYet) {
                Thread.sleep(10L);
            }
        }
        throw new AssertionError("no turn ever became completable");
    }
}
