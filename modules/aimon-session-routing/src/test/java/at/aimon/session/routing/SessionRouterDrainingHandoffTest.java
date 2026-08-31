package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
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
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * What a node does with inbox work that reaches it while {@code closeGracefully} is draining (design §7.3).
 *
 * <p>
 * Before this stage the answer was "nothing at all", and that was the defect. A doorbell arriving after the shutdown
 * gate flipped was dispatched to a worker that either refused to start a pass or started one the shutdown was about to
 * tear down; every peer that heard the same {@code MESSAGE_ENQUEUED} had already found the session held by the
 * departing node and given up, and nothing re-rang. The message then waited for the session's next submission —
 * which may never come — while the node that queued it waited out its whole forward deadline.
 *
 * <p>
 * The design asked for {@code TURN_RESULT{error=NOT_HOLDER}} in answer, and the implementation splits that request in
 * two, because the messages involved are not in the same place:
 *
 * <ul>
 * <li>A message still <em>in</em> the at-most-once inbox is <b>handed over</b>, not failed: the departing node stops
 * holding the session and re-rings the doorbell. Failing it would resolve the submitter's future with "this never
 * ran" while leaving the message there for the next node to collect and run — a duplicate execution invented by the
 * very signal meant to prevent one.
 * <li>A message the node had <b>already collected</b> is out of the inbox, so no successor can ever run it. Once the
 * grace window is over, {@code NOT_HOLDER} is the only way its submitter finds out — and it says "resubmit this" where
 * {@code FAILED} would say "your input was attempted and threw".
 * </ul>
 *
 * <p>
 * Two managers over one set of shared SPIs stand in for two cluster nodes, as in
 * {@code SessionRouterForwardedTurnResultTest}.
 */
@DisplayName("SessionRouter draining hand-off")
class SessionRouterDrainingHandoffTest {

    private SessionLeaseStore leaseStore;
    private SessionSignalBus bus;
    private SessionInbox inbox;
    private SessionRecordStore repository;
    private IdempotencyStore idempotency;

    private final List<TestManagerHarness> nodes = new ArrayList<>();
    private final List<SessionSignalBus.Subscription> taps = new ArrayList<>();
    private final List<Thread> shutdowns = new ArrayList<>();

    @BeforeEach
    void wireSharedBackend() {
        leaseStore = new InMemorySessionLeaseStore();
        bus = new InMemorySignalBus();
        inbox = new InMemorySessionInbox();
        repository = new InMemorySessionRecordStore();
        idempotency = new InMemoryIdempotencyStore();
    }

