package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.idempotency.PutResult;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Regression for the bug where a throwing {@code IdempotencyStore.compareAndReset} in {@code runTurnLoop}'s catch block
 * could leak the user-visible {@link java.util.concurrent.CompletableFuture}: the cleanup ran before
 * {@code future.completeExceptionally}, so the original exception never surfaced and the caller hung. Real backends
 * (Redis, etc.) can throw transient connection errors here, so the manager must always resolve the future first and
 * treat the cleanup as best-effort.
 */
@DisplayName("SessionRouter idempotency cleanup failure does not leak the submit future")
class SessionRouterIdempotencyCleanupFailureTest {

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("turn-loop catch with throwing compareAndReset still completes the submit future exceptionally")
    void turnLoopCatchStillCompletesFutureWhenCompareAndResetThrows() throws Exception {
        final IdempotencyStore throwingStore = new ThrowingCompareAndResetStore(new InMemoryIdempotencyStore());
        harness = TestManagerHarness.builder().idempotencyStore(throwingStore).build();
        final SessionId id = SessionId.of("c-idem-leak-1");

        final SubmitRequest request = SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("hello")
                .initiator(Principal.user("tester")).idempotencyKey("k-leak-1").build();
        final SubmitDisposition outcome = harness.manager().submit(request);
        assertThat(outcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final CompletionStage<AgentExecutionResult> future = outcome.getFuture();

        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // Provoke the runTurnLoop catch path. compareAndReset will throw — the fix guarantees the user-visible
        // future still completes exceptionally with the original cause.
        session.failCurrentTurn(new RuntimeException("boom"));

        final Throwable cause = awaitFailure(future, Duration.ofSeconds(2));
        Throwable inner = cause;
        while (inner instanceof CompletionException && inner.getCause() != null) {
            inner = inner.getCause();
        }
        assertThat(inner).hasMessageContaining("boom");
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

    private static Throwable awaitFailure(CompletionStage<AgentExecutionResult> stage, Duration timeout)
            throws InterruptedException, TimeoutException {
        try {
            stage.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            return ee.getCause() != null ? ee.getCause() : ee;
        }
        throw new AssertionError("Expected the submit future to complete exceptionally, but it succeeded");
    }

    /** Delegates every operation except {@link #compareAndReset(String, String)}, which always throws. */
    private static final class ThrowingCompareAndResetStore implements IdempotencyStore {
        private final IdempotencyStore delegate;

        ThrowingCompareAndResetStore(IdempotencyStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public PutResult putIfAbsent(String key, IdempotencyEntry entry, Duration ttl) {
            return delegate.putIfAbsent(key, entry, ttl);
        }

        @Override
        public void markDone(String key, AgentExecutionResult result) {
            delegate.markDone(key, result);
        }

        @Override
        public Optional<IdempotencyEntry> find(String key) {
            return delegate.find(key);
        }

        @Override
        public boolean touch(String key, String holderId) {
            return delegate.touch(key, holderId);
        }

        @Override
        public boolean releaseHolder(String key, String expectedHolderId, Duration ttl) {
            return delegate.releaseHolder(key, expectedHolderId, ttl);
        }

        @Override
        public boolean acquireHolder(String key, String holderId, Duration ttl) {
            return delegate.acquireHolder(key, holderId, ttl);
        }

        @Override
        public boolean discardReservation(String key) {
            return delegate.discardReservation(key);
        }

        @Override
        public boolean compareAndReset(String key, String expectedHolderId) {
            throw new RuntimeException("simulated idempotency backend failure");
        }

        @Override
        public List<IdempotencyEntry> findStaleInFlight(Instant cutoff) {
            return delegate.findStaleInFlight(cutoff);
        }
    }
}
