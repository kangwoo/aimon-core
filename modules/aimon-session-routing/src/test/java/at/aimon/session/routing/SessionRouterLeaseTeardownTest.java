package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.ScriptableLock;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Regression for the cascade a lost lease used to cause: {@code onLeaseLost} ran the whole teardown inline on the
 * renewal thread, and evicting a session closes it — which fires the deployment's {@code OnSessionEnd} hooks, arbitrary
 * code that may block for as long as it likes. One session's slow hook therefore parked the renewal thread, and every
 * other session renewing behind it lost its lease too: a single lost lease turning into a node-wide outage.
 *
 * <p>
 * The fix splits {@code onLeaseLost} in two — the non-blocking half (mark the lease lost, interrupt the local turn)
 * stays inline where its ordering is guaranteed, and the unbounded half (evict, then hand the lease back) is dispatched
 * to the turn executor. What that split produces is observable in exactly one place: the thread the close runs on. So
 * that is what this test asserts, rather than how long the teardown took — a latency assertion would be flaky and could
 * not tell a real hand-off apart from a teardown that merely happened to be fast.
 */
@DisplayName("SessionRouter hands a lost lease's blocking teardown off the renewal pool")
class SessionRouterLeaseTeardownTest {

    /**
     * Thread-name prefixes minted by {@code DefaultSessionRouter#namedFactory}. Asserting on thread names is deliberate
     * here: the whole content of the fix is which executor performs the teardown, so the thread's identity <em>is</em>
     * the contract, and there is nothing else to look at.
     */
    private static final String RENEWAL_THREAD_PREFIX = "web-session-lease";
    private static final String TURN_THREAD_PREFIX = "web-session-turn";

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a lost lease closes its session on a turn-executor thread and never on a lease-renewal thread")
    void lostLeaseClosesItsSessionOffTheRenewalPool() throws Exception {
        final ScriptableLock lock = new ScriptableLock(new InMemorySessionLeaseStore());
        harness = TestManagerHarness.builder().leaseStore(lock).lockLease(Duration.ofMillis(500))
                .lockExtendInterval(Duration.ofMillis(50)).build();

        final SessionId id = SessionId.of("c-lease-teardown-1");
        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(outcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("done"));
        assertThat(outcome.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("done");

        // Not decoration: the submitter's future is completed several statements before the turn loop's finally
        // unpins the cache entry, and an entry that is still pinned makes the eviction below *defer* its close to
        // that unpin — onto the turn thread, whose name is the one this test wants to see. The assertion would then
        // hold for the wrong reason, including on unfixed code. Two proven renewal ticks put ~100ms of observed
        // progress between the finished turn and the lost lease, which is that window many times over. It also
        // establishes the precondition the rest of the test needs: an idle session whose lease is still being renewed.
        awaitExtendsAtLeast(lock, lock.extendCalls() + 2);

        // Park the close so the teardown is caught while it is still running. This stands in for the deployment's
        // OnSessionEnd code — the unbounded work whose whole point is that a renewal thread must not be sitting in it.
        session.blockClose();
        try {
            // A refused renewal is what "the backend gave this session to another node" looks like from here.
            lock.startFailingExtends();
            assertThat(session.awaitCloseEntered()).as("a lost lease must tear its session down").isTrue();

            // The regression guard. Pre-fix this read web-session-lease-1 — the sole renewal thread, now parked in a
            // hook for as long as the hook cares to take, while every other session on the node renews behind it.
            assertThat(session.closingThreadName()).as("teardown must not park a lease-renewal thread")
                    .doesNotStartWith(RENEWAL_THREAD_PREFIX);
            // And where it went instead: the turn executor, which is an unbounded cached pool, so a teardown neither
            // queues nor costs anything but its own thread.
            assertThat(session.closingThreadName()).as("teardown belongs to the unbounded turn executor")
                    .startsWith(TURN_THREAD_PREFIX);

            // The other half of the split: marking the lease lost and stopping the local turn stayed inline, so the
            // interrupt is already recorded by the time the handed-off teardown reaches the close.
            assertThat(session.recordedInterrupts()).as("the non-blocking half still runs on the renewal thread")
                    .contains(InterruptReason.LEASE_LOST);
        } finally {
            // Whatever the assertions decided, the parked close has to be let go — the manager's shutdown in
            // tearDown() would otherwise wait on it and the suite would hang on a failure rather than report one.
            session.releaseClose();
        }
    }

    /**
     * Waits, within a bounded budget, until the lease store has seen at least {@code target} extend calls.
     *
     * <p>
     * Awaitility is not on this module's test classpath, so this follows the neighboring lease tests' bounded-deadline
     * poll. The real synchronization in this test is the close latch; this is only a progress barrier.
     */
    private static void awaitExtendsAtLeast(ScriptableLock lock, int target) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (lock.extendCalls() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(lock.extendCalls()).as("an idle session's lease must still be renewed")
                .isGreaterThanOrEqualTo(target);
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
