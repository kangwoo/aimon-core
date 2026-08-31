package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Holder-side signal subscription: a node that runs turns must hear the session's rail even when no client ever
 * called {@link SessionRouter#events(SessionId)} on it.
 *
 * <p>
 * Subscriptions used to be armed only by {@code events()}, which made every cross-node control path depend on a
 * coincidence — it worked when the client happened to stream from the node that also won the lock, and silently did
 * nothing when it did not. Both tests here deliberately never call {@code events()} on the holder, so they fail against
 * the observer-only wiring: the stop button does not stop, and a peer's delete cannot make the holder yield.
 */
@DisplayName("SessionRouter holder-side signal subscription")
class SessionRouterSignalSubscriptionTest {

    private TestManagerHarness holderNode;
    private TestManagerHarness peerNode;

    @AfterEach
    void tearDown() {
        if (peerNode != null) {
            peerNode.close();
        }
        if (holderNode != null) {
            holderNode.close();
        }
    }

    /**
     * Two managers over one set of shared SPIs — the in-process stand-in for two cluster nodes. Everything a real
     * deployment keeps in a shared backend (lock, rail, inbox, history, idempotency) is shared here; only the node
     * identity and the node-local caches differ.
     */
    private void wireTwoNodes() {
        final SessionLeaseStore leaseStore = new InMemorySessionLeaseStore();
        final SessionSignalBus bus = new InMemorySignalBus();
        final SessionInbox inbox = new InMemorySessionInbox();
        final SessionRecordStore repository = new InMemorySessionRecordStore();
        final IdempotencyStore idempotency = new InMemoryIdempotencyStore();

        holderNode = TestManagerHarness.builder().nodeId("node-A").leaseStore(leaseStore).signalBus(bus).inbox(inbox)
                .repository(repository).idempotencyStore(idempotency).build();
        peerNode = TestManagerHarness.builder().nodeId("node-B").leaseStore(leaseStore).signalBus(bus).inbox(inbox)
                .repository(repository).idempotencyStore(idempotency).build();
    }

    @Test
    @DisplayName("a peer's interrupt() trips the turn on a holder nobody is streaming from")
    void interruptReachesHolderThatNeverStreamed() throws Exception {
        wireTwoNodes();
        final SessionId id = SessionId.of("c-subscribe-1");

        // No events() anywhere — a scheduled or fire-and-forget turn, or a client streaming from a third node.
        final SubmitDisposition disposition = holderNode.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(disposition.getKind()).as("node-A must win the lock and run the turn locally")
                .isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        final TestLiveSession session = awaitSession(holderNode, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        peerNode.manager().interrupt(id, InterruptReason.USER_SIGINT);

        assertThat(session.awaitInterrupt()).as("the lock holder is the only node that can stop the turn").isTrue();
        assertThat(session.recordedInterrupts()).containsExactly(InterruptReason.USER_SIGINT);

        session.completeCurrentTurn(TestLiveSession.ok("stopped"));
        disposition.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a peer's deleteSession() makes the holder yield the lock instead of exhausting its retries")
    void deleteOnPeerMakesTheHolderYieldTheLock() throws Exception {
        wireTwoNodes();
        final SessionId id = SessionId.of("c-subscribe-2");

        final SubmitDisposition disposition = holderNode.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(disposition.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        final TestLiveSession session = awaitSession(holderNode, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // Stand in for a real session honoring the interrupt: TestLiveSession only records it, so nothing would ever
        // release the lock on its own. The point under test is that the yield request arrives at all.
        final Thread yielder = new Thread(() -> {
            try {
                if (session.awaitInterrupt()) {
                    session.completeCurrentTurn(TestLiveSession.ok("interrupted"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "test-yielder");
        yielder.setDaemon(true);
        yielder.start();

        // The delete broadcasts a YIELD (plus its legacy INTERRUPT(SESSION_RELEASED) companion) on contention and
        // retries within a bounded budget. A holder deaf to the rail never yields, so this throws IllegalStateException
        // once the budget runs out.
        assertThatCode(() -> peerNode.manager().deleteSession(id)).doesNotThrowAnyException();

        yielder.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(session.recordedInterrupts()).contains(InterruptReason.SESSION_RELEASED);
        assertThat(awaitClosed(session)).as("the peer's EVICT must also drop the holder's cached session").isTrue();
    }

    private static TestLiveSession awaitSession(TestManagerHarness harness, SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("submit must open a local session on the holder").isNotNull();
        return session;
    }

    private static boolean awaitClosed(TestLiveSession session) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (!session.isClosed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return session.isClosed();
    }
}
