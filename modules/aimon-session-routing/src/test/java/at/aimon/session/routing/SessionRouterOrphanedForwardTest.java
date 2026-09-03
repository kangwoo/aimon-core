package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionInboxException;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.session.routing.fixture.RecordingSessionMetrics;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * The retry that gets a forwarded message run when the node it was queued for is gone (design §6.3 D).
 *
 * <p>
 * A submission that loses the election is handed to the inbox and announced with {@code MESSAGE_ENQUEUED}. That
 * announcement is a one-shot: every peer that hears it and finds the session held gives up, on the understanding that
 * the holder re-rings from its lease-return path. A holder that <em>crashed</em> never runs that path — its lease
 * merely lapses on its TTL — so nothing re-ringing meant the message sat in the inbox until the session's next
 * submission, which may never come, while the caller waited out the whole forward TTL for a turn no node was running.
 *
 * <p>
 * Closed from the side that is actually waiting: the forward poll already ticks for exactly as long as a caller has an
 * unresolved forwarded turn, so it re-rings the doorbell on every tick. That bounds the recovery at one poll interval
 * past the lease expiry, and it costs nothing against a live holder — the drain pass it schedules cannot take a lease
 * that is still being renewed, which the second test pins.
 */
@DisplayName("SessionRouter orphaned-forward retry")
class SessionRouterOrphanedForwardTest {

    private SessionLeaseStore leaseStore;
    private SessionSignalBus bus;
    private SessionInbox inbox;
    private SessionRecordStore repository;
    private IdempotencyStore idempotency;

