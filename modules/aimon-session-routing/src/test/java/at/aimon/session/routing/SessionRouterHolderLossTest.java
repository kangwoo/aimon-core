package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.InterruptedAt;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Holder-loss recovery seen from the manager: a caller waiting on a turn whose node died is failed by the sweeper's
 * announcement rather than left to its five-minute forward deadline (design §6.3 D).
 *
 * <p>
 * The announcement is addressed by <em>idempotency key alone</em>, and that is the property these tests exist to pin.
 * No surviving node knows the lost {@code TurnId}: the sweeper works from an {@link IdempotencyEntry}, which records
 * the
 * reservation and its holder but not the turn the dead holder was running — that id travelled in the inbox envelope the
 * holder consumed before it died. So both the local resolve and the wire payload have to work with the key on its own.
 *
 * <p>
 * The same ignorance shapes the recovery <em>event</em>: it goes onto the {@code EVENT} rail with no turn stamp, and is
 * therefore delivered to a session's subscribers rather than to one turn's.
 */
@DisplayName("SessionRouter holder-loss recovery")
class SessionRouterHolderLossTest {

    private static final String KEY = "k-lost";
    private static final String INPUT = "the turn that never finishes";

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
    @DisplayName("the sweeping node resolves its own forward, which no rail broadcast could do for it")
    void sweepFailsTheLocalForward() throws Exception {
        plantStaleReservation(SessionId.of("c-lost-1"), "dead-node");
        final List<Map<String, Object>> announced = tapTurnResults(SessionId.of("c-lost-1"));
        final TestManagerHarness harness = sweepingNode("node-A");

        final SubmitDisposition forwarded = harness.manager().submit(keyed(SessionId.of("c-lost-1"), INPUT));
        assertThat(forwarded.getKind()).as("a submission collapsed onto a reservation another node holds can only wait")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // onSignal drops self-origin signals, so the rail cannot answer the node that publishes the announcement. The
        // manager resolving its own forward alongside the publish is what stops the sweeping node from being the one
        // caller that waits out the full forward deadline.
        assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOLDER_LOST").hasMessageContaining("dead-node").hasMessageContaining(KEY);
        // Literal keys on purpose: this is the wire shape a peer decodes. No "turn" entry, because there is no turn id
        // to
        // put there — see the class comment.
        assertThat(announced).anySatisfy(payload -> assertThat(payload).containsEntry("outcome", "HOLDER_LOST")
                .containsEntry("idem", KEY).doesNotContainKey("turn"));
    }

    @Test
    @DisplayName("a peer's key-only announcement fails a forward this node is holding")
    void peerAnnouncementFailsTheForward() throws Exception {
        final SessionId id = SessionId.of("c-lost-2");
        plantStaleReservation(id, "dead-node");
        // Sweeping stays off here so nothing but the peer's payload can resolve the forward.
        final TestManagerHarness harness = node("node-A", b -> b.holderLossSweepInterval(Duration.ofMinutes(5)));

        final SubmitDisposition forwarded = harness.manager().submit(keyed(id, INPUT));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        publishTurnResult(id, Map.of("idem", KEY, "outcome", "HOLDER_LOST", "message", "holder dead-node was lost"));

        assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOLDER_LOST").hasMessageContaining(KEY);
    }

    @Test
    @DisplayName("an announcement with neither address is discarded rather than failing an unrelated forward")
    void addresslessAnnouncementIsIgnored() throws Exception {
        final SessionId id = SessionId.of("c-lost-3");
        plantStaleReservation(id, "dead-node");
        final TestManagerHarness harness = node("node-A", b -> b.holderLossSweepInterval(Duration.ofMinutes(5)));

        final SubmitDisposition forwarded = harness.manager().submit(keyed(id, INPUT));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        publishTurnResult(id, Map.of("outcome", "HOLDER_LOST", "message", "somebody's turn died"));

        Thread.sleep(200L);
        assertThat(forwarded.getFuture().toCompletableFuture()).as(
                "a payload naming no turn and no key reaches nobody; it must not fail whatever happens to be pending")
                .isNotDone();
    }

    @Test
    @DisplayName("holder loss does not end the conversation's event stream")
    void streamStaysOpenAcrossHolderLoss() throws Exception {
        final SessionId id = SessionId.of("c-lost-4");
        plantStaleReservation(id, "dead-node");
        final TestManagerHarness harness = sweepingNode("node-A");
        final TerminationRecordingSubscriber subscriber = new TerminationRecordingSubscriber();
        harness.manager().events(id).subscribe(subscriber);

        final SubmitDisposition forwarded = harness.manager().submit(keyed(id, INPUT));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(awaitFailure(forwarded.getFuture())).hasMessageContaining("HOLDER_LOST");

        // The sweeper used to complete this stream and broadcast an EVICT so every peer did the same. A successor can
        // already hold the lease and be running the next turn, so that ended a session over a turn that died
        // elsewhere.
        assertThat(subscriber.terminated).as("one dead attempt is not the end of the conversation").isEmpty();
        assertThat(hasHolderLostFrame(subscriber)).as("subscribers still learn why the turn stopped producing")
                .isTrue();
    }

