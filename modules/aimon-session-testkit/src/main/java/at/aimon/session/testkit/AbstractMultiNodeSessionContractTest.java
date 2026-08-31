package at.aimon.session.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.PutResult;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.InterruptedAt;
import at.aimon.core.agent.stream.IterationStarted;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.ClusterSessionStatus;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.SubmitRequest;
import at.aimon.session.routing.internal.DefaultSessionRouter;

/**
 * What two {@link SessionRouter} nodes owe each other over a shared backend, executed against each backend.
 *
 * <p>
 * Its subject is the half of the session model that only exists between nodes: which node owns a turn, where a
 * message goes when the other node holds the lock, whether an interrupt reaches the holder, what a surviving node
 * says when the holder dies mid-turn. None of that is visible to a single-node test, and none of it is implied by any
 * one SPI's own tests — a backend can satisfy {@code SessionLeaseStore} and {@code SessionSignalBus} separately and
 * still route a turn to the wrong place.
 *
 * <p>
 * A backend joins by subclassing, returning a {@link SessionBackendFactory} from {@link #backend()}, and tagging
 * itself {@code @Tag("docker")}. There were three copies of these seven scenarios, one per backend module, with the
 * method names identical down to the letter; each carried a javadoc line promising it mirrored the others, which is
 * the comment a duplicate writes when it has no way to prove it.
 *
 * <h2>Waiting</h2>
 *
 * <p>
 * Three windows differ per backend, and only ever in the widening direction — Redis pub/sub settles fastest, a
 * Postgres {@code LISTEN} dispatcher needs a poll cycle, MongoDB change streams need a cursor. The defaults here are
 * the Redis values and a backend overrides what it needs.
 *
 * <p>
 * {@link #settle()} is the one to be careful with. The old suites used two different settle values within the same
 * backend — Postgres slept 200ms in one scenario and 300ms in another, MongoDB 500ms and 300ms, inverted between the
 * two — and nothing distinguished the cases. One value per backend replaces the pair, the wider of the two, so no
 * scenario waits less than it used to.
 */
public abstract class AbstractMultiNodeSessionContractTest {

    /** How long the negative "the stream did not end" check waits. Deliberately short: it must not find anything. */
    private static final long NOT_TERMINATED_MILLIS = 300L;

    private final List<AutoCloseable> extraCloseables = new ArrayList<>();

    private TwoNodeSessionHarness harness;

    /**
     * @return this backend's factory. Called once per scenario, so an implementation may return a fresh instance or a
     *         shared one, as long as {@link SessionBackendFactory#reset()} really clears the container
     */
    protected abstract SessionBackendFactory backend();

    /**
     * How long to let a subscription take effect before the other node publishes. Not a timeout — a plain sleep — so
     * this one is paid in full by every run.
     *
     * @return the settle window, 150ms unless a backend widens it
     */
    protected Duration settle() {
        return Duration.ofMillis(150);
    }

    /**
     * The bound on a signal, status snapshot or relayed event reaching the other node. A timeout, so widening it costs
     * nothing when the backend is healthy.
     *
     * @return the propagation bound, 3s unless a backend widens it
     */
    protected Duration propagationTimeout() {
        return Duration.ofSeconds(3);
    }

    /**
     * The bound on the surviving node's sweeper noticing a stale {@code IN_FLIGHT} entry. Wider than
     * {@link #propagationTimeout()} because it covers several sweep ticks, not one publish.
     *
     * @return the sweep bound, 5s unless a backend widens it
     */
    protected Duration holderLossTimeout() {
        return Duration.ofSeconds(5);
    }

    /**
     * @param lockLease
     *            how long a won lease stays valid without renewal
     * @param lockExtendInterval
     *            renewal cadence, strictly shorter than {@code lockLease}
     * @return a two-node harness on this backend, closed after the scenario
     */
    protected final TwoNodeSessionHarness newHarness(Duration lockLease, Duration lockExtendInterval) {
        harness = new TwoNodeSessionHarness(backend(), lockLease, lockExtendInterval);
        return harness;
    }

