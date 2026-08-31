package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.ScriptableLock;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Slice 7: what a mid-turn eviction may and may not cost the session.
 *
 * <p>
 * A pin has never stopped an eviction from taking the entry out of the cache; since 3b-2 it only defers the
 * {@code close()}. That leaves a window with no owner: the session has no cached session, so the next submission
 * misses the cache, yet the lease is still installed, so that submission is entitled to run here. Whether it does comes
 * down to the order of the turn loop's {@code finally}: {@code unpin()} — which is what performs the deferred close —
 * then {@code returnLeaseIfUnowned}, and only then {@code endTurn}. The first test pins both of the orderings that
 * implies, by probing at the two moments a wrong order would have left the gate open:
 *
 * <ul>
 * <li><b>while the deferred close runs</b>, reached by parking the session double's {@code close()}. Moving
 * {@code endTurn} above {@code unpin()} makes this probe find an open gate.
 * <li><b>while the lease is being handed back</b>, reached by parking the lease store's {@code release()}
 * <em>after</em>
 * the backend has already taken the lease — where a real remote store spends its round trip. Moving {@code endTurn}
 * above {@code returnLeaseIfUnowned} makes this probe find an open gate.
 * </ul>
 *
 * <p>
 * The second probe cannot be folded into the first window, and that is the whole reason it exists. While the close is
 * parked the turn holds the gate whatever order the two calls after the unpin are in, so a probe taken there is
 * answered {@code FORWARDED} by the gate alone — it says nothing about the lease and stays green against a turn loop
 * that hands the lease back only after opening the gate. Only a probe taken from inside {@code release} can tell the
 * two orders apart: the gate is provably still held there in the current order, and provably already open in the
 * reversed one.
 *
 * <p>
 * The idle-TTL half of the same problem is not here but in {@code LiveSessionCacheTest} — a pinned entry is never
 * idle, so at that level there is no window to test.
 */
@DisplayName("SessionRouter mid-turn eviction")
class SessionRouterMidTurnEvictionTest {

    private static final String NODE_ID = "node-pin";
    private static final String PEER_ID = "node-peer";

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("the gate stays shut for the whole tear-down: the deferred close and the lease hand-back")
    void noSubmissionSlipsInBetweenTheEvictionAndTheLeaseGoingBack() throws Exception {
        final ReleaseParkingLeaseStore leases = new ReleaseParkingLeaseStore(new InMemorySessionLeaseStore());
        harness = TestManagerHarness.builder().nodeId(NODE_ID).leaseStore(leases).build();
        final SessionId id = SessionId.of("c-pin-turn-1");

        final SubmitDisposition first = harness.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        assertThat(first.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // A peer asks for the session. The entry leaves the cache immediately; the close waits for the turn.
        publishYield(id);
        assertThat(session.awaitInterrupt()).isTrue();

        // Park the close, then let the turn finish: the turn thread now sits inside the unpin that performs the
        // eviction's deferred close — no cached session, lease not yet handed back, gate held.
        session.blockClose();
        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitCloseEntered()).as("the unpin must perform the eviction's deferred close").isTrue();
        assertThat(first.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("first-done");

        // Probe 1 — the unpin runs inside the gate. This one is answered by the gate and by nothing else: in both
        // orderings this test tells apart the lease is still installed here (both leave returnLeaseIfUnowned after the
        // unpin), so a FORWARDED verdict says the turn had not opened the gate before handing its session over to the
        // close, and says nothing at all about when the lease went back.
        final SubmitDisposition duringClose = harness.manager().submit(RequestFixtures.submit(id, "alpha", "second"));
        assertThat(duringClose.getKind()).as("the gate must still be closed while the eviction's close runs")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(session.submittedInputs()).as("no turn may start on a session that is being torn down")
                .containsExactly("first");
        assertThat(harness.inbox().isEmpty(id)).as("the message waits instead").isFalse();

        // Probe 2 — the lease goes back inside the gate. Releasing the close lets the turn thread out of the unpin and
        // straight into the lease return, where the store parks it once the backend has already taken the lease. That
        // is the one instant at which the two orderings differ observably: here the gate is still held, whereas a turn
        // loop that called endTurn first would have opened it, and this submission would re-claim the free lease, miss
        // the cache and open a second session for a session whose previous one has only just stopped emitting.
        leases.armRelease();
        session.releaseClose();
        assertThat(leases.awaitReleaseEntered()).as("the turn loop must hand the lease back on its way out").isTrue();
        final SubmitDisposition duringLeaseReturn = harness.manager()
                .submit(RequestFixtures.submit(id, "alpha", "third"));
        leases.resumeRelease();
        assertThat(duringLeaseReturn.getKind())
                .as("the lease must go back inside the gate, not after it — a submission arriving while the "
                        + "hand-back is in flight must still be refused")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(session.submittedInputs()).as("still no turn on the torn-down session").containsExactly("first");

        // Deliberately no assertion on either queued message running: the harness opens one session double per
        // session, so the local re-drain that the doorbell triggers here reuses the closed one and fails. What a
        // re-drain does with a live session is `secondSubmissionDuringATurnIsQueued`'s subject, not this test's.
        assertThat(awaitClosed(session)).as("the released close still completes").isTrue();
    }

    @Test
    @DisplayName("LEASE_LOST still reaches a turn whose cache entry the eviction already took away")
    void leaseLostReachesTheEvictedButStillRunningTurn() throws Exception {
        final ScriptableLock lock = new ScriptableLock(new InMemorySessionLeaseStore());
        harness = TestManagerHarness.builder().nodeId(NODE_ID).leaseStore(lock).lockLease(Duration.ofMillis(500))
                .lockExtendInterval(Duration.ofMillis(50)).build();
        final SessionId id = SessionId.of("c-pin-turn-2");

        final SubmitDisposition first = harness.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        assertThat(first.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // Put the session into the window: the entry leaves the cache now, the close waits for the turn, and the lease
        // stays installed because the turn holds the gate — so renewal goes on ticking for a session the cache can no
        // longer name.
        publishYield(id);
        assertThat(session.awaitInterrupt()).as("the yield must stop the turn").isTrue();

        // Now take the lease away underneath it. This is the one interrupt that is not advisory: the turn is still
        // appending history under a lease the backend has reassigned, and the only handle on it is the node-local
        // turn registry. Looked up through the cache, LEASE_LOST would be dropped precisely here.
        lock.startFailingExtends();
        assertThat(awaitInterrupt(session, InterruptReason.LEASE_LOST))
                .as("LEASE_LOST must reach the turn even with no cache entry to find it by, saw %s",
                        session.recordedInterrupts())
                .isTrue();

        // Polled rather than awaited: awaitInterrupt's latch is one-shot and the yield above already spent it.
        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        first.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static boolean awaitInterrupt(TestLiveSession session, InterruptReason reason) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (session.recordedInterrupts().contains(reason)) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private void publishYield(SessionId id) {
        // originNodeId must differ from this manager's own or the same-origin filter drops it before the handler.
        harness.signalBus().publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.YIELD)
                .originNodeId(PEER_ID).build());
    }

    private TestLiveSession waitForSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(harness.session(id)).as("session for %s should be opened", id).isNotNull();
        return harness.session(id);
    }