    @AfterEach
    void closeNodes() throws InterruptedException {
        // The shutdown threads first: each is inside a closeGracefully that the test has already unblocked, and letting
        // it finish keeps its teardown from racing the one close() below would start.
        for (Thread shutdown : shutdowns) {
            shutdown.join(TestLiveSession.DEFAULT_AWAIT_MS);
        }
        for (SessionSignalBus.Subscription tap : taps) {
            tap.close();
        }
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).close();
        }
    }

    @Test
    @DisplayName("a message the node had already collected is answered NOT_HOLDER once the grace window is over")
    void alreadyCollectedMessagesAreAnsweredNotHolder() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-drain-1");
        final List<Map<String, Object>> announced = tapTurnResults(id);
        repository.provision(id);
        // Subscribed as an observer would be, which is all this node needs to hear a peer's doorbell. Deliberately
        // not a submit: the pass under test must be a drain-only one, so the queued messages are its only work.
        harness.manager().events(id);

        final TurnId first = TurnId.generate();
        final TurnId second = TurnId.generate();
        final Instant now = Instant.now();
        inbox.deliver(queued(id, "first", first, now));
        inbox.deliver(queued(id, "second", second, now.plusMillis(1)));
        ringDoorbell(id);

        final TestLiveSession session = awaitSession(harness, id);
        assertThat(session.awaitTurnStarted()).as("one pass must collect both messages and start the first").isTrue();

        // ZERO, so the first waitForDrain fails immediately and the forced path runs while the pass is still mid-queue.
        // Off-thread because the teardown that follows blocks on the very turn this test has not completed yet.
        startShutdown(harness, Duration.ZERO);
        assertThat(session.awaitInterrupt()).as("the interrupt is the observable edge of the forced close").isTrue();
        assertThat(session.recordedInterrupts()).contains(InterruptReason.SYSTEM_SHUTDOWN);

        // The interrupt only records here, so the turn still has to be completed for the pass to reach its next
        // message.
        completeWhenReady(session, TestLiveSession.ok("first-done"));

        awaitAnnouncements(announced, 2);
        assertThat(outcomeOf(announced, first)).as("a turn that finished inside the window keeps its result")
                .isEqualTo("RESULT");
        assertThat(outcomeOf(announced, second)).as("the sibling that never started is not reported as a failed input")
                .isEqualTo("NOT_HOLDER");
        assertThat(messageOf(announced, second)).contains("stopped holding session " + id.value());
        assertThat(session.submittedInputs()).as("no turn may start after the sessions have been interrupted")
                .containsExactly("first");
    }

    @Test
    @DisplayName("the grace window still runs messages the node had already collected")
    void theGraceWindowStillRunsAlreadyCollectedMessages() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-drain-2");
        final List<Map<String, Object>> announced = tapTurnResults(id);
        repository.provision(id);
        harness.manager().events(id);

        final TurnId first = TurnId.generate();
        final TurnId second = TurnId.generate();
        final Instant now = Instant.now();
        inbox.deliver(queued(id, "first", first, now));
        inbox.deliver(queued(id, "second", second, now.plusMillis(1)));
        ringDoorbell(id);

        final TestLiveSession session = awaitSession(harness, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // The whole pass is counted in inFlightTurns, so this is exactly the work the operator's grace window waits
        // on. Refusing it on the shutdown gate alone would make every graceful close abrupt.
        final AtomicBoolean drained = startShutdown(harness, Duration.ofSeconds(5));
        completeWhenReady(session, TestLiveSession.ok("first-done"));

        assertThat(session.awaitTurnCount(2)).as("the second collected message must still get its turn").isTrue();
        completeWhenReady(session, TestLiveSession.ok("second-done"));

        awaitAnnouncements(announced, 2);
        assertThat(outcomeOf(announced, first)).isEqualTo("RESULT");
        assertThat(outcomeOf(announced, second)).as("nothing was forced, so nothing may be reported NOT_HOLDER")
                .isEqualTo("RESULT");
        assertThat(session.submittedInputs()).containsExactly("first", "second");
        awaitShutdown();
        assertThat(drained).as("a close that ran every queued turn drained rather than timed out").isTrue();
    }

    @Test
    @DisplayName("a draining holder hands the conversation to a peer that is still serving traffic")
    void drainingHolderHandsTheConversationToAPeer() throws Exception {
        final TestManagerHarness departing = node("node-A");
        final TestManagerHarness successor = node("node-B");
        final SessionId id = SessionId.of("c-drain-3");
        final List<String> doorbells = tapDoorbells(id);

        final SubmitDisposition local = departing.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        final TestLiveSession first = awaitSession(departing, id);
        assertThat(first.awaitTurnStarted()).isTrue();

        startShutdown(departing, Duration.ofSeconds(5));
        awaitGateClosed(departing, SessionId.of("c-drain-3-probe"));

        // Node B cannot win a lock node A is holding, draining or not, so its submission goes to the inbox — and its
        // own doorbell finds the session held and gives up. Nothing on B will look again.
        final SubmitDisposition forwarded = successor.manager().submit(RequestFixtures.submit(id, "alpha", "queued"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(successor.session(id)).as("a forwarding node must not open a session of its own").isNull();

        // A's doorbell rang while it was draining, so the session is given up rather than drained here. The
        // yield is declined until this turn ends, which is what the hand-off has to survive.
        completeWhenReady(first, TestLiveSession.ok("first-done"));
        assertThat(local.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .as("the in-flight turn keeps its answer — the hand-off is about the queue, not the turn")
                .isEqualTo("first-done");

        final TestLiveSession second = awaitSession(successor, id);
        assertThat(second.awaitTurnStarted()).isTrue();
        completeWhenReady(second, TestLiveSession.ok("queued-done"));

        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .as("B's caller is answered by the turn B ran after the hand-off").isEqualTo("queued-done");
        assertThat(second.submittedInputs()).containsExactly("queued");
        assertThat(second.submittedTurnIds()).as("the successor runs the turn under the id B issued")
                .containsExactly(forwarded.getTurnId());
        assertThat(doorbells)
                .as("the relay, not a lease expiry, is what moved the work: node-A rings once on its way out")
                .containsExactly("node-B", "node-A");
        assertThat(inbox.isEmpty(id)).isTrue();
    }

    @Test
    @DisplayName("a draining node relays no doorbell for a conversation it never held")
    void aNodeThatNeverHeldTheConversationRelaysNothing() throws Exception {
        final TestManagerHarness departing = node("node-A");
        final SessionId watched = SessionId.of("c-drain-4");
        final SessionId busy = SessionId.of("c-drain-4-busy");
        final List<String> doorbells = tapDoorbells(watched);

        // A turn on an unrelated session, purely to keep closeGracefully waiting long enough to observe.
        departing.manager().submit(RequestFixtures.submit(busy, "alpha", "hello"));
        final TestLiveSession busySession = awaitSession(departing, busy);
        assertThat(busySession.awaitTurnStarted()).isTrue();
        // Subscribed to `watched` as an observer only: this node has never held it and never will.
        departing.manager().events(watched);

        startShutdown(departing, Duration.ofSeconds(5));
        awaitGateClosed(departing, SessionId.of("c-drain-4-probe"));

        inbox.deliver(queued(watched, "not-mine", TurnId.generate(), Instant.now()));
        ringDoorbell(watched);

        // An unconditional relay would let two draining nodes volley one announcement for the whole shutdown window, so
        // only the node that was the holder when the doorbell rang may pass it on. Short wait: the ring is dispatched
        // synchronously, so this only has to outlast that one attempt.
        Thread.sleep(200L);
        assertThat(doorbells).as("node-A owed nothing here, so it must add nothing to the rail")
                .containsExactly("node-Z");
        assertThat(leaseStore.findHolder(watched))
                .as("taking a lease in order to drain is the one thing a draining node must not do").isEmpty();
        assertThat(departing.session(watched)).isNull();
        assertThat(inbox.isEmpty(watched)).as("the message stays every peer's to collect").isFalse();

        completeWhenReady(busySession, TestLiveSession.ok("done"));
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

    /** A message written straight into the shared inbox, as a forwarding peer's {@code deliverToInbox} would. */
    private static InboundMessage queued(SessionId id, String input, TurnId turnId, Instant deliveredAt) {
        return InboundMessage.builder().sessionId(id).agentRef("alpha").userInput(input).turnId(turnId)
                .priority(QueuedInputPriority.LATER).initiator(Principal.user("tester")).deliveredAt(deliveredAt)
                .build();
    }

    /**
     * Rings {@code id}'s doorbell as a peer would. The origin must be foreign — {@code onSignal} drops self-origin
     * signals, so a doorbell published as the node under test would be ignored by it. The payload is omitted because
     * the receiver reads only the session id out of the envelope.
     */
    private void ringDoorbell(SessionId id) {
        bus.publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.MESSAGE_ENQUEUED)
                .originNodeId("node-Z").build());
    }

    /** Records every {@code TURN_RESULT} payload published for {@code id}, so a test can assert the wire shape. */
    private List<Map<String, Object>> tapTurnResults(SessionId id) {
        final List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
        taps.add(bus.subscribe(id, signal -> {
            if (signal.getKind() == SessionSignal.SignalKind.TURN_RESULT) {
                seen.add(signal.getPayload());
            }
        }));
        return seen;
    }

    /** Records who rang {@code id}'s doorbell, in order — the rail a hand-off is visible on. */
    private List<String> tapDoorbells(SessionId id) {
        final List<String> origins = new CopyOnWriteArrayList<>();
        taps.add(bus.subscribe(id, signal -> {
            if (signal.getKind() == SessionSignal.SignalKind.MESSAGE_ENQUEUED) {
                origins.add(signal.getOriginNodeId());
            }
        }));
        return origins;
    }

    /**
     * Starts {@code closeGracefully} on a daemon thread and registers it for teardown.
     *
     * <p>
     * Never on the test thread: the teardown after the grace window blocks on {@code awaitTermination} of the very
     * executor running the turn the test has not completed yet, so a foreground call deadlocks against its own
     * assertions.
     *
     * @return the holder for the return value, readable once {@link #awaitShutdown()} has run
     */
    private AtomicBoolean startShutdown(TestManagerHarness harness, Duration timeout) {
        final AtomicBoolean drained = new AtomicBoolean();
        final Thread thread = new Thread(() -> drained.set(harness.manager().closeGracefully(timeout)),
                "shutdown-" + shutdowns.size());
        thread.setDaemon(true);
        shutdowns.add(thread);
        thread.start();
        return drained;
    }

    private void awaitShutdown() throws InterruptedException {
        for (Thread shutdown : shutdowns) {
            shutdown.join(TestLiveSession.DEFAULT_AWAIT_MS);
            assertThat(shutdown.isAlive()).as("closeGracefully must return once the turns it waited for are done")
                    .isFalse();
        }
    }

    /**
     * Blocks until {@code harness} has flipped its shutdown gate, using the one edge a caller can observe: a submit is
     * refused. Deliberately aimed at a throwaway session — probing the one under test would hand back the lease
     * its running turn is holding.
     */
    private static void awaitGateClosed(TestManagerHarness harness, SessionId probe) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                harness.manager().submit(RequestFixtures.submit(probe, "alpha", "probe"));
            } catch (IllegalStateException e) {
                assertThat(e).hasMessageContaining("shutting down");
                return;
            }
            Thread.sleep(10L);
        }
        assertThatThrownBy(() -> harness.manager().submit(RequestFixtures.submit(probe, "alpha", "probe")))
                .as("the shutdown gate never closed").isInstanceOf(IllegalStateException.class);
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

    private static void awaitAnnouncements(List<Map<String, Object>> announced, int target)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (announced.size() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(announced).as("every collected message must be answered on the rail").hasSize(target);
    }

    /**
     * Completes the session's current turn, retrying past the window where there is not one yet — the fixture records a
     * submitted input before it installs that turn's future, so a test can arrive a beat early.
     */
    private static void completeWhenReady(TestLiveSession session, AgentExecutionResult result)
            throws InterruptedException {
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

    private static String outcomeOf(List<Map<String, Object>> announced, TurnId turnId) {
        return String.valueOf(payloadFor(announced, turnId).get("outcome"));
    }

    private static String messageOf(List<Map<String, Object>> announced, TurnId turnId) {
        return String.valueOf(payloadFor(announced, turnId).get("message"));
    }

    private static Map<String, Object> payloadFor(List<Map<String, Object>> announced, TurnId turnId) {
        for (Map<String, Object> payload : announced) {
            if (turnId.value().equals(payload.get("turn"))) {
                return payload;
            }
        }
        throw new AssertionError(
                "no TURN_RESULT was announced for turn " + turnId.value() + " (saw " + announced + ")");
    }
}
