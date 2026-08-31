package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
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
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Which {@code AgentRuntime} a drain-only pass opens the session against.
 *
 * <p>
 * A drain pass opens a session nobody on this node asked for, so every input to that open has to come out of the
 * envelope or out of the record. The agent comes from the record — the binding is authoritative and a message that
 * disagrees with it is refused rather than believed. The <em>discriminator</em> has no such source: nothing durable
 * records that a session bound to {@code alpha} was opened for {@code tenant-a}, so unless the envelope carries it the
 * holder can only open the bare {@code agent:alpha} runtime. That is not a plainer version of the right runtime; it is
 * a
 * different one, with whatever tools, hooks and MCP clients its own registration gave it.
 *
 * <p>
 * One node with a foreign doorbell stands in for the cluster: the message is written straight into the shared inbox as
 * a
 * forwarding peer's {@code deliverToInbox} would, and the ring arrives from a node id that is not this one, because
 * {@code onSignal} drops self-origin signals.
 */
@DisplayName("SessionRouter drain-only open: which runtime the queued message names")
class SessionRouterDrainRuntimeIdTest {

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
    @DisplayName("a queued message's discriminator reaches the opener as the composite runtime id")
    void queuedDiscriminatorReachesTheOpener() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-drain-ctx-1");
        repository.provision(id, "alpha");
        harness.manager().events(id);

        inbox.deliver(queued(id, "alpha", "tenant-a", "hello"));
        ringDoorbell(id);

        final TestLiveSession session = awaitSession(harness, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        assertThat(harness.openedRuntimeId(id))
                .as("the submitting node meant agent:alpha:tenant-a, and the holder has no other way to know")
                .isEqualTo(AgentRuntimeId.fromName("alpha", "tenant-a"));

        session.completeCurrentTurn(TestLiveSession.ok("done"));
    }

    @Test
    @DisplayName("a queued message without a discriminator opens the bare runtime")
    void absentDiscriminatorOpensTheBareRuntime() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-drain-ctx-2");
        repository.provision(id, "alpha");
        harness.manager().events(id);

        inbox.deliver(queued(id, "alpha", null, "hello"));
        ringDoorbell(id);

        final TestLiveSession session = awaitSession(harness, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        assertThat(harness.openedRuntimeId(id)).as("empty means bare, which is what the submitter opened too")
                .isEqualTo(AgentRuntimeId.fromName("alpha"));

        session.completeCurrentTurn(TestLiveSession.ok("done"));
    }

    @Test
    @DisplayName("a message naming another agent does not lend its discriminator to the bound one")
    void aConflictingMessageLendsNoDiscriminator() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-drain-ctx-3");
        final List<Map<String, Object>> announced = tapTurnResults(id);
        // Bound to alpha; the queued message names beta, so the pass will refuse it. The open still has to happen
        // first, and beta's discriminator names a runtime registered under beta — pairing it with alpha would ask the
        // opener for agent:alpha:tenant-b, which nobody registered, turning an orderly refusal into a failed open.
        repository.provision(id, "alpha");
        harness.manager().events(id);

        inbox.deliver(queued(id, "beta", "tenant-b", "wrong-agent"));
        ringDoorbell(id);

        final TestLiveSession session = awaitSession(harness, id);
        // The refusal, not a timeout, is what says the pass got as far as reading the message: an assertion on the
        // empty input list alone would also pass while the pass had not started.
        awaitAnnouncements(announced, 1);
        assertThat(String.valueOf(announced.get(0).get("outcome"))).isEqualTo("REJECTED");

        assertThat(harness.openedRuntimeId(id)).isEqualTo(AgentRuntimeId.fromName("alpha"));
        assertThat(session.submittedInputs()).as("the conflicting message is refused, not run").isEmpty();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------------------------------

    private TestManagerHarness node(String nodeId) {
        final TestManagerHarness harness = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).build();
        nodes.add(harness);
        return harness;
    }

    /** A message written straight into the shared inbox, as a forwarding peer's {@code deliverToInbox} would. */
    private static InboundMessage queued(SessionId id, String agentRef, String discriminator, String input) {
        return InboundMessage.builder().sessionId(id).agentRef(agentRef).contextDiscriminator(discriminator)
                .userInput(input).turnId(TurnId.generate()).priority(QueuedInputPriority.NEXT)
                .initiator(Principal.user("tester")).deliveredAt(Instant.now()).build();
    }

    /** Rings {@code id}'s doorbell as a peer would — the origin must be foreign, or {@code onSignal} drops it. */
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

    /** Records every {@code TURN_RESULT} payload published for {@code id}. */
    private List<Map<String, Object>> tapTurnResults(SessionId id) {
        final List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
        taps.add(bus.subscribe(id, signal -> {
            if (signal.getKind() == SessionSignal.SignalKind.TURN_RESULT) {
                seen.add(signal.getPayload());
            }
        }));
        return seen;
    }

    private static void awaitAnnouncements(List<Map<String, Object>> announced, int target)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (announced.size() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(announced).hasSize(target);
    }
}
