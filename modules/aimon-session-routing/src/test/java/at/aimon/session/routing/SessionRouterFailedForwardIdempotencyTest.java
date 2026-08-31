package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
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
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * What a terminal failure owes the idempotency key of the turn it is failing, once that turn was forwarded.
 *
 * <p>
 * Forwarding hands the key over rather than dropping it — the turn is real, it is merely running elsewhere — so
 * {@code safeReleaseHolder} leaves a holderless {@code IN_FLIGHT} reservation standing, and {@code checkIdempotency}
 * answers {@code alreadyInFlight} to any entry that is not {@code DONE}. When such a turn then failed, the router
 * announced the failure and deliberately wrote no result: caching a failure would make every later retry of that key
 * inherit it. But the reservation stayed, and {@code compareAndReset} could not reach it — that method matches on a
 * holder, and a queued reservation has none on purpose. So the client was told its turn was dead, retried, and was told
 * the key was already in flight: it collapsed onto the very attempt it had just been told had died, then waited out
 * {@code idempotencyForwardTtl} for an announcement that had already been made. The key stopped being replayable at
 * exactly the moment its caller was told to replay it.
 *
 * <p>
 * Two managers over one shared backend stand in for two cluster nodes, as in
 * {@code SessionRouterForwardedTurnResultTest}: node A holds the session and runs the turn, node B is the node whose
 * caller submitted it and whose retry has to be accepted.
 */
@DisplayName("SessionRouter frees a forwarded turn's idempotency key when that turn fails terminally")
class SessionRouterFailedForwardIdempotencyTest {

