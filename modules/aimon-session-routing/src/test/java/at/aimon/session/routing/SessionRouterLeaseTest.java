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
 * WS-02-B7: lease extender renews while a turn is running and trips LEASE_LOST when extend starts to fail.
 */
@DisplayName("SessionRouter lease extender")
class SessionRouterLeaseTest {

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("WS-02-B7: lease is renewed during a long turn; extend failure trips LEASE_LOST")
    void leaseExtenderRenewsAndTrips() throws Exception {
        final ScriptableLock scriptableLock = new ScriptableLock(new InMemorySessionLeaseStore());
        harness = TestManagerHarness.builder().leaseStore(scriptableLock).lockLease(Duration.ofMillis(500))
                .lockExtendInterval(Duration.ofMillis(50)).releaseInterruptTimeout(Duration.ofMillis(100)).build();

        final SessionId id = SessionId.of("c-7");
        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "long"));
        assertThat(outcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // Wait for several extend ticks to demonstrate ongoing renewal.
        final long deadline = System.currentTimeMillis() + 1_000L;
        while (scriptableLock.extendCalls() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(scriptableLock.extendCalls()).as("extend should have fired multiple times")
                .isGreaterThanOrEqualTo(3);
        // Renewal must not share the pool that carries idle sweeps, the holder-loss sweep, STATUS heartbeats and every
        // forwarded-turn poll: a tick queued behind that work is indistinguishable from a lease somebody stole, and the
        // budget is only two missed ticks wide. The thread name is where that wiring is observable.
        assertThat(scriptableLock.lastExtendThreadName()).as("renewal runs on the dedicated lease scheduler")
                .startsWith("web-session-lease-");

        // Flip the lock to start failing — LeaseRenewer should fire the onExtendFailed callback once.
        scriptableLock.startFailingExtends();
        assertThat(session.awaitInterrupt()).isTrue();
        assertThat(session.recordedInterrupts()).contains(InterruptReason.LEASE_LOST);

        // Let the manager's turn loop unwind.
        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private TestLiveSession waitForSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 1_000L;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return harness.session(id);
    }
}
