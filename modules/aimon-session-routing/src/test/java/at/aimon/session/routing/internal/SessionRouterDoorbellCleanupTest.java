package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * What a node does with a doorbell notice for a session that has just gone away.
 *
 * <p>
 * A doorbell that finds the session held elsewhere is remembered rather than dropped: the notice is what makes the
 * next lease return re-ring, so a message that arrived in an awkward window still gets collected. That is only true
 * while the message exists. A release, a delete or a peer's {@code EVICT} purges the inbox first, so a notice left
 * standing afterwards promises work nobody has — it buys an empty drain pass on the next lease return, hands a peer a
 * session that no longer exists, and outlives the {@link SessionId} itself, so a later session reusing that id
 * inherits an announcement about messages that were never its own.
 *
 * <p>
 * The marks are node-local bookkeeping with no behaviour of their own left to observe once the session is gone — an
 * empty drain pass is indistinguishable from no pass at all — which is why these tests read
 * {@link DefaultSessionRouter#hasDoorbellNotice(SessionId)} instead of asserting on an effect.
 */
@DisplayName("SessionRouter doorbell notices for sessions that went away")
class SessionRouterDoorbellCleanupTest {

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
    @DisplayName("releaseSession drops the notice it just purged the message behind")
    void releaseForgetsTheDoorbell() throws Exception {
        final TestManagerHarness node = node("node-A");
        final DefaultSessionRouter router = (DefaultSessionRouter) node.manager();
        final SessionId id = SessionId.of("c-doorbell-1");
        // A node hears a peer's doorbell only for sessions it is subscribed to, and nothing here submits.
        node.manager().events(id);

        final SessionLease outsider = leaseStore.tryAcquire(id, "outsider", Duration.ofSeconds(30)).orElseThrow();
        try {
            inbox.deliver(queued(id, "alpha", "hello"));
            ringDoorbellAsPeer(id);

            assertThat(awaitNotice(router, id)).as("a doorbell that cannot be answered has to be remembered").isTrue();

            node.manager().releaseSession(id);

            assertThat(inbox.isEmpty(id)).as("release purges the message the notice was about").isTrue();
            assertThat(router.hasDoorbellNotice(id)).as("so the notice must go with it").isFalse();
        } finally {
            leaseStore.release(outsider);
        }
    }

    @Test
    @DisplayName("a peer's EVICT drops the notice on every node that was holding one")
    void peerEvictForgetsTheDoorbell() throws Exception {
        final TestManagerHarness node = node("node-A");
        final TestManagerHarness releasing = node("node-B");
        final DefaultSessionRouter router = (DefaultSessionRouter) node.manager();
        final SessionId id = SessionId.of("c-doorbell-2");
        node.manager().events(id);

        final SessionLease outsider = leaseStore.tryAcquire(id, "outsider", Duration.ofSeconds(30)).orElseThrow();
        try {
            inbox.deliver(queued(id, "alpha", "hello"));
            ringDoorbellAsPeer(id);
            assertThat(awaitNotice(router, id)).isTrue();

            releasing.manager().releaseSession(id);

            assertThat(awaitNoNotice(router, id)).as("node-B purged the inbox, so node-A's notice is about nothing")
                    .isTrue();
        } finally {
            leaseStore.release(outsider);
        }
    }

    @Test
    @DisplayName("the relay debt goes with the notice, not just the pending mark")
    void forgettingDropsTheRelayDebtToo() throws Exception {
        final TestManagerHarness node = node("node-A");
        final TestManagerHarness releasing = node("node-B");
        final DefaultSessionRouter router = (DefaultSessionRouter) node.manager();
        final SessionId id = SessionId.of("c-doorbell-3");

        // The doorbell has to land while this node is the holder *and* busy. Holding is what records the debt to
        // relay it — only the node that was the holder may pass a doorbell on — and the turn gate is what stops the
        // drain pass that would otherwise answer it and clear both marks on the spot. Without both, only the pending
        // mark is ever set here, and half of forgetDoorbell goes unobserved.
        node.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(node, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        inbox.deliver(queued(id, "alpha", "hello"));
        ringDoorbellAsPeer(id);
        assertThat(awaitNotice(router, id)).isTrue();

        // node-B holds nothing: its release purges the shared inbox and announces EVICT. node-A's turn is still
        // running, so its entry stays pinned and nothing on node-A re-rings — what the assertion reads is
        // forgetDoorbell and nothing else.
        releasing.manager().releaseSession(id);

        assertThat(awaitNoNotice(router, id)).as("a standing relay debt hands a peer a session that was just released")
                .isTrue();

        // Only after the assertion: ending the turn re-collects, and a re-collect clears both marks by itself —
        // doing it earlier would hide whichever one forgetDoorbell failed to drop. It also lets the teardown finish
        // without waiting out the shutdown grace window.
        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------------------------------------------

    private TestManagerHarness node(String nodeId) {
        final TestManagerHarness harness = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).build();
        nodes.add(harness);
        return harness;
    }

    private static InboundMessage queued(SessionId id, String agentRef, String input) {
        return InboundMessage.builder().sessionId(id).agentRef(agentRef).userInput(input).turnId(TurnId.generate())
                .priority(QueuedInputPriority.NEXT).initiator(Principal.user("tester")).deliveredAt(Instant.now())
                .build();
    }

    /** Rings {@code id}'s doorbell as a peer would — the origin must be foreign, or {@code onSignal} drops it. */
    private void ringDoorbellAsPeer(SessionId id) {
        bus.publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.MESSAGE_ENQUEUED)
                .originNodeId("node-Z").build());
    }

    private static TestLiveSession awaitSession(TestManagerHarness harness, SessionId id) throws InterruptedException {
        await(() -> harness.session(id) != null);
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("a session for %s should have been opened", id).isNotNull();
        return session;
    }

    /** The doorbell is answered on a worker, so the mark it leaves appears a moment after the signal. */
    private static boolean awaitNotice(DefaultSessionRouter router, SessionId id) throws InterruptedException {
        return await(() -> router.hasDoorbellNotice(id));
    }

    private static boolean awaitNoNotice(DefaultSessionRouter router, SessionId id) throws InterruptedException {
        return await(() -> !router.hasDoorbellNotice(id));
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }
}