    private static final String AGENT = "alpha";
    private static final String KEY = "k-fwd-fail";
    /** The retry must repeat the input verbatim, or {@code checkIdempotency} answers with a key-reuse conflict. */
    private static final String INPUT = "second";

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
        // Reverse order, so the node that may still be draining on another's behalf goes down last.
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).close();
        }
    }

    @Test
    @DisplayName("a retry after a forwarded turn fails is a fresh attempt, not a collapse onto the dead one")
    void aRetryAfterAFailedForwardIsAcceptedAsAFreshAttempt() throws Exception {
        final TestManagerHarness holder = node("node-A");
        final TestManagerHarness peer = node("node-B");
        final SessionId id = SessionId.of("c-fwd-fail-1");

        final SubmitDisposition forwarded = forwardedTurnRunningOnHolder(holder, peer, id);
        final TestLiveSession session = holder.session(id);
        session.failCurrentTurn(new RuntimeException("boom"));

        assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED").hasMessageContaining("boom");
        assertThat(idempotency.find(KEY))
                .as("no failure is cached, and the reservation the forward left behind goes with it").isEmpty();

        // The point of the whole exercise. A collapsed retry is answered with the synthetic idem-<key> handle, puts
        // nothing in the inbox, and waits out idempotencyForwardTtl for an announcement that has already been made.
        final SubmitDisposition retry = peer.manager().submit(keyed(id, INPUT));
        assertThat(retry.getKind()).as("node-A still holds the session, so a fresh attempt is forwarded too")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(retry.getInboxId().orElseThrow().value())
                .as("a real inbox handle, not the synthetic id checkIdempotency answers an in-flight key with")
                .doesNotStartWith("idem-");

        assertThat(session.awaitTurnCount(3)).as("the holder must run the retry as a turn of its own").isTrue();
        assertThat(session.awaitTurnInFlight()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("retry-done"));

        assertThat(retry.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS)
                .getFinalAnswer()).as("the retry is answered by the turn it actually caused").isEqualTo("retry-done");
        assertThat(session.submittedInputs()).as("and that turn really reached the agent").containsExactly("first",
                INPUT, INPUT);
    }

    @Test
    @DisplayName("a retry submitted the instant the forward fails is still a fresh attempt")
    void aRetryFromTheFailureCallbackDoesNotAdoptTheDeadAttempt() throws Exception {
        final TestManagerHarness holder = node("node-A");
        final TestManagerHarness peer = node("node-B");
        final SessionId id = SessionId.of("c-fwd-fail-3");

        final SubmitDisposition forwarded = forwardedTurnRunningOnHolder(holder, peer, id);

        // Submitted from the completion callback, which runs on the thread that settles the future before that thread
        // gets any further. This is the earliest instant any caller can react to the failure, and pinning it is what
        // makes the ordering assertable rather than a race: the previous test's sibling window lived here, between the
        // future settling and the pending-forward entry being withdrawn. A retry landing in it was handed the corpse of
        // the very attempt it was retrying — told "failed" a second time while its own message ran on the holder
        // anyway. Freeing the durable reservation before the announcement (above) buys nothing if the in-memory half
        // stays adoptable a few instructions longer.
        final CompletableFuture<Object> retrySubmitted = new CompletableFuture<>();
        forwarded.getFuture().whenComplete((result, failure) -> {
            try {
                retrySubmitted.complete(peer.manager().submit(keyed(id, INPUT)));
            } catch (Throwable t) {
                retrySubmitted.complete(t);
            }
        });

        holder.session(id).failCurrentTurn(new RuntimeException("boom"));
        assertThat(awaitFailure(forwarded.getFuture())).hasMessageContaining("FAILED");

        final Object submitted = retrySubmitted.get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        assertThat(submitted).as("the retry must be accepted, not rejected, at the moment of failure")
                .isInstanceOf(SubmitDisposition.class);
        final SubmitDisposition retry = (SubmitDisposition) submitted;
        assertThat(retry.getTurnId()).as("adopting the dead entry hands the retry that attempt's turn id, and with it "
                + "the failure it was retrying").isNotEqualTo(forwarded.getTurnId());

        final TestLiveSession session = holder.session(id);
        assertThat(session.awaitTurnCount(3)).as("and the retry must reach the holder as a turn of its own").isTrue();
        assertThat(session.awaitTurnInFlight()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("retry-done"));

        assertThat(retry.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS)
                .getFinalAnswer()).isEqualTo("retry-done");
    }

    @Test
    @DisplayName("the reservation is already gone by the time the failure is announced on the rail")
    void theKeyIsFreedBeforeTheFailureIsAnnounced() throws Exception {
        final TestManagerHarness holder = node("node-A");
        final TestManagerHarness peer = node("node-B");
        final SessionId id = SessionId.of("c-fwd-fail-2");

        // Read on the publishing thread, from inside publish: InMemorySignalBus dispatches synchronously, so this
        // handler sees the store exactly as it stood when the announcement went out. Nobody who learns of the failure
        // can observe an earlier state of the key than this one — which is what makes the ordering assertable rather
        // than a race.
        final List<Boolean> reservedWhenAnnounced = new CopyOnWriteArrayList<>();
        taps.add(bus.subscribe(id, signal -> {
            // Literal wire keys on purpose: this is the payload shape a peer decodes, and TurnResultPayloadTest owns
            // the mapping from the internal constants to these names.
            if (signal.getKind() == SessionSignal.SignalKind.TURN_RESULT
                    && "FAILED".equals(signal.getPayload().get("outcome"))
                    && KEY.equals(signal.getPayload().get("idem"))) {
                reservedWhenAnnounced.add(idempotency.find(KEY).isPresent());
            }
        }));

        final SubmitDisposition forwarded = forwardedTurnRunningOnHolder(holder, peer, id);
        holder.session(id).failCurrentTurn(new RuntimeException("boom"));
        assertThat(awaitFailure(forwarded.getFuture())).hasMessageContaining("FAILED");

        assertThat(reservedWhenAnnounced).as("the failure must reach the rail exactly once for this key").hasSize(1);
        assertThat(reservedWhenAnnounced.get(0))
                .as("announcing first leaves a window in which a caller that retries the instant it is failed is told "
                        + "to collapse onto the attempt it was just told had died")
                .isFalse();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------------------------------------------

    /**
     * Drives the two nodes into the one state this fix is about: node B's keyed submission is out of its hands, its
     * reservation has been handed over holderless, and node A is running it as its next turn — one
     * {@code failCurrentTurn} away from a terminal failure announced for a key nobody holds.
     *
     * @return node B's forwarded disposition, whose future the failure below must end
     */
    private SubmitDisposition forwardedTurnRunningOnHolder(TestManagerHarness holder, TestManagerHarness peer,
            SessionId id) throws InterruptedException {
        final SubmitDisposition local = holder.manager().submit(RequestFixtures.submit(id, AGENT, "first"));
        assertThat(local.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).as("the holder must be occupied before the peer submits").isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(keyed(id, INPUT));
        assertThat(forwarded.getKind()).as("node-B cannot win a lock node-A is holding")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // Both tests are vacuous without this: the reservation has to survive the forward (otherwise there is nothing
        // to free) and it has to be holderless (otherwise compareAndReset would already reach it and no new SPI method
        // would be needed).
        final Optional<IdempotencyEntry> reserved = idempotency.find(KEY);
        assertThat(reserved).as("the forwarded key must stay reserved").isPresent();
        assertThat(reserved.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(reserved.orElseThrow().getHolderId()).as("a queued reservation has no holder").isEmpty();

        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).as("the holder must drain node-B's message as its next turn").isTrue();
        // The count is recorded a few instructions before the turn is completable, so failing on it alone can find
        // nothing in flight.
        assertThat(session.awaitTurnInFlight()).isTrue();
        return forwarded;
    }

    private static SubmitRequest keyed(SessionId id, String input) {
        return SubmitRequest.builder().sessionId(id).agentRef(AGENT).userInput(input)
                .initiator(Principal.user("tester")).idempotencyKey(KEY).build();
    }

    /** A manager sharing this test's backend, registered for teardown. */
    private TestManagerHarness node(String nodeId) {
        final TestManagerHarness harness = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency).build();
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

    private static Throwable awaitFailure(CompletionStage<AgentExecutionResult> stage) throws Exception {
        try {
            stage.toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            return ee.getCause() != null ? ee.getCause() : ee;
        }
        throw new AssertionError("Expected the forwarded future to fail, but it completed");
    }
}
