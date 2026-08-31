package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * WS-06: graceful shutdown semantics — {@link SessionRouter#closeGracefully(Duration)} drains in-flight
 * turns when given enough time, refuses new submits during draining, and falls back to a hard close on timeout.
 */
@DisplayName("SessionRouter graceful shutdown")
class SessionRouterGracefulShutdownTest {

    @Test
    @DisplayName("graceful shutdown drains finishing turn within timeout")
    void drainsFinishingTurnWithinTimeout() throws Exception {
        final TestManagerHarness harness = TestManagerHarness.builder().build();
        try {
            final SessionId id = SessionId.of("c-gs-1");
            final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
            final TestLiveSession session = waitForSession(harness, id);
            assertThat(session.awaitTurnStarted()).isTrue();

            // Complete the turn from a separate thread shortly after closeGracefully is called.
            final Thread completer = new Thread(() -> {
                try {
                    Thread.sleep(50L);
                    session.completeCurrentTurn(TestLiveSession.ok("done"));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            completer.setDaemon(true);
            completer.start();

            final boolean drained = harness.manager().closeGracefully(Duration.ofSeconds(2));
            assertThat(drained).as("turn must drain inside the timeout").isTrue();
            outcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
        } finally {
            // closeGracefully is idempotent — calling close() again must be safe.
            harness.close();
        }
    }

    @Test
    @DisplayName("graceful shutdown refuses new submits while draining")
    void refusesNewSubmitsAfterShutdownStarted() throws Exception {
        final TestManagerHarness harness = TestManagerHarness.builder().build();
        try {
            final SessionId id = SessionId.of("c-gs-2");
            final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
            final TestLiveSession session = waitForSession(harness, id);
            assertThat(session.awaitTurnStarted()).isTrue();

            // Start shutdown in a background thread so the assertion runs while the gate is closed.
            final Thread shutdownThread = new Thread(() -> harness.manager().closeGracefully(Duration.ofMillis(500)));
            shutdownThread.setDaemon(true);
            shutdownThread.start();
            // Let the gate flip — closeGracefully sets acceptingSubmits=false synchronously before draining.
            Thread.sleep(50L);

            assertThatThrownBy(() -> harness.manager().submit(RequestFixtures.submit(id, "alpha", "no-go")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("shutting down");

            // Allow the in-flight turn to finish so the shutdown thread can complete cleanly.
            session.completeCurrentTurn(TestLiveSession.ok("done"));
            shutdownThread.join(2_000L);
            outcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("graceful shutdown trips active turn with SYSTEM_SHUTDOWN when the timeout elapses")
    void interruptsLongTurnOnTimeout() throws Exception {
        final TestManagerHarness harness = TestManagerHarness.builder().build();
        try {
            final SessionId id = SessionId.of("c-gs-3");
            harness.manager().submit(RequestFixtures.submit(id, "alpha", "long"));
            final TestLiveSession session = waitForSession(harness, id);
            assertThat(session.awaitTurnStarted()).isTrue();

            // Allow the turn to finish promptly *after* the timeout fires so the manager can unwind.
            final Thread completer = new Thread(() -> {
                try {
                    Thread.sleep(400L);
                    session.completeCurrentTurn(TestLiveSession.ok("late-done"));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            completer.setDaemon(true);
            completer.start();

            final boolean drained = harness.manager().closeGracefully(Duration.ofMillis(150));
            assertThat(drained).as("draining must report timeout when the turn does not finish in time").isFalse();
            assertThat(session.recordedInterrupts()).contains(InterruptReason.SYSTEM_SHUTDOWN);
        } finally {
            harness.close();
        }
    }

    private TestLiveSession waitForSession(TestManagerHarness harness, SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 1_000L;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        final TestLiveSession s = harness.session(id);
        assertThat(s).as("session for %s should be opened", id).isNotNull();
        return s;
    }
}
