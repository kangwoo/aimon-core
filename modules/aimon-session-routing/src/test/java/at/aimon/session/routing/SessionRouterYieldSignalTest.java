package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
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
import at.aimon.core.agent.stream.InterruptedAt;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * {@code YIELD}: "give this session up", as its own signal kind rather than an interrupt with a special reason.
 *
 * <p>
 * Stage 3b-2 moved the lease from the turn to the session and had to make peers able to ask for a session, so it
 * reused the one channel it had: an unaddressed {@code INTERRUPT(SESSION_RELEASED)}, which the receiver reads as a
 * yield
 * request. That works, but it conflates two different asks — <em>stop the turn</em> and <em>hand the session
 * over</em>
 * — and the second one has nothing to do with a turn: the case it exists for is an <b>idle</b> holder, where there is
 * no
 * turn to interrupt at all and an interrupt-shaped request is a request for nothing.
 *
 * <p>
 * The tests below therefore split the two: what a yield must do (stop the turn if there is one, drop the session,
 * return
 * the lease) from what it must <em>not</em> do (any of {@code EVICT}'s terminal work — the session is not going
 * away,
 * it is going somewhere else). The last two cover the rolling-upgrade shim in both directions, since a node that
 * predates
 * this kind drops the signal undecoded on all three rails.
 */
@DisplayName("SessionRouter YIELD signal")
class SessionRouterYieldSignalTest {

    private static final String NODE_ID = "node-A";
    private static final String PEER_ID = "node-B";

