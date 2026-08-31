package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.ScriptableLock;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Regression for the second half of the renewal-pool fix: the lease scheduler is sized for more than one session.
 *
 * <p>
 * {@link at.aimon.session.routing.internal.LeaseRenewer} schedules one fixed-rate task per held lease, all of them on
 * the
 * manager's lease scheduler. Renewal is a call into the lease backend, so a session whose backend stops answering —
 * a stalled connection, a lock row another transaction is sitting on — holds the thread that made the call for as long
 * as it hangs. On a single-threaded scheduler that thread is the only one there is, so every other session's renewal
 * tick simply never runs, and each of them loses a lease it was still entitled to. The node then sheds sessions that
 * had nothing wrong with them, because of one that did.
 *
 * <p>
 * What the sizing buys is bounded and this test claims no more than that: with a pool of <em>n</em> threads, <em>n</em>
 * simultaneously stuck renewals still starve the rest. The floor of two is what separates "one stuck session takes the
 * node down" from "one stuck session is one stuck session", and two sessions are what it takes to show it. The
 * unbounded half of the problem — teardown hooks running on this pool — is not a sizing question at all and is fixed
 * by the hand-off that {@code SessionRouterLeaseTeardownTest} pins.
 */
@DisplayName("SessionRouter renews other sessions while one session's renewal is stuck in the backend")
class SessionRouterLeaseRenewalPoolTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    /** Short enough that a few ticks land inside the test's budget; the lease above stays far from expiring. */
    private static final Duration RENEW_EVERY = Duration.ofMillis(50);

    private TestManagerHarness harness;
    private ScriptableLock lock;

    @AfterEach
    void tearDown() {
        // Before the manager shuts down: a scheduler thread parked in extend() would otherwise make close() wait out
        // the fixture's own 30s ceiling, turning a failure into a hang.
        if (lock != null) {
            lock.releaseBlockedExtends();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a renewal hung in the lease backend does not stop another session's lease from being renewed")
    void aStuckRenewalDoesNotStarveTheOtherSessionsRenewal() throws Exception {
        lock = new ScriptableLock(new InMemorySessionLeaseStore());
        harness = TestManagerHarness.builder().leaseStore(lock).lockLease(LEASE).lockExtendInterval(RENEW_EVERY)
                .build();

        final SessionId stuck = SessionId.of("c-renewal-stuck");
        final SessionId healthy = SessionId.of("c-renewal-healthy");
        idleSessionHoldingALease(stuck);
        idleSessionHoldingALease(healthy);

        // Both leases must already be on the scheduler before one is jammed, or the test could be measuring a task
        // that was never scheduled rather than one that was scheduled and starved.
        awaitRenewed(stuck, 1);
        awaitRenewed(healthy, 1);

        lock.blockExtendsFor(stuck);
        assertThat(lock.awaitExtendBlocked()).as("the fixture must actually be holding a renewal thread").isTrue();
        // Read after the block is entered, never before: arming only affects renewals that have not yet reached the
        // check, so a tick already inside the backend races past it and the count at arming time is not a fixed number.
        final int stuckWhenParked = lock.extendCallsFor(stuck);

        // Two further ticks, not one: a single one could have been in flight when the block was armed. Two means the
        // scheduler came back for this session after the other one stopped returning.
        final int before = lock.extendCallsFor(healthy);
        awaitRenewed(healthy, before + 2);

        assertThat(lock.extendCallsFor(healthy))
                .as("a pool of one lets the stuck session's renewal hold the only thread, and this session — which "
                        + "has nothing wrong with it — silently loses a lease it is still entitled to")
                .isGreaterThanOrEqualTo(before + 2);
        // scheduleAtFixedRate never overlaps a task with itself, so a parked renewal means this session's ticks have
        // stopped entirely — the starvation the other session must not share.
        assertThat(lock.extendCallsFor(stuck)).as("and the jammed session is still jammed, not quietly proceeding")
                .isEqualTo(stuckWhenParked);
    }

    // -----------------------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Runs one turn to completion for {@code id}, leaving the session idle but still holding — and renewing — its
     * lease. Idle on purpose: a session parked in a turn would confuse a starved renewal with a busy one.
     */
    private void idleSessionHoldingALease(SessionId id) throws Exception {
        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(outcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("done"));
        assertThat(outcome.getFuture().toCompletableFuture()
                .get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS).getFinalAnswer()).isEqualTo("done");
    }

    /**
     * Waits, within a bounded budget, until {@code id} has been renewed at least {@code target} times. Awaitility is
     * not on this module's test classpath, so this is the neighbouring lease tests' bounded-deadline poll.
     */
    private void awaitRenewed(SessionId id, int target) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (lock.extendCallsFor(id) < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private TestLiveSession waitForSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("session for %s should be opened by the cache", id).isNotNull();
        return session;
    }
}
