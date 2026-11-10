package at.aimon.session.routing;

import static at.aimon.session.routing.SessionRouterDrainModeTest.awaitSession;
import static at.aimon.session.routing.SessionRouterDrainModeTest.completeWhenReady;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
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
import at.aimon.core.base.Principal;
import at.aimon.session.routing.admission.TurnAdmission;
import at.aimon.session.routing.admission.TurnAdmissionPermit;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Node turn admission (routing.md §8.4): the gate is asked before anything is claimed or collected, a deferral loses
 * nothing and hands the session to a peer, and every granted permit is released exactly once.
 */
@DisplayName("SessionRouter turn admission")
class SessionRouterTurnAdmissionTest {

    private SessionLeaseStore leaseStore;
    private SessionSignalBus bus;
    private SessionInbox inbox;
    private SessionRecordStore repository;
    private IdempotencyStore idempotency;

    private final List<TestManagerHarness> nodes = new ArrayList<>();

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
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).close();
        }
    }

    @Test
    @DisplayName("a holder whose gate defers leaves the queued message in the inbox and a peer runs it")
    void deferBeforeCollectKeepsTheMessageForAPeer() throws Exception {
        final RecordingAdmission gate = new RecordingAdmission();
        final TestManagerHarness holder = node("node-A", gate);
        final TestManagerHarness peer = node("node-B", TurnAdmission.alwaysAdmit());
        final SessionId id = SessionId.of("c-defer-inbox");

        final SubmitDisposition first = holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession held = awaitSession(holder, id);
        completeWhenReady(held, TestLiveSession.ok("first-done"));
        first.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);
        awaitTrue(() -> leaseStore.findHolder(id).map(LeaseHolder::getHolderId).filter("node-A"::equals).isPresent(),
                "node-A holds the idle session");
        peer.manager().events(id);

        gate.allow.set(false);
        final TurnId queuedTurn = TurnId.generate();
        inbox.deliver(InboundMessage.builder().sessionId(id).agentRef("alpha").userInput("queued").turnId(queuedTurn)
                .priority(QueuedInputPriority.LATER).initiator(Principal.user("tester")).deliveredAt(Instant.now())
                .build());
        bus.publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.MESSAGE_ENQUEUED)
                .originNodeId("node-Z").build());

        final TestLiveSession taken = awaitSession(peer, id);
        assertThat(taken.awaitTurnStarted()).isTrue();
        completeWhenReady(taken, TestLiveSession.ok("queued-done"));

        assertThat(taken.submittedTurnIds()).containsExactly(queuedTurn);
        assertThat(held.submittedInputs()).as("the deferring holder collected nothing").containsExactly("first");
        assertThat(gate.paths).contains(TurnAdmission.Path.SUBMIT, TurnAdmission.Path.INBOX);
        awaitTrue(() -> inbox.isEmpty(id), "the peer collected the message");
        gate.assertEveryPermitReleasedOnce();
    }

    @Test
    @DisplayName("a deferred submit is queued instead of run, and a peer with capacity answers it")
    void deferredSubmitIsForwardedToAPeer() throws Exception {
        final RecordingAdmission gate = new RecordingAdmission();
        gate.allow.set(false);
        final TestManagerHarness full = node("node-A", gate);
        final TestManagerHarness peer = node("node-B", TurnAdmission.alwaysAdmit());
        final SessionId id = SessionId.of("c-defer-submit");
        peer.manager().events(id);

        final SubmitDisposition d = full.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(d.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        final TestLiveSession taken = awaitSession(peer, id);
        completeWhenReady(taken, TestLiveSession.ok("hello-done"));

        assertThat(d.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("hello-done");
        assertThat(taken.submittedTurnIds()).containsExactly(d.getTurnId());
        assertThat(full.session(id)).as("the full node opened nothing").isNull();
        assertThat(gate.granted).isEmpty();
    }

    @Test
    @DisplayName("a gate that throws is a deferral, not a lost message")
    void throwingGateDefers() throws Exception {
        final TestManagerHarness broken = node("node-A", (sessionId, path) -> {
            throw new IllegalStateException("gate down");
        });
        final TestManagerHarness peer = node("node-B", TurnAdmission.alwaysAdmit());
        final SessionId id = SessionId.of("c-throw");
        peer.manager().events(id);

        final SubmitDisposition d = broken.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(d.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        completeWhenReady(awaitSession(peer, id), TestLiveSession.ok("ok"));
        assertThat(d.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer()).isEqualTo("ok");
    }

    @Test
    @DisplayName("every permit is released exactly once: success, failure, interrupt, abandoned before start, drain pass")
    void permitsAreReleasedExactlyOnce() throws Exception {
        final RecordingAdmission gate = new RecordingAdmission();
        final TestManagerHarness node = node("node-A", gate);

        // Success.
        final SessionId ok = SessionId.of("c-permit-ok");
        final SubmitDisposition okTurn = node.manager().submit(RequestFixtures.submit(ok, "alpha", "ok"));
        completeWhenReady(awaitSession(node, ok), TestLiveSession.ok("done"));
        okTurn.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Failure.
        final SessionId failed = SessionId.of("c-permit-fail");
        final SubmitDisposition failTurn = node.manager().submit(RequestFixtures.submit(failed, "alpha", "boom"));
        final TestLiveSession failing = awaitSession(node, failed);
        assertThat(failing.awaitTurnInFlight()).isTrue();
        failing.failCurrentTurn(new IllegalStateException("boom"));
        assertThat(failTurn.getFuture().toCompletableFuture()).failsWithin(5, TimeUnit.SECONDS);

        // Interrupt: the fixture only records it, so the turn is then ended the way an interrupted turn ends.
        final SessionId stopped = SessionId.of("c-permit-interrupt");
        final SubmitDisposition stopTurn = node.manager().submit(RequestFixtures.submit(stopped, "alpha", "stop"));
        final TestLiveSession interrupted = awaitSession(node, stopped);
        assertThat(interrupted.awaitTurnInFlight()).isTrue();
        node.manager().interrupt(stopped, InterruptReason.USER_SIGINT);
        assertThat(interrupted.awaitInterrupt()).isTrue();
        interrupted.failCurrentTurn(new IllegalStateException("interrupted"));
        assertThat(stopTurn.getFuture().toCompletableFuture()).failsWithin(5, TimeUnit.SECONDS);

        // Abandoned before start: admitted, then the lease turns out to be an outsider's, so the submit is forwarded.
        final SessionId elsewhere = SessionId.of("c-permit-elsewhere");
        assertThat(leaseStore.tryAcquire(elsewhere, "outsider", Duration.ofSeconds(30))).isPresent();
        final SubmitDisposition forwarded = node.manager().submit(RequestFixtures.submit(elsewhere, "alpha", "queued"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(node.session(elsewhere)).isNull();

        // An inbox drain pass on a session nobody holds.
        final SessionId drained = SessionId.of("c-permit-drain");
        repository.provision(drained);
        node.manager().events(drained);
        inbox.deliver(InboundMessage.builder().sessionId(drained).agentRef("alpha").userInput("q")
                .turnId(TurnId.generate()).priority(QueuedInputPriority.LATER).initiator(Principal.user("tester"))
                .deliveredAt(Instant.now()).build());
        bus.publish(SessionSignal.builder().sessionId(drained).kind(SessionSignal.SignalKind.MESSAGE_ENQUEUED)
                .originNodeId("node-Z").build());
        completeWhenReady(awaitSession(node, drained), TestLiveSession.ok("q-done"));

        awaitTrue(() -> gate.outstanding() == 0, "every permit is eventually released");
        assertThat(gate.paths).contains(TurnAdmission.Path.INBOX);
        assertThat(gate.granted.size()).as("one permit per pass that was admitted").isGreaterThanOrEqualTo(5);
        Thread.sleep(100L);
        gate.assertEveryPermitReleasedOnce();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------------------------------

    private TestManagerHarness node(String nodeId, TurnAdmission admission) {
        final TestManagerHarness harness = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency)
                .turnAdmission(admission).build();
        nodes.add(harness);
        return harness;
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    /** A gate that can be switched off and counts how often each permit it granted was released. */
    private static final class RecordingAdmission implements TurnAdmission {
        final AtomicBoolean allow = new AtomicBoolean(true);
        final List<AtomicInteger> granted = new CopyOnWriteArrayList<>();
        final List<Path> paths = new CopyOnWriteArrayList<>();

        @Override
        public Optional<TurnAdmissionPermit> tryAdmit(SessionId sessionId, Path path) {
            paths.add(path);
            if (!allow.get()) {
                return Optional.empty();
            }
            final AtomicInteger releases = new AtomicInteger();
            granted.add(releases);
            return Optional.of(releases::incrementAndGet);
        }

        long outstanding() {
            return granted.stream().filter(r -> r.get() == 0).count();
        }

        void assertEveryPermitReleasedOnce() {
            for (AtomicInteger releases : granted) {
                assertThat(releases.get()).as("each permit is released exactly once").isEqualTo(1);
            }
        }
    }
}
