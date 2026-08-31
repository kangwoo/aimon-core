package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Turn addressing across the manager boundary: the id a submitter is handed must be the id the turn actually runs
 * under,
 * and it must be usable to stop that turn and only that turn.
 *
 * <p>
 * The queued case is the one worth the wiring. A submitter whose node does not hold the lock gets back a
 * {@code FORWARDED}
 * disposition and never sees the turn run; if the holder minted its own id when it drained the inbox, the id the
 * submitter holds would name nothing and its cancel would be unroutable.
 */
@DisplayName("SessionRouter turn addressing")
class SessionRouterTurnIdTest {

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a locally executed submit reports the id the session runs the turn under")
    void localSubmitReportsTheRunningTurnId() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-turnid-local");

        final SubmitDisposition disposition = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(disposition.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        assertThat(session.submittedTurnIds()).containsExactly(disposition.getTurnId());
        assertThat(session.currentTurnId()).contains(disposition.getTurnId());

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        disposition.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a queued submit reports the id the holder later runs it under, and the two turns differ")
    void queuedSubmitReportsTheIdTheHolderWillUse() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-turnid-queued");

        final SubmitDisposition first = harness.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition second = harness.manager().submit(RequestFixtures.submit(id, "alpha", "second"));
        assertThat(second.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(second.getTurnId()).as("a deferred turn is still addressable").isNotEqualTo(first.getTurnId());

        // Releasing turn 1 lets the holder drain the inbox and run turn 2 — under the queued id, not a fresh one.
        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).isTrue();

        assertThat(session.submittedTurnIds()).containsExactly(first.getTurnId(), second.getTurnId());

        session.completeCurrentTurn(TestLiveSession.ok("second-done"));
        first.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("interrupt(turnId) stops the named turn and leaves a turn it does not name alone")
    void addressedInterruptStopsOnlyItsOwnTurn() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-turnid-interrupt");

        final SubmitDisposition disposition = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // A cancel for a turn that already settled must not take down its successor.
        harness.manager().interrupt(id, TurnId.of("some-other-turn"), InterruptReason.USER_SIGINT);
        assertThat(session.recordedInterrupts()).as("an interrupt addressed elsewhere must not land here").isEmpty();

        harness.manager().interrupt(id, disposition.getTurnId(), InterruptReason.USER_SIGINT);
        assertThat(session.awaitInterrupt()).isTrue();
        assertThat(session.recordedInterrupts()).containsExactly(InterruptReason.USER_SIGINT);

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        disposition.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("the unaddressed interrupt still stops whatever is running")
    void unaddressedInterruptStillStopsTheActiveTurn() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-turnid-unaddressed");

        final SubmitDisposition disposition = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // The operator/eviction path deliberately keeps this broader contract — see SessionRouter#interrupt.
        harness.manager().interrupt(id, InterruptReason.SYSTEM_SHUTDOWN);

        assertThat(session.awaitInterrupt()).isTrue();
        assertThat(session.recordedInterrupts()).containsExactly(InterruptReason.SYSTEM_SHUTDOWN);

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        disposition.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
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
