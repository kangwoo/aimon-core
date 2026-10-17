package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Where a session's owner comes from: the {@code owner} of the submit that provisions its record, on the claim path or
 * — for a forwarded first submit — on the drain path. First wins, nothing overwrites it, and nothing enforces it.
 */
@DisplayName("SessionRouter session owner")
class SessionRouterOwnerTest {

    private static final Principal ALICE = Principal.user("alice", "Alice");
    private static final Principal MALLORY = Principal.user("mallory", "Mallory");

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
    @DisplayName("a submit naming an owner records it; a submit naming none records none")
    void submitRecordsTheOwner() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId owned = SessionId.of("c-owner-1");
        final SessionId unowned = SessionId.of("c-owner-2");

        runOneTurn(harness, SubmitRequest.builder().sessionId(owned).agentRef("alpha").userInput("hi")
                .initiator(Principal.system()).owner(ALICE).build());
        runOneTurn(harness, RequestFixtures.submit(unowned, "alpha", "hi"));

        assertThat(repository.load(owned).orElseThrow().getOwner()).contains(ALICE);
        // Not derived from the initiator: a session whose host named no owner has none, whoever spoke.
        assertThat(repository.load(unowned).orElseThrow().getOwner()).isEmpty();
    }

    @Test
    @DisplayName("a later claim naming another owner is run, and changes nothing")
    void laterOwnerDoesNotReplaceTheFirst() throws Exception {
        final TestManagerHarness first = node("node-A");
        final SessionId id = SessionId.of("c-owner-3");
        runOneTurn(first, withOwner(id, ALICE));
        first.manager().releaseSession(id);
        awaitUnheld(id);

        final TestManagerHarness second = node("node-B");
        runOneTurn(second, withOwner(id, MALLORY));

        assertThat(repository.load(id).orElseThrow().getOwner()).contains(ALICE);
    }

    @Test
    @DisplayName("a forwarded first submit records its owner when the holder drains it")
    void drainRecordsTheOwner() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-owner-4");
        harness.manager().events(id);

        inbox.deliver(queued(id, "alpha", ALICE));
        ringDoorbell(id);

        final TestLiveSession session = awaitSession(harness, id);
        assertThat(session.awaitTurnStarted()).isTrue();
        assertThat(repository.load(id).orElseThrow().getOwner()).contains(ALICE);
        session.completeCurrentTurn(TestLiveSession.ok("done"));
    }

    @Test
    @DisplayName("a drained message naming another agent is refused and writes no owner")
    void refusedMessageWritesNoOwner() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-owner-5");
        repository.provision(id, "alpha");
        harness.manager().events(id);

        inbox.deliver(queued(id, "beta", MALLORY));
        ringDoorbell(id);

        awaitSession(harness, id);
        final long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline && !inbox.isEmpty(id)) {
            Thread.sleep(10L);
        }
        Thread.sleep(100L);
        assertThat(repository.load(id).orElseThrow().getOwner()).isEmpty();
    }

    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an agentRef the runtime id would refuse never reaches the claim, so it cannot poison the session (#260)")
    void invalidAgentRefCannotBindTheSession() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-owner-poison");

        assertThatThrownBy(() -> harness.manager()
                .submit(SubmitRequest.builder().sessionId(id).agentRef("ops:prod").userInput("hi")
                        .initiator(Principal.system()).owner(MALLORY).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.exists(id)).as("nothing was provisioned or bound").isFalse();

        runOneTurn(harness, withOwner(id, ALICE));

        assertThat(repository.load(id).orElseThrow().getAgentRef()).contains("alpha");
        assertThat(repository.load(id).orElseThrow().getOwner()).contains(ALICE);
    }

    private TestManagerHarness node(String nodeId) {
        final TestManagerHarness harness = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).build();
        nodes.add(harness);
        return harness;
    }

    private static SubmitRequest withOwner(SessionId id, Principal owner) {
        return SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("hi").initiator(Principal.system())
                .owner(owner).build();
    }

    private static void runOneTurn(TestManagerHarness harness, SubmitRequest request) throws Exception {
        final SubmitDisposition outcome = harness.manager().submit(request);
        assertThat(outcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final TestLiveSession session = awaitSession(harness, request.getSessionId());
        assertThat(session.awaitTurnStarted()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get();
    }

    private void awaitUnheld(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (leaseStore.findHolder(id).isPresent() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(leaseStore.findHolder(id)).isEmpty();
    }

    private static InboundMessage queued(SessionId id, String agentRef, Principal owner) {
        return InboundMessage.builder().sessionId(id).agentRef(agentRef).userInput("hello").turnId(TurnId.generate())
                .priority(QueuedInputPriority.NEXT).initiator(Principal.system()).owner(owner)
                .deliveredAt(Instant.now()).build();
    }

    private void ringDoorbell(SessionId id) {
        bus.publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.MESSAGE_ENQUEUED)
                .originNodeId("node-Z").build());
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
}
