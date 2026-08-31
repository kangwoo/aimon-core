package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * The forwarded-turn return path (design §7.1 F0/F3/F6/F7): a submission that loses the session lock is answered
 * by
 * whichever node runs it, and the answer travels back over the {@code TURN_RESULT} rail.
 *
 * <p>
 * Before this rail existed, {@code FORWARDED} was a dead end — the submitting node handed its caller no future at all,
 * so a client whose request happened to land on a non-holder node simply never learned the outcome. Every test here
 * therefore asserts on {@link SubmitDisposition#getFuture()} of a forwarded disposition, which is the whole point of
 * the
 * stage. What varies is <em>how</em> the outcome gets home: the rail, the polling fallback when the rail loses the
 * announcement, or one of the paths that must fail the future because no node will ever run the turn.
 *
 * <p>
 * Two managers over one set of shared SPIs stand in for two cluster nodes, as in
 * {@code SessionRouterSignalSubscriptionTest}: everything a real deployment keeps in a shared backend (lock,
 * rail,
 * inbox, history, idempotency) is shared; only the node identity and the node-local caches differ.
 */
@DisplayName("SessionRouter forwarded-turn result rail")
class SessionRouterForwardedTurnResultTest {

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
    @DisplayName("the holder's TURN_RESULT completes the future the submitting node handed its caller")
    void railCompletesTheForwardedFuture() throws Exception {
        final TestManagerHarness holder = node("node-A");
        final TestManagerHarness peer = node("node-B");
        final SessionId id = SessionId.of("c-fwd-1");

        final SubmitDisposition local = holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        assertThat(local.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(RequestFixtures.submit(id, "alpha", "second"));
        assertThat(forwarded.getKind()).as("node-B cannot win a lock node-A is holding")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(peer.session(id)).as("a forwarding node must not open a session of its own").isNull();
        assertThat(forwarded.getFuture().toCompletableFuture()).isNotDone();

        completeWhenReady(session, TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).as("the holder must drain node-B's message as its next turn").isTrue();
        completeWhenReady(session, TestLiveSession.ok("second-done"));

        assertThat(local.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("first-done");
        final AgentExecutionResult remote = forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(remote.getFinalAnswer()).as("node-B's caller is answered by the turn node-A ran")
                .isEqualTo("second-done");
        assertThat(remote.isSuccess()).isTrue();

        assertThat(session.submittedInputs()).containsExactly("first", "second");
        assertThat(session.submittedTurnIds()).as("the holder must run the forwarded turn under the id node-B issued")
                .containsExactly(local.getTurnId(), forwarded.getTurnId());
    }

    @Test
    @DisplayName("a turn that throws on the holder fails the forwarded future instead of stranding it")
    void railPropagatesHolderSideFailure() throws Exception {
        final TestManagerHarness holder = node("node-A");
        final TestManagerHarness peer = node("node-B");
        final SessionId id = SessionId.of("c-fwd-2");

        final SubmitDisposition local = holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();
        final SubmitDisposition forwarded = peer.manager().submit(RequestFixtures.submit(id, "alpha", "second"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        completeWhenReady(session, TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).isTrue();
        failWhenReady(session, new RuntimeException("boom"));

        assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED").hasMessageContaining("boom")
                .hasMessageContaining(forwarded.getTurnId().value());
        assertThat(local.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .as("a failed sibling turn must not take the holder's own answer with it").isEqualTo("first-done");
    }

    @Test
    @DisplayName("a MESSAGE_ENQUEUED doorbell drains a message no holder was left to collect")
    void doorbellDrainsAStrandedMessage() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-fwd-3");
        // An unbound record — a holder that crashed between winning the lock and recording a binding, which is
        // precisely the case the drain-only pass's fallback exists for. The seed only models that crash; the pass no
        // longer depends on it, because provision creates the record in the same call that binds it.
        repository.provision(id);

        // Somebody outside this manager holds the lock, so the submit can only forward — and the drain-on-delivery
        // doorbell it rings itself finds the lock taken and gives up. That is exactly the window the rail exists for:
        // the message is in the inbox with no holder scheduled to look at it again.
        final SessionLease outsider = leaseStore.tryAcquire(id, "outsider", Duration.ofSeconds(30)).orElseThrow();
        final SubmitDisposition forwarded = harness.manager().submit(RequestFixtures.submit(id, "alpha", "stranded"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(neverOpensSession(harness, id)).as("the message must stay stranded while the lock is held").isTrue();

        leaseStore.release(outsider);
        // A foreign origin node id is required: onSignal drops self-origin signals, so a doorbell published as node-A
        // would be ignored by node-A.
        bus.publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.MESSAGE_ENQUEUED)
                .originNodeId("node-Z").payload(Map.of("inboxId", forwarded.getInboxId().orElseThrow().value(),
                        "turnId", forwarded.getTurnId().value()))
                .build());

        final TestLiveSession session = awaitSession(harness, id);
        assertThat(session.awaitTurnStarted()).isTrue();
        completeWhenReady(session, TestLiveSession.ok("drained"));

        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("drained");
        assertThat(session.submittedInputs()).containsExactly("stranded");
        assertThat(session.submittedTurnIds()).containsExactly(forwarded.getTurnId());
        // No submit ever reached runTurnLoop here, so the drain-only pass's fallback is the only thing that could have
        // bound the session — which is what proves the turn ran through that path.
        assertThat(repository.load(id).flatMap(SessionRecordView::getAgentRef))
                .as("a drain-only pass must record the binding it claimed").hasValue("alpha");
        assertThat(inbox.isEmpty(id)).isTrue();
    }

    @Test
    @DisplayName("the polling fallback answers the caller when the rail loses the announcement")
    void pollingFallbackAnswersWhenTheRailDropsTheSignal() throws Exception {
        // A rolling upgrade is the real case: an older subscriber decodes the kind with SignalKind.valueOf, throws on
        // TURN_RESULT, and drops the whole signal. Modelled here by a rail that never carries the announcement at all.
        final TurnResultDroppingBus deafRail = new TurnResultDroppingBus(bus);
        final TestManagerHarness holder = node("node-A", b -> b.signalBus(deafRail));
        // Shortening the secondary TTL is also what shortens the forward poll interval (floored at one second).
        final TestManagerHarness peer = node("node-B", b -> b.idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-fwd-4");

        holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(keyed(id, "second"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        completeWhenReady(session, TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).isTrue();
        completeWhenReady(session, TestLiveSession.ok("second-done"));

        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .as("the store, re-read on a timer, is what makes the rail optional rather than load-bearing")
                .isEqualTo("second-done");
        assertThat(deafRail.dropped()).as("the test is vacuous unless the rail really lost the announcement")
                .isPositive();
        final Optional<IdempotencyEntry> entry = idempotency.find(KEY);
        assertThat(entry).isPresent();
        assertThat(entry.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
    }

    @Test
    @DisplayName("a queued message naming a different agent is REJECTED back to the node that queued it")
    void conflictingAgentRefIsRejectedBackToTheSubmitter() throws Exception {
        final TestManagerHarness holder = node("node-A");
        // Node B resolves the session from its own repository, standing in for the window where A has won the lock
        // but B has not yet seen the binding A recorded. Without that window B's own resolveAndValidate would throw
        // ConflictingAgentException up front and the message would never reach the inbox — which is a different,
        // already
        // covered path. The one under test is the holder refusing a message that did get queued.
        final TestManagerHarness peer = node("node-B", b -> b.repository(new InMemorySessionRecordStore()));
        final SessionId id = SessionId.of("c-fwd-5");
        final List<Map<String, Object>> announced = tapTurnResults(id);

        final SubmitDisposition local = holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(RequestFixtures.submit(id, "beta", "wrong agent"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        completeWhenReady(session, TestLiveSession.ok("first-done"));

        assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REJECTED").hasMessageContaining("alpha").hasMessageContaining("beta");
        assertThat(local.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("first-done");
        assertThat(session.submittedInputs()).as("a refused message must never reach the session")
                .containsExactly("first");
        // Literal keys on purpose: this is the wire shape a peer decodes, and TurnResultPayloadTest owns the mapping
        // from the internal constants to these names.
        assertThat(announced).anySatisfy(payload -> assertThat(payload).containsEntry("outcome", "REJECTED")
                .containsEntry("turn", forwarded.getTurnId().value()));
    }

    @Test
    @DisplayName("releaseSession fails the forwards whose queued messages it just purged")
    void releaseFailsOutstandingForwards() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-fwd-6");

        final SessionLease outsider = leaseStore.tryAcquire(id, "outsider", Duration.ofSeconds(30)).orElseThrow();
        try {
            final SubmitDisposition forwarded = harness.manager().submit(RequestFixtures.submit(id, "alpha", "queued"));
            assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

            harness.manager().releaseSession(id);

            assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("was released");
            assertThat(inbox.isEmpty(id)).as("the message backing that future is provably gone").isTrue();
        } finally {
            leaseStore.release(outsider);
        }
    }

    @Test
    @DisplayName("a peer's EVICT fails a forward whose message that peer purged")
    void peerEvictFailsRemoteForwards() throws Exception {
        final TestManagerHarness releasing = node("node-A");
        final TestManagerHarness peer = node("node-B");
        final SessionId id = SessionId.of("c-fwd-7");

        final SessionLease outsider = leaseStore.tryAcquire(id, "outsider", Duration.ofSeconds(30)).orElseThrow();
        try {
            final SubmitDisposition forwarded = peer.manager().submit(RequestFixtures.submit(id, "alpha", "queued"));
            assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

            releasing.manager().releaseSession(id);

            assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                    .as("the EVICT must name the node that purged the inbox").hasMessageContaining("node-A")
                    .hasMessageContaining("was released or deleted");
        } finally {
            leaseStore.release(outsider);
        }
    }

    @Test
    @DisplayName("shutdown fails outstanding forwards rather than leaving futures nobody can complete")
    void shutdownFailsOutstandingForwards() throws Exception {
        final TestManagerHarness harness = node("node-A");
        final SessionId id = SessionId.of("c-fwd-8");

        final SessionLease outsider = leaseStore.tryAcquire(id, "outsider", Duration.ofSeconds(30)).orElseThrow();
        try {
            final SubmitDisposition forwarded = harness.manager().submit(RequestFixtures.submit(id, "alpha", "queued"));
            assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

            // The poll task that would otherwise resolve this forward dies with the scheduler, so shutdown has to
            // answer the caller itself.
            harness.manager().close();

            assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("shut down before this forwarded turn");
        } finally {
            leaseStore.release(outsider);
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------------------------------------------

    private static final String KEY = "k-fwd-4";

    private static SubmitRequest keyed(SessionId id, String input) {
        return SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput(input)
                .initiator(Principal.user("tester")).idempotencyKey(KEY).build();
    }

    private TestManagerHarness node(String nodeId) {
        return node(nodeId, b -> {
        });
    }

    /**
     * A manager sharing this test's backend, registered for teardown. The customizer runs last, so a test can swap one
     * collaborator (its own repository, a lossy rail) while keeping the rest shared.
     *
     * @param nodeId
     *            the node identity, which is also what {@code onSignal} dedups self-broadcast on
     * @param customizer
     *            applied to the builder before {@code build()}
     * @return the harness
     */
    private TestManagerHarness node(String nodeId, Consumer<TestManagerHarness.Builder> customizer) {
        final TestManagerHarness.Builder builder = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency);
        customizer.accept(builder);
        final TestManagerHarness harness = builder.build();
        nodes.add(harness);
        return harness;
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

    private static TestLiveSession awaitSession(TestManagerHarness harness, SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("a session for %s should have been opened", id).isNotNull();
        return session;
    }

    /**
     * Confirms no session appears for {@code id} within a short window. Deliberately not the full await budget: the
     * doorbell {@code deliverToInbox} rings is dispatched immediately, so this only has to outlast that one attempt.
     */
    private static boolean neverOpensSession(TestManagerHarness harness, SessionId id) throws InterruptedException {
        Thread.sleep(200L);
        return harness.session(id) == null;
    }

    private static void completeWhenReady(TestLiveSession session, AgentExecutionResult result)
            throws InterruptedException {
        applyWhenReady(session, s -> s.completeCurrentTurn(result));
    }

    private static void failWhenReady(TestLiveSession session, Throwable cause) throws InterruptedException {
        applyWhenReady(session, s -> s.failCurrentTurn(cause));
    }

    /**
     * Completes the session's current turn, retrying past the window where there is not one yet.
     *
     * <p>
     * {@code TestLiveSession} records a submitted input before it installs that turn's future, so
     * {@code awaitTurnCount} can return a beat before the turn is completable. Retrying is safe rather than
     * double-completing: {@code completeCurrentTurn} swaps the future out before it checks it for null, so a losing
     * attempt observes {@code null} and throws without touching a turn.
     */
    private static void applyWhenReady(TestLiveSession session, Consumer<TestLiveSession> action)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (true) {
            try {
                action.accept(session);
                return;
            } catch (IllegalStateException noTurnYet) {
                if (System.currentTimeMillis() >= deadline) {
                    throw noTurnYet;
                }
                Thread.sleep(5L);
            }
        }
    }

    private static Throwable awaitFailure(CompletionStage<AgentExecutionResult> stage) throws Exception {
        try {
            stage.toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            return ee.getCause() != null ? ee.getCause() : ee;
        }
        throw new AssertionError("Expected the forwarded future to fail, but it completed");
    }

    /**
     * A rail that silently swallows {@code TURN_RESULT} publishes — the in-process stand-in for the announcement being
     * lost in transit or rejected by a subscriber running an older build. Subscriptions pass straight through, so a
     * node
     * wired to this bus still hears everything its peers publish.
     */
    private static final class TurnResultDroppingBus implements SessionSignalBus {

        private final SessionSignalBus delegate;
        private final AtomicInteger dropped = new AtomicInteger();

        TurnResultDroppingBus(SessionSignalBus delegate) {
            this.delegate = delegate;
        }

        @Override
        public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
            return delegate.subscribe(id, handler);
        }

        @Override
        public void publish(SessionSignal signal) {
            if (signal.getKind() == SessionSignal.SignalKind.TURN_RESULT) {
                dropped.incrementAndGet();
                return;
            }
            delegate.publish(signal);
        }

        int dropped() {
            return dropped.get();
        }
    }
}