    /**
     * @param lockLease
     *            how long a won lease stays valid without renewal
     * @param lockExtendInterval
     *            renewal cadence, strictly shorter than {@code lockLease}
     * @param holderLossSweepInterval
     *            how often each node sweeps for stale {@code IN_FLIGHT} entries
     * @param idempotencySecondaryTtl
     *            how long an {@code IN_FLIGHT} entry may go untouched before a sweeper may claim it
     * @return a two-node harness on this backend, closed after the scenario
     */
    protected final TwoNodeSessionHarness newHarness(Duration lockLease, Duration lockExtendInterval,
            Duration holderLossSweepInterval, Duration idempotencySecondaryTtl) {
        harness = new TwoNodeSessionHarness(backend(), lockLease, lockExtendInterval, holderLossSweepInterval,
                idempotencySecondaryTtl);
        return harness;
    }

    @AfterEach
    final void closeHarness() {
        if (harness != null) {
            harness.close();
            harness = null;
        }
        for (AutoCloseable c : extraCloseables) {
            try {
                c.close();
            } catch (Exception ignored) {
                /* test teardown */
            }
        }
        extraCloseables.clear();
    }

    @Test
    @DisplayName("concurrent submit from two nodes: exactly one EXECUTED_LOCALLY, the other FORWARDED")
    void concurrentSubmitOneNodeOwnsTurn() throws Exception {
        final TwoNodeSessionHarness nodes = newHarness(Duration.ofSeconds(30), Duration.ofSeconds(10));
        final SessionId id = SessionId.of("c-multi-1");

        final SubmitDisposition a = nodes.nodeA().manager().submit(submit(id, "hello-A"));
        // small wait so node A definitely owns the lock before B tries
        Thread.sleep(50);
        final SubmitDisposition b = nodes.nodeB().manager().submit(submit(id, "hello-B"));

        assertThat(a.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        assertThat(b.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(b.getInboxId()).isPresent();

        // Node A's session was opened and started its turn.
        final RecordingTestSession sessionA = nodes.nodeA().session(id);
        assertThat(sessionA).as("node A session opened").isNotNull();
        assertThat(sessionA.awaitTurnStarted(2_000L)).isTrue();
        assertThat(sessionA.submittedInputs()).containsExactly("hello-A");

        // Node B never opened a session locally because the lock was held elsewhere.
        assertThat(nodes.nodeB().session(id)).as("node B should not have opened a local session").isNull();

        sessionA.completeCurrentTurn(RecordingTestSession.ok("done"));
    }

    @Test
    @DisplayName("cross-node interrupt: B's interrupt() trips A's active session via the signal bus")
    void crossNodeInterrupt() throws Exception {
        final TwoNodeSessionHarness nodes = newHarness(Duration.ofSeconds(30), Duration.ofSeconds(10));
        final SessionId id = SessionId.of("c-multi-2");

        // Subscribing to events on both sides also wires the signal bus subscription that delivers INTERRUPT.
        nodes.nodeA().manager().events(id);
        nodes.nodeB().manager().events(id);
        Thread.sleep(settle().toMillis());

        final SubmitDisposition a = nodes.nodeA().manager().submit(submit(id, "long-running"));
        assertThat(a.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        Thread.sleep(50);

        final RecordingTestSession sessionA = nodes.nodeA().session(id);
        assertThat(sessionA).as("node A session opened").isNotNull();
        assertThat(sessionA.awaitTurnStarted(2_000L)).isTrue();

        // Node B asks the cluster to interrupt — it doesn't hold the lock, so the signal must reach A.
        nodes.nodeB().manager().interrupt(id, InterruptReason.USER_SIGINT);

        assertThat(sessionA.awaitInterrupt(propagationTimeout().toMillis())).as("signal must propagate to lock holder")
                .isTrue();
        assertThat(sessionA.recordedInterrupts()).contains(InterruptReason.USER_SIGINT);

        sessionA.completeCurrentTurn(RecordingTestSession.ok("interrupted-then-done"));
    }

    @Test
    @DisplayName("lock fail-over: A's lease expires without explicit release, B can then acquire")
    void lockExpiryAllowsFailOver() {
        // Skip the manager — go straight to the SPI to test pure lock semantics.
        newHarness(Duration.ofSeconds(60), Duration.ofSeconds(30));
        final SessionId id = SessionId.of("c-multi-3");

        // Distinct lock instances on connections of their own, mirroring the cross-node setup.
        final SessionLeaseStore lockA = backend().createLeaseStore(extraCloseables::add);
        final SessionLeaseStore lockB = backend().createLeaseStore(extraCloseables::add);

        // A acquires with a 1-second lease and "abandons" it (never releases, never extends).
        final Optional<SessionLease> handleA = lockA.tryAcquire(id, "node-A", Duration.ofSeconds(1));
        assertThat(handleA).isPresent();

        // Immediately after acquire B sees the lock held.
        assertThat(lockB.tryAcquire(id, "node-B", Duration.ofMillis(100))).isEmpty();

        // After the lease expires, B should be able to acquire.
        try {
            Thread.sleep(1_300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        final Optional<SessionLease> handleB = lockB.tryAcquire(id, "node-B", Duration.ofSeconds(5));
        assertThat(handleB).as("after lease expiry the second node should acquire").isPresent();
        // Within a live chain (no release in between) the token strictly increases.
        assertThat(handleB.orElseThrow().getFencingToken()).isGreaterThan(handleA.orElseThrow().getFencingToken());
    }

    @Test
    @DisplayName("submit from non-holder lands in shared inbox; drained by holder on next collect")
    void deliveredMessageVisibleToHolderInbox() throws Exception {
        final TwoNodeSessionHarness nodes = newHarness(Duration.ofSeconds(30), Duration.ofSeconds(10));
        final SessionId id = SessionId.of("c-multi-4");

        final SubmitDisposition a = nodes.nodeA().manager().submit(submit(id, "first"));
        assertThat(a.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        Thread.sleep(50);
        final RecordingTestSession sessionA = nodes.nodeA().session(id);
        assertThat(sessionA.awaitTurnStarted(2_000L)).isTrue();

        // B submits while A holds the lock — must go to the inbox, not run locally.
        final SubmitDisposition b = nodes.nodeB().manager().submit(submit(id, "second"));
        assertThat(b.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // Drain the inbox through a connection neither node owns, to confirm the message really materialized in the
        // shared backend rather than in the sender's memory.
        final SessionInbox observer = backend().createInbox(extraCloseables::add);
        final List<InboundMessage> seen = observer.collect(id, QueuedInputPriority.LATER);
        assertThat(seen).extracting(InboundMessage::getUserInput).contains("second");

        sessionA.completeCurrentTurn(RecordingTestSession.ok("done"));
    }

    @Test
    @DisplayName("forwarded turn: A's TURN_RESULT completes the future B handed its caller, over the real signal bus")
    void forwardedTurnResultReachesTheSubmittingNode() throws Exception {
        final TwoNodeSessionHarness nodes = newHarness(Duration.ofSeconds(30), Duration.ofSeconds(10));
        final SessionId id = SessionId.of("c-multi-fwd-1");

        final SubmitDisposition a = nodes.nodeA().manager().submit(submit(id, "first"));
        assertThat(a.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final RecordingTestSession sessionA = awaitSession(nodes.nodeA(), id, 2_000L);
        assertThat(sessionA).as("node A opened its session").isNotNull();
        assertThat(sessionA.awaitTurnStarted(2_000L)).isTrue();

        // B cannot win a lock A is holding, so its message is forwarded through the shared inbox. submit() carries no
        // idempotency key, and that is what makes this a test of the rail rather than of the polling fallback: with
        // nothing durable to poll, the only thing that can complete this future is A's TURN_RESULT crossing the bus.
        final SubmitDisposition b = nodes.nodeB().manager().submit(submit(id, "second"));
        assertThat(b.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(nodes.nodeB().session(id)).as("a forwarding node must not open a session of its own").isNull();

        sessionA.completeWhenReady(RecordingTestSession.ok("first-done"), 2_000L);
        assertThat(sessionA.awaitSubmittedInputs(2, 5_000L)).as("A must drain B's message as its next turn").isTrue();
        sessionA.completeWhenReady(RecordingTestSession.ok("second-done"), 2_000L);

        final AgentExecutionResult remote = b.getFuture().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(remote.getFinalAnswer()).as("B's caller is answered by the turn A ran").isEqualTo("second-done");
        assertThat(remote.isSuccess()).isTrue();
        assertThat(remote.getArtifacts()).as("a result that crossed a node boundary carries no artifacts").isEmpty();
        assertThat(sessionA.submittedInputs()).containsExactly("first", "second");
        assertThat(a.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .as("A's own turn is unaffected").isEqualTo("first-done");
    }

    @Test
    @DisplayName("holder crash: stale IN_FLIGHT entry trips HOLDER_LOST without ending the conversation")
    void holderCrashEmitsTurnScopedHolderLost() throws Exception {
        // Aggressive sweep + secondary TTL so the test doesn't sit on the design's 15s/30s defaults.
        final TwoNodeSessionHarness nodes = newHarness(Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofMillis(300), Duration.ofMillis(200));
        final SessionId id = SessionId.of("c-multi-5");

        // Simulate node A crashed: shut down its scheduler so only node B's sweeper can win compareAndReset. B emits
        // InterruptedAt(HOLDER_LOST) locally and announces the lost turn on the TURN_RESULT rail — and stops there.
        // Recovery used to also complete B's stream and broadcast an EVICT so every peer dropped the session, but a
        // lost holder is not a lost session: the lease expires and a successor claims it, so that teardown raced the
        // successor's claim() and could end a session whose next turn was already running elsewhere.
        nodes.nodeA().manager().close();

        final Flow.Publisher<AgentExecutionEvent> events = nodes.nodeB().manager().events(id);

        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch terminated = new CountDownLatch(1);
        final CopyOnWriteArrayList<AgentExecutionEvent> received = new CopyOnWriteArrayList<>();
        events.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
                ready.countDown();
            }

            @Override
            public void onNext(AgentExecutionEvent item) {
                received.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                /* not expected */ }

            @Override
            public void onComplete() {
                terminated.countDown();
            }
        });
        // Wait for the subscriber to register demand before planting the stale entry — otherwise the publisher's
        // zero-timeout offer drops the InterruptedAt event (InProcessEventPublisher §5.5.1 invariant).
        assertThat(ready.await(2, TimeUnit.SECONDS)).as("subscriber must register demand").isTrue();

        // Plant a stale IN_FLIGHT entry as if a now-dead holder had created it, through a connection neither node
        // owns so nothing about this depends on the harness's own wiring.
        final IdempotencyStore directStore = backend().createIdempotencyStore(extraCloseables::add);
        final Instant longAgo = Instant.now().minusSeconds(10);
        final IdempotencyEntry stale = IdempotencyEntry.builder().key("idem-key-dead").sessionId(id)
                .inputHash("hash-dead").status(IdempotencyEntry.Status.IN_FLIGHT).holderId("dead-node")
                .createdAt(longAgo).lastTouchedAt(longAgo).build();
        final PutResult putResult = directStore.putIfAbsent("idem-key-dead", stale, Duration.ofSeconds(60));
        assertThat(putResult.getKind()).as("planted entry must insert cleanly").isEqualTo(PutResult.Kind.INSERTED);

        // Sweeper on B should pick this up within a few sweep ticks (~300ms each).
        final long deadline = System.currentTimeMillis() + holderLossTimeout().toMillis();
        while (received.stream()
                .noneMatch(e -> e instanceof InterruptedAt ia && ia.getReason() == InterruptReason.HOLDER_LOST)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertThat(received).as("the surviving node must say why the turn stopped producing")
                .anyMatch(e -> e instanceof InterruptedAt ia && ia.getReason() == InterruptReason.HOLDER_LOST);
        assertThat(terminated.await(NOT_TERMINATED_MILLIS, TimeUnit.MILLISECONDS))
                .as("one dead attempt must not end the conversation's stream").isFalse();
    }

    @Test
    @DisplayName("cross-node observability: A's STATUS snapshot and a relayed EVENT both reach subscriber B")
    void crossNodeStatusAndEventRelay() throws Exception {
        final TwoNodeSessionHarness nodes = newHarness(Duration.ofSeconds(30), Duration.ofSeconds(10));
        final SessionId id = SessionId.of("c-multi-status-1");

        // Enable holder-side STATUS broadcast on node A (off by default for safe rollout).
        ((DefaultSessionRouter) nodes.nodeA().manager()).setStatusBroadcastEnabled(true);

        // B subscribes to events(id): wires the signal-bus subscription AND lets us capture relayed events.
        final CopyOnWriteArrayList<AgentExecutionEvent> bEvents = new CopyOnWriteArrayList<>();
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch eventSeen = new CountDownLatch(1);
        nodes.nodeB().manager().events(id).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
                ready.countDown();
            }

            @Override
            public void onNext(AgentExecutionEvent item) {
                bEvents.add(item);
                eventSeen.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                /* not expected */ }

            @Override
            public void onComplete() {
                /* not expected within the test window */ }
        });
        assertThat(ready.await(2, TimeUnit.SECONDS)).as("subscriber must register demand").isTrue();
        Thread.sleep(settle().toMillis()); // let the subscription take effect before A publishes

        // A runs a turn (holds the lock) → publishes a STATUS snapshot on turn start.
        final SubmitDisposition a = nodes.nodeA().manager().submit(submit(id, "hello"));
        assertThat(a.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        // runTurnLoop opens the session asynchronously; poll until it materializes before driving it.
        final RecordingTestSession sessionA = awaitSession(nodes.nodeA(), id, 2_000L);
        assertThat(sessionA).as("node A opened its session").isNotNull();
        assertThat(sessionA.awaitTurnStarted(2_000L)).isTrue();

        // STATUS: B folds A's snapshot and answers status() as REMOTE_PROJECTION from node-A.
        assertThat(awaitRemoteProjection(nodes.nodeB().manager(), id, propagationTimeout().toMillis()))
                .as("B observes A's STATUS snapshot across the cluster").isTrue();
        assertThat(nodes.nodeB().manager().status(id).getOriginNodeId()).contains("node-A");

        // EVENT relay (the cross-node latent-bug fix): A emits an event mid-turn; B reconstructs it.
        sessionA.emitEvent(IterationStarted.builder().timestamp(Instant.now())
                .agentRuntimeId(AgentRuntimeId.fromName("alpha")).iteration(1).plannedIteration(1).build());
        assertThat(eventSeen.await(propagationTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .as("B must receive A's relayed event").isTrue();
        assertThat(bEvents).anyMatch(e -> e instanceof IterationStarted);

        sessionA.completeCurrentTurn(RecordingTestSession.ok("done"));
    }

    private static boolean awaitRemoteProjection(SessionRouter manager, SessionId id, long millis)
            throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.status(id).getSource() == ClusterSessionStatus.Source.REMOTE_PROJECTION) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static RecordingTestSession awaitSession(TwoNodeSessionHarness.Node node, SessionId id, long millis)
            throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        RecordingTestSession session = node.session(id);
        while (session == null && System.nanoTime() < deadline) {
            Thread.sleep(25);
            session = node.session(id);
        }
        return session;
    }

    private static SubmitRequest submit(SessionId id, String input) {
        return SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput(input)
                .initiator(Principal.user("tester")).build();
    }
}