    @Test
    @DisplayName("a peer's subscriber hears the holder loss instead of watching its stream go quiet")
    void holderLossReachesSubscribersOnOtherNodes() throws Exception {
        final SessionId id = SessionId.of("c-lost-5");
        plantStaleReservation(id, "dead-node");

        // The watching node goes first, and its subscribe is not decoration: events() is what installs this node's bus
        // subscription, and the rail is not a log — a frame published before the subscription exists reaches nobody.
        final TestManagerHarness watcher = node("node-B", b -> b.holderLossSweepInterval(Duration.ofMinutes(5)));
        final TerminationRecordingSubscriber subscriber = new TerminationRecordingSubscriber();
        watcher.manager().events(id).subscribe(subscriber);

        // Nothing else on this node is party to the turn: no submit, so no forward to resolve and no reservation of its
        // own. The relayed frame is the only thing that can tell this subscriber anything.
        sweepingNode("node-A");

        assertThat(awaitHolderLostFrame(subscriber)).as(
                "the sweep is the only evidence that will ever arrive for the lost turn — a local-only frame leaves "
                        + "every other node's subscribers with a stream that simply stops")
                .isTrue();
        assertThat(subscriber.terminated).as("and it is news about a turn, not the end of the conversation").isEmpty();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------------------------------------------

    /**
     * Plants the reservation a node took out and then died holding: IN_FLIGHT, with a holder, last touched long enough
     * ago that any sane secondary TTL has lapsed. {@code inputHash} must match what the manager computes for
     * {@link #INPUT}, or the submission below is rejected as a key reused with different input instead of collapsing
     * onto this entry.
     */
    private void plantStaleReservation(SessionId id, String holderId) {
        final Instant touched = Instant.now().minus(Duration.ofSeconds(30));
        idempotency.putIfAbsent(KEY,
                IdempotencyEntry.builder().key(KEY).sessionId(id).inputHash(sha256(INPUT))
                        .status(IdempotencyEntry.Status.IN_FLIGHT).holderId(holderId).createdAt(touched)
                        .lastTouchedAt(touched).build(),
                Duration.ofMinutes(10));
    }

    /**
     * A node whose sweeper fires soon but not instantly. The delay has to outlast the submit that registers the forward
     * — a sweep that lands first resets the reservation, and the submit then inserts one of its own and runs the turn
     * locally, which is a different test.
     */
    private TestManagerHarness sweepingNode(String nodeId) {
        return node(nodeId,
                b -> b.holderLossSweepInterval(Duration.ofMillis(300)).idempotencySecondaryTtl(Duration.ofSeconds(1)));
    }

    private TestManagerHarness node(String nodeId, Consumer<TestManagerHarness.Builder> customizer) {
        final TestManagerHarness.Builder builder = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency);
        customizer.accept(builder);
        final TestManagerHarness harness = builder.build();
        nodes.add(harness);
        return harness;
    }

    private static SubmitRequest keyed(SessionId id, String input) {
        return SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput(input)
                .initiator(Principal.user("tester")).idempotencyKey(KEY).build();
    }

    /**
     * Publishes a raw {@code TURN_RESULT} as a peer would. A foreign origin is required: onSignal drops self-origin.
     */
    private void publishTurnResult(SessionId id, Map<String, Object> payload) {
        bus.publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.TURN_RESULT)
                .originNodeId("node-Z").payload(payload).build());
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

    private static Throwable awaitFailure(CompletionStage<AgentExecutionResult> stage) throws Exception {
        try {
            stage.toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            return ee.getCause() != null ? ee.getCause() : ee;
        }
        throw new AssertionError("Expected the forwarded future to fail, but it completed");
    }

    private static boolean awaitHolderLostFrame(TerminationRecordingSubscriber subscriber) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (!hasHolderLostFrame(subscriber) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return hasHolderLostFrame(subscriber);
    }

    /** The reason matters as much as the type: an interrupt is how a yield looks too. */
    private static boolean hasHolderLostFrame(TerminationRecordingSubscriber subscriber) {
        return subscriber.received.stream()
                .anyMatch(event -> event instanceof InterruptedAt at && at.getReason() == InterruptReason.HOLDER_LOST);
    }

    private static String sha256(String input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Records the frames received and any terminal signal, so a test can assert on both what arrived and what did not.
     */
    private static final class TerminationRecordingSubscriber implements Flow.Subscriber<AgentExecutionEvent> {

        final List<AgentExecutionEvent> received = new CopyOnWriteArrayList<>();
        final List<String> terminated = new CopyOnWriteArrayList<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AgentExecutionEvent item) {
            received.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            terminated.add("onError: " + throwable);
        }

        @Override
        public void onComplete() {
            terminated.add("onComplete");
        }
    }
}