    private TestManagerHarness harness;
    private TestManagerHarness peerNode;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
        if (peerNode != null) {
            peerNode.close();
        }
    }

    @Test
    @DisplayName("a peer's YIELD makes an idle holder hand the conversation over")
    void yieldMakesAnIdleHolderHandTheConversationOver() throws Exception {
        harness = TestManagerHarness.builder().nodeId(NODE_ID).build();
        final SessionId id = SessionId.of("c-yield-1");

        runOneTurn(id, "hello", "done");
        final TestLiveSession session = harness.session(id);
        assertThat(harness.leaseStore().findHolder(id)).as("an idle holder keeps the lease since 3b-2").isPresent();

        publishYield(id);

        assertThat(awaitLeaseReturned(id)).as("the whole point of the kind: an idle holder lets go").isTrue();
        assertThat(awaitClosed(session)).as("the session goes with it — closing is what returns the lease").isTrue();
    }

    @Test
    @DisplayName("YIELD stops the running turn instead of waiting it out")
    void yieldStopsTheRunningTurn() throws Exception {
        harness = TestManagerHarness.builder().nodeId(NODE_ID).build();
        final SessionId id = SessionId.of("c-yield-2");

        final SubmitDisposition disposition = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(disposition.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        publishYield(id);

        // Without this the asking peer waits out a turn of unbounded length, which is the deadline it cannot afford.
        assertThat(session.awaitInterrupt()).as("a yield must stop the turn, not queue behind it").isTrue();
        assertThat(session.recordedInterrupts()).containsExactly(InterruptReason.SESSION_RELEASED);
        assertThat(harness.leaseStore().findHolder(id)).as("a pinned entry defers its close to the turn's end")
                .isPresent();

        session.completeCurrentTurn(TestLiveSession.ok("stopped"));
        disposition.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(awaitLeaseReturned(id)).as("the deferred close must still return the lease at unpin").isTrue();
    }

    @Test
    @DisplayName("YIELD does none of EVICT's terminal work — the conversation is moving, not ending")
    void yieldIsNotAnEviction() throws Exception {
        harness = TestManagerHarness.builder().nodeId(NODE_ID).build();
        final SessionId id = SessionId.of("c-yield-3");

        final CopyOnWriteArrayList<AgentExecutionEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch terminated = new CountDownLatch(1);
        subscribe(id, received, terminated);

        runOneTurn(id, "hello", "done");
        // Straight into the inbox, deliberately without the MESSAGE_ENQUEUED signal a forwarding node would publish:
        // ringing the doorbell would have this very node drain it, and what is under test is that a yield leaves the
        // queue for whoever picks the session up next.
        harness.inbox()
                .deliver(InboundMessage.builder().sessionId(id).agentRef("alpha").userInput("queued")
                        .priority(QueuedInputPriority.NEXT).initiator(Principal.user("tester"))
                        .deliveredAt(Instant.now()).build());

        publishYield(id);
        assertThat(awaitLeaseReturned(id)).isTrue();

        assertThat(harness.inbox().isEmpty(id)).as("a queued message belongs to the next holder, not to the bin")
                .isFalse();
        assertThat(terminated.await(200, TimeUnit.MILLISECONDS))
                .as("subscribers keep their stream — the conversation still exists").isFalse();
        assertThat(received).as("no terminal InterruptedAt: nothing has ended")
                .noneMatch(InterruptedAt.class::isInstance);
    }

    @Test
    @DisplayName("a bare INTERRUPT(SESSION_RELEASED) from an older peer is still read as a yield")
    void legacyInterruptIsStillReadAsAYield() throws Exception {
        harness = TestManagerHarness.builder().nodeId(NODE_ID).build();
        final SessionId id = SessionId.of("c-yield-4");

        runOneTurn(id, "hello", "done");
        assertThat(harness.leaseStore().findHolder(id)).isPresent();

        // Exactly what a node predating SignalKind.YIELD sends. Dropping this shim would strand every such peer.
        harness.signalBus().publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.INTERRUPT)
                .originNodeId(PEER_ID).payload(Map.of("reason", InterruptReason.SESSION_RELEASED.name())).build());

        assertThat(awaitLeaseReturned(id)).as("the receive-side half of the rollout shim").isTrue();
    }

    @Test
    @DisplayName("a peer's delete broadcasts YIELD plus the legacy companion, and takes an idle holder's lease")
    void deleteYieldsAnIdleHolderAndKeepsTheLegacyCompanion() throws Exception {
        final SessionLeaseStore leaseStore = new InMemorySessionLeaseStore();
        final SessionSignalBus bus = new InMemorySignalBus();
        final SessionInbox inbox = new InMemorySessionInbox();
        final SessionRecordStore repository = new InMemorySessionRecordStore();
        final IdempotencyStore idempotency = new InMemoryIdempotencyStore();
        harness = TestManagerHarness.builder().nodeId(NODE_ID).leaseStore(leaseStore).signalBus(bus).inbox(inbox)
                .repository(repository).idempotencyStore(idempotency).build();
        peerNode = TestManagerHarness.builder().nodeId(PEER_ID).leaseStore(leaseStore).signalBus(bus).inbox(inbox)
                .repository(repository).idempotencyStore(idempotency).build();

        final SessionId id = SessionId.of("c-yield-5");
        final CopyOnWriteArrayList<SessionSignal> seen = new CopyOnWriteArrayList<>();
        bus.subscribe(id, seen::add);

        runOneTurn(id, "hello", "done");
        final TestLiveSession session = harness.session(id);
        assertThat(leaseStore.findHolder(id).orElseThrow().getHolderId()).isEqualTo(NODE_ID);

        // No yielder thread and no interrupt to honor: the holder is idle. Before this kind existed the peer's only
        // move was to interrupt a turn that was not running and then spend its whole budget retrying.
        assertThatCode(() -> peerNode.manager().deleteSession(id)).doesNotThrowAnyException();

        assertThat(awaitClosed(session)).as("the holder must have given the conversation up").isTrue();
        assertThat(kinds(seen, id)).as("both halves go out until every node understands YIELD")
                .contains(SessionSignal.SignalKind.YIELD, SessionSignal.SignalKind.INTERRUPT);
        assertThat(leaseStore.findHolder(id)).as("the delete released the lease it took").isEmpty();
    }

    private static List<SessionSignal.SignalKind> kinds(List<SessionSignal> signals, SessionId id) {
        return signals.stream().filter(s -> s.getSessionId().equals(id)).map(SessionSignal::getKind).toList();
    }

    private void publishYield(SessionId id) {
        // originNodeId must differ from this manager's own or the same-origin filter drops it before the handler.
        harness.signalBus().publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.YIELD)
                .originNodeId(PEER_ID).build());
    }

    private void subscribe(SessionId id, List<AgentExecutionEvent> sink, CountDownLatch terminated) {
        harness.manager().events(id).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentExecutionEvent item) {
                sink.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                /* not expected */ }

            @Override
            public void onComplete() {
                terminated.countDown();
            }
        });
    }

    /**
     * Runs one turn to completion so the tests below start from a session this node holds a lease on.
     *
     * <p>
     * The route is deliberately not pinned: the turn gate outlives the caller-visible future by the width of the
     * post-turn cleanup, so a submission that follows a previous answer closely enough can be queued instead of run
     * inline. Either way the same session runs it and the same answer comes back, which is the only part the yield
     * tests build on.
     */
    private void runOneTurn(SessionId id, String input, String answer) throws Exception {
        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", input));
        assertThat(outcome.getKind()).isIn(SubmitDisposition.Kind.EXECUTED_LOCALLY, SubmitDisposition.Kind.FORWARDED);
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnInFlight()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok(answer));
        assertThat(outcome.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo(answer);
    }

    private boolean awaitLeaseReturned(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            final Optional<LeaseHolder> holder = harness.leaseStore().findHolder(id);
            if (holder.isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static boolean awaitClosed(TestLiveSession session) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (!session.isClosed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return session.isClosed();
    }

    private TestLiveSession waitForSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(harness.session(id)).as("session for %s should be opened", id).isNotNull();
        return harness.session(id);
    }
}
