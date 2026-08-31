package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.InterruptedAt;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Design §13 issue #10 close-out: {@code deleteSession} runs under the session lock so it never races an
 * in-flight turn. Direct {@code repository.delete(id)} bypassed the manager and was the source of the race.
 */
@DisplayName("SessionRouter deleteSession")
class SessionRouterDeleteSessionTest {

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("idle conversation: delete removes history, emits terminal InterruptedAt + onComplete, broadcasts EVICT")
    void deleteIdleSession() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-del-1");
        harness.repository().provision(id, "alpha");
        assertThat(harness.repository().exists(id)).isTrue();

        final CopyOnWriteArrayList<SessionSignal> evictSignals = new CopyOnWriteArrayList<>();
        harness.signalBus().subscribe(id, signal -> {
            if (signal.getKind() == SessionSignal.SignalKind.EVICT) {
                evictSignals.add(signal);
            }
        });

        final CopyOnWriteArrayList<AgentExecutionEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch terminated = new CountDownLatch(1);
        final Flow.Publisher<AgentExecutionEvent> events = harness.manager().events(id);
        events.subscribe(new Flow.Subscriber<>() {
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
                /* not expected */ }

            @Override
            public void onComplete() {
                terminated.countDown();
            }
        });

        harness.manager().deleteSession(id);

        assertThat(harness.repository().exists(id)).as("repository must be purged").isFalse();
        assertThat(terminated.await(2, TimeUnit.SECONDS)).as("subscriber must onComplete").isTrue();
        assertThat(received).as("subscriber must observe terminal InterruptedAt before onComplete").hasSize(1);
        assertThat(((InterruptedAt) received.get(0)).getReason()).isEqualTo(InterruptReason.SESSION_RELEASED);
        assertThat(evictSignals).as("manager must broadcast EVICT for cluster fan-out").hasSize(1);
    }

    @Test
    @DisplayName("contention: in-flight turn is interrupted with SESSION_RELEASED, delete succeeds after lock yields")
    void deleteWaitsOutHolderThenSucceeds() throws Exception {
        harness = TestManagerHarness.builder().releaseInterruptTimeout(Duration.ofSeconds(5)).build();
        final SessionId id = SessionId.of("c-del-2");

        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(outcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).as("turn must hold the lock").isTrue();

        final ExecutorService deleter = Executors.newSingleThreadExecutor();
        try {
            final Future<?> deleteFuture = deleter.submit(() -> harness.manager().deleteSession(id));

            // Manager must trip the active session with SESSION_RELEASED so the holder's turn unwinds.
            assertThat(session.awaitInterrupt()).as("delete must broadcast SESSION_RELEASED to holder").isTrue();
            assertThat(session.recordedInterrupts()).contains(InterruptReason.SESSION_RELEASED);

            // Yield the lease by completing the turn — the delete loop should pick it up on the next retry.
            session.completeCurrentTurn(TestLiveSession.ok("yielded"));

            deleteFuture.get(3, TimeUnit.SECONDS);
        } finally {
            deleter.shutdownNow();
        }

        assertThat(harness.repository().exists(id)).isFalse();
    }

    @Test
    @DisplayName("holder never yields: delete throws IllegalStateException after releaseInterruptTimeout")
    void deleteFailsWhenLockNeverYields() throws Exception {
        harness = TestManagerHarness.builder().releaseInterruptTimeout(Duration.ofMillis(300)).build();
        final SessionId id = SessionId.of("c-del-3");

        harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // Test session ignores the interrupt (records it but never completes the turn) — the lease stays held.
        assertThatThrownBy(() -> harness.manager().deleteSession(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not acquire session").hasMessageContaining("deleteSession");

        // Cleanup — let the held turn drain so close() doesn't deadlock.
        session.completeCurrentTurn(TestLiveSession.ok("late"));
    }

    @Test
    @DisplayName("peer never yields: the failed delete gives the turn gate back, so the retry can succeed")
    void deleteReleasesTheTurnGateWhenTheLeaseWaitTimesOut() {
        harness = TestManagerHarness.builder().releaseInterruptTimeout(Duration.ofMillis(300)).build();
        final SessionId id = SessionId.of("c-del-4");
        harness.repository().provision(id, "alpha");

        // A peer holds the lease and there is no turn running here: the delete takes the gate on its first try and then
        // times out waiting for the lease. Deliberately not the shape of deleteFailsWhenLockNeverYields, which contends
        // on the gate instead and so never gets far enough to leak it.
        final SessionLease stolen = harness.leaseStore().tryAcquire(id, "outsider-node", Duration.ofSeconds(30))
                .orElseThrow();

        assertThatThrownBy(() -> harness.manager().deleteSession(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not acquire session");

        // The peer finishes and hands the session back. Nothing but endTurn ever clears the gate, so a delete that
        // walked out of the guarded region without it would make this session permanently undeletable on this node —
        // and unsubmittable, and undrainable — for the rest of the process's life. Asserting on the retry rather than
        // on the message is the only discrimination available: the gate wait and the lease wait share their wording on
        // purpose, so the second failure would look exactly like the first.
        harness.leaseStore().release(stolen);

        harness.manager().deleteSession(id);
        assertThat(harness.repository().exists(id)).as("the retry must actually delete the record").isFalse();
    }

    private TestLiveSession waitForSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 1_000L;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("session for %s should be opened by the cache", id).isNotNull();
        return session;
    }
}