    private final List<TestManagerHarness> nodes = new ArrayList<>();

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
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).close();
        }
    }

    @Test
    @DisplayName("a message queued for a holder that died runs here once its lease lapses, with no second submit")
    void deadHoldersQueuedMessageRunsAfterTheLeaseLapses() throws Exception {
        final RecordingSessionMetrics metrics = new RecordingSessionMetrics();
        final TestManagerHarness node = node("node-A", b -> b.metrics(metrics)
                // Also the forward poll interval, floored at one second by the manager.
                .idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-orphan-1");

        // The lease the dead node was holding when it stopped. Nothing releases it — it lapses on its own TTL, which
        // is the only thing a crashed holder's lease ever does.
        leaseStore.tryAcquire(id, "dead-node", Duration.ofMillis(400)).orElseThrow();

        final SubmitDisposition forwarded = node.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(forwarded.getKind()).as("the lease is still held, so this submission can only be queued")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(node.session(id)).as("a forwarding node must not open a session of its own").isNull();

        // No further submit, no peer, no doorbell from anywhere: whatever opens this session was driven by the poll.
        final TestLiveSession session = awaitSession(node, id);
        assertThat(session.awaitTurnStarted()).isTrue();
        assertThat(session.submittedInputs()).containsExactly("hello");
        session.completeCurrentTurn(TestLiveSession.ok("done"));

        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .as("the caller is answered by the turn its own node ended up running").isEqualTo("done");
        assertThat(metrics.forwardDoorbellRerung.get()).as("the retry is what got there, not some other path")
                .isPositive();
        assertThat(inbox.isEmpty(id)).isTrue();
    }

    @Test
    @DisplayName("the retry cannot take a session away from a holder that is still renewing its lease")
    void retryLeavesALiveHolderAlone() throws Exception {
        final TestManagerHarness holder = node("node-A", b -> {
        });
        final RecordingSessionMetrics peerMetrics = new RecordingSessionMetrics();
        final TestManagerHarness peer = node("node-B",
                b -> b.metrics(peerMetrics).idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-orphan-2");

        holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(RequestFixtures.submit(id, "alpha", "second"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // Waited for rather than slept through: two ticks is what makes the assertions below mean something, and a
        // loaded CI box can take longer than two poll intervals to deliver them.
        assertThat(awaitRetries(peerMetrics, 2)).as("the test is vacuous unless the retry really fired").isTrue();
        assertThat(peer.session(id)).as("a retry must not open a session for one node-A is holding").isNull();
        assertThat(forwarded.getFuture().toCompletableFuture())
                .as("nor answer the caller before the holder has run the turn").isNotDone();
        assertThat(leaseStore.findHolder(id).orElseThrow().getHolderId()).isEqualTo("node-A");

        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).as("the holder's own post-turn re-collect drains the queued message")
                .isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("second-done"));
        assertThat(forwarded.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("second-done");
    }

    @Test
    @DisplayName("the retry goes quiet once the message is out of the inbox")
    void retryStopsOnceTheMessageIsCollected() throws Exception {
        final RecordingSessionMetrics metrics = new RecordingSessionMetrics();
        final TestManagerHarness node = node("node-A",
                b -> b.metrics(metrics).idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-orphan-4");

        final SessionLease outsider = leaseStore.tryAcquire(id, "outsider", Duration.ofSeconds(30)).orElseThrow();
        try {
            final SubmitDisposition forwarded = node.manager().submit(RequestFixtures.submit(id, "alpha", "queued"));
            assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
            assertThat(awaitRetries(metrics, 1)).as("a queued message is what the retry is for").isTrue();

            // Stands in for a holder collecting the message: from here the turn exists somewhere and its death, if it
            // comes, is the holder-loss sweeper's to report — there is nothing left for a doorbell to announce.
            inbox.purge(id);
            final int retriesSoFar = metrics.forwardDoorbellRerung.get();

            Thread.sleep(2_000L);
            assertThat(metrics.forwardDoorbellRerung.get()).as("an empty inbox must not be re-announced every tick")
                    .isEqualTo(retriesSoFar);
        } finally {
            leaseStore.release(outsider);
        }
    }

    @Test
    @DisplayName("a released session's forward is failed rather than retried into a session that no longer has it")
    void releaseStillWinsOverTheRetry() throws Exception {
        final TestManagerHarness node = node("node-A", b -> b.idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-orphan-3");

        final SessionLease outsider = leaseStore.tryAcquire(id, "outsider", Duration.ofSeconds(30)).orElseThrow();
        try {
            final SubmitDisposition forwarded = node.manager().submit(RequestFixtures.submit(id, "alpha", "queued"));
            assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

            node.manager().releaseSession(id);

            assertThat(awaitFailure(forwarded.getFuture())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("was released");
            // The poll task unregisters once its future is done, so the retry stops with it rather than re-ringing a
            // doorbell for a message the release just purged.
            Thread.sleep(1_500L);
            assertThat(node.session(id)).as("nothing may open a session for the message the release removed").isNull();
        } finally {
            leaseStore.release(outsider);
        }
    }

    @Test
    @DisplayName("an inbox that cannot say whether it is empty is re-rung anyway")
    void anUnreadableInboxIsRungAnyway() throws Exception {
        // Replaced before the node is built, so the manager sees only the blinded inbox.
        inbox = new BlindInbox(inbox);
        final RecordingSessionMetrics metrics = new RecordingSessionMetrics();
        final TestManagerHarness node = node("node-A",
                b -> b.metrics(metrics).idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-orphan-5");

        final SessionLease outsider = leaseStore.tryAcquire(id, "outsider", Duration.ofSeconds(30)).orElseThrow();
        try {
            final SubmitDisposition forwarded = node.manager().submit(RequestFixtures.submit(id, "alpha", "queued"));
            assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
            assertThat(awaitRetries(metrics, 1))
                    .as("being unable to see the queue is not evidence that the queue is empty").isTrue();
        } finally {
            leaseStore.release(outsider);
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------------------------------------------

    private TestManagerHarness node(String nodeId, Consumer<TestManagerHarness.Builder> customizer) {
        final TestManagerHarness.Builder builder = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency);
        customizer.accept(builder);
        final TestManagerHarness harness = builder.build();
        nodes.add(harness);
        return harness;
    }

    private static boolean awaitRetries(RecordingSessionMetrics metrics, int target) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (metrics.forwardDoorbellRerung.get() >= target) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
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

    private static Throwable awaitFailure(CompletionStage<?> stage) throws InterruptedException {
        try {
            stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected the stage to fail");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("the stage was never settled", e);
        }
    }

    /**
     * An inbox that delivers and collects normally but cannot answer {@code isEmpty} — the shape of a backend blip
     * against the one check that decides whether the retry fires.
     */
    private static final class BlindInbox implements SessionInbox {

        private final SessionInbox delegate;

        BlindInbox(SessionInbox delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        }

        @Override
        public InboundMessageId deliver(InboundMessage message) {
            return delegate.deliver(message);
        }

        @Override
        public List<InboundMessage> collect(SessionId id, QueuedInputPriority maxPriority) {
            return delegate.collect(id, maxPriority);
        }

        @Override
        public boolean isEmpty(SessionId id) {
            throw new SessionInboxException("inbox backend is unreachable");
        }

        @Override
        public void purge(SessionId id) {
            delegate.purge(id);
        }
    }
}