    private static boolean awaitClosed(TestLiveSession session) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (!session.isClosed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return session.isClosed();
    }

    /**
     * Lease store that lets one {@code release} be parked mid-call, so a test can occupy the instant at which a lease
     * is on its way back to the cluster.
     *
     * <p>
     * The park is <em>after</em> the delegate, not before, and that is what makes the probe mean anything. Park first
     * and the backend still shows the lease as this node's, so a probing submission is refused by the election however
     * the turn loop is ordered — the test would pass on the wrong reason a second time. Delegated first, the only
     * thing standing between the probe and a second live session is the turn gate, which is exactly the ordering under
     * test. It is also the more faithful of the two: a lease release against a remote store lands long before the call
     * returns, and the turn thread sits in the rest of that round trip.
     *
     * <p>
     * One-shot and explicitly armed, so the drain pass the doorbell triggers after this window cannot park too, and
     * the wait is bounded so a test that fails before resuming fails on its assertion rather than hanging the suite.
     */
    private static final class ReleaseParkingLeaseStore implements SessionLeaseStore {

        private final SessionLeaseStore delegate;
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        ReleaseParkingLeaseStore(SessionLeaseStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        }

        /** Arms the next {@code release} — and only the next one — to park. */
        void armRelease() {
            armed.set(true);
        }

        /** Waits up to {@link TestLiveSession#DEFAULT_AWAIT_MS} for the armed {@code release} to be entered. */
        boolean awaitReleaseEntered() throws InterruptedException {
            return entered.await(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        }

        /** Lets the parked {@code release} return. */
        void resumeRelease() {
            resume.countDown();
        }

        @Override
        public Optional<SessionLease> tryAcquire(SessionId id, String holderId, Duration lease) {
            return delegate.tryAcquire(id, holderId, lease);
        }

        @Override
        public Optional<LeaseHolder> findHolder(SessionId id) {
            return delegate.findHolder(id);
        }

        @Override
        public boolean extend(SessionLease lease, Duration duration) {
            return delegate.extend(lease, duration);
        }

        @Override
        public void release(SessionLease lease) {
            delegate.release(lease);
            if (!armed.compareAndSet(true, false)) {
                return;
            }
            entered.countDown();
            try {
                resume.await(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
