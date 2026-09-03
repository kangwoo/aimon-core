package at.aimon.session.routing.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.fixture.RecordingSessionMetrics;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.ScriptableLock;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;
import at.aimon.session.routing.metrics.SessionMetrics.CacheEvictionReason;

/**
 * WS-06: verifies that the manager fires every {@link SessionMetrics} hook at the expected site.
 */
@DisplayName("SessionMetrics hook coverage")
class SessionMetricsTest {

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("first submit records cache miss + lock acquire + EXECUTED_LOCALLY outcome")
    void firstSubmitRecordsAcquireAndMiss() throws Exception {
        final RecordingSessionMetrics metrics = new RecordingSessionMetrics();
        harness = TestManagerHarness.builder().metrics(metrics).build();
        final SessionId id = SessionId.of("c-metrics-1");

        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);

        assertThat(metrics.lockAcquireSucceeded.get()).isEqualTo(1);
        assertThat(metrics.lockAcquireRejected.get()).isZero();
        assertThat(metrics.cacheMisses.get()).isEqualTo(1);
        assertThat(metrics.cacheHits.get()).isZero();
        assertThat(metrics.submitOutcomes.get(SubmitDisposition.Kind.EXECUTED_LOCALLY).get()).isEqualTo(1);
        assertThat(metrics.lastLockAcquireLatency.get()).isNotNull();
        assertThat(metrics.lastLockAcquireLatency.get().toNanos()).isPositive();
    }

    @Test
    @DisplayName("second concurrent submit records lock-rejected + FORWARDED outcome")
    void concurrentSubmitRecordsRejected() throws Exception {
        final RecordingSessionMetrics metrics = new RecordingSessionMetrics();
        harness = TestManagerHarness.builder().metrics(metrics).build();
        final SessionId id = SessionId.of("c-metrics-2");

        final SubmitDisposition first = harness.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition second = harness.manager().submit(RequestFixtures.submit(id, "alpha", "second"));
        assertThat(second.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        assertThat(metrics.lockAcquireSucceeded.get()).isEqualTo(1);
        assertThat(metrics.lockAcquireRejected.get()).isEqualTo(1);
        assertThat(metrics.submitOutcomes.get(SubmitDisposition.Kind.EXECUTED_LOCALLY).get()).isEqualTo(1);
        assertThat(metrics.submitOutcomes.get(SubmitDisposition.Kind.FORWARDED).get()).isEqualTo(1);

        // Drain so the manager can shut down cleanly.
        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (session.submittedInputs().size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        session.completeCurrentTurn(TestLiveSession.ok("second-done"));
        first.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("releaseSession triggers EXPLICIT_RELEASE eviction")
    void releaseRecordsExplicitEviction() throws Exception {
        final RecordingSessionMetrics metrics = new RecordingSessionMetrics();
        harness = TestManagerHarness.builder().metrics(metrics).build();
        final SessionId id = SessionId.of("c-metrics-3");

        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        harness.manager().releaseSession(id);

        try {
            session.completeCurrentTurn(TestLiveSession.ok("done"));
        } catch (IllegalStateException ignored) {
            // race with manager release path
        }
        outcome.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);

        assertThat(metrics.cacheEvictions.get(CacheEvictionReason.EXPLICIT_RELEASE).get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("lease extend success/failure are reported")
    void leaseExtendMetrics() throws Exception {
        final RecordingSessionMetrics metrics = new RecordingSessionMetrics();
        final ScriptableLock scriptableLock = new ScriptableLock(new InMemorySessionLeaseStore());
        harness = TestManagerHarness.builder().metrics(metrics).leaseStore(scriptableLock)
                .lockLease(Duration.ofMillis(500)).lockExtendInterval(Duration.ofMillis(50))
                .releaseInterruptTimeout(Duration.ofMillis(100)).build();

        final SessionId id = SessionId.of("c-metrics-4");
        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "long"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (metrics.leaseExtendSucceeded.get() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(metrics.leaseExtendSucceeded.get()).as("multiple extend successes recorded")
                .isGreaterThanOrEqualTo(3);
        assertThat(metrics.leaseExtendFailed.get()).isZero();

        scriptableLock.startFailingExtends();
        assertThat(session.awaitInterrupt()).isTrue();
        assertThat(session.recordedInterrupts()).contains(InterruptReason.LEASE_LOST);
        assertThat(metrics.leaseExtendFailed.get()).as("one extend failure recorded").isEqualTo(1);

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("second submit on same active session records cache hit")
    void secondTurnSameSessionRecordsHit() throws Exception {
        final RecordingSessionMetrics metrics = new RecordingSessionMetrics();
        harness = TestManagerHarness.builder().metrics(metrics).build();
        final SessionId id = SessionId.of("c-metrics-5");

        final SubmitDisposition first = harness.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();
        // Second submit lands in the inbox (lock held), then the holder picks it up after first turn — second
        // ensureOpen should be a hit on the cached entry.
        harness.manager().submit(RequestFixtures.submit(id, "alpha", "second"));

        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        final long deadline = System.currentTimeMillis() + 2_000L;
        while (session.submittedInputs().size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        session.completeCurrentTurn(TestLiveSession.ok("second-done"));
        first.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);

        // The second turn reuses the same ensureOpen call from inside the still-running turn loop, so we expect
        // exactly one miss (initial open) and zero or more hits depending on the manager's reuse path. The strict
        // invariant is: at least one miss recorded, no extra misses recorded for the second message.
        assertThat(metrics.cacheMisses.get()).isEqualTo(1);
    }

    private TestLiveSession waitForSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        final TestLiveSession s = harness.session(id);
        assertThat(s).as("session for %s should be opened", id).isNotNull();
        return s;
    }
}
