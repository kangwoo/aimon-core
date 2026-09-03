package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.ConflictingAgentException;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * WS-02 design §12: submit / interrupt / release scenarios on a single node.
 */
@DisplayName("SessionRouter submit/interrupt/release")
class SessionRouterSubmitTest {

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("WS-02-B1: 두 번째 동시 submit은 FORWARDED outcome")
    void concurrentSubmitYieldsForwardedForSecond() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-1");

        final SubmitDisposition first = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(first.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).as("first turn must have started").isTrue();

        final SubmitDisposition second = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hi again"));
        assertThat(second.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(second.getInboxId()).isPresent();
        assertThat(harness.inbox().isEmpty(id)).as("inbox must hold the deferred message").isFalse();
        assertThat(second.getFuture().toCompletableFuture())
                .as("a forwarded turn's future stays open until a holder announces its result").isNotDone();

        // Drain both turns so the manager's executor unwinds cleanly.
        session.completeCurrentTurn(TestLiveSession.ok("done-1"));
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (session.submittedInputs().size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        session.completeCurrentTurn(TestLiveSession.ok("done-2"));
        first.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        final AgentExecutionResult forwarded = second.getFuture().toCompletableFuture()
                .get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        assertThat(forwarded.getFinalAnswer()).as("the holder's drain must close the forwarded future")
                .isEqualTo("done-2");
    }

    @Test
    @DisplayName("WS-02-B2: agentRef 충돌 시 ConflictingAgentException")
    void mismatchedAgentRefThrowsConflict() {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-2");
        harness.repository().provision(id, "alpha");

        assertThatThrownBy(() -> harness.manager().submit(RequestFixtures.submit(id, "beta", "hi")))
                .isInstanceOf(ConflictingAgentException.class);
    }

    @Test
    @DisplayName("WS-02-B5: interrupt() trips active session")
    void interruptTripsActiveTurn() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-5");
        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        harness.manager().interrupt(id, InterruptReason.USER_SIGINT);

        assertThat(session.awaitInterrupt()).isTrue();
        assertThat(session.recordedInterrupts()).contains(InterruptReason.USER_SIGINT);

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("WS-02-B6: releaseSession evicts cache, closes the handle, completes events()")
    void releaseConversationCleansUp() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-6");
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();

        final Flow.Publisher<AgentExecutionEvent> events = harness.manager().events(id);
        events.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentExecutionEvent item) {
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });

        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // Release while turn is in flight: manager interrupts session, evicts cache, completes publisher.
        harness.manager().releaseSession(id);

        assertThat(session.recordedInterrupts()).contains(InterruptReason.SESSION_RELEASED);

        // Let the turn unwind so the manager's executor finishes cleanly.
        try {
            session.completeCurrentTurn(TestLiveSession.ok("done"));
        } catch (IllegalStateException ignored) {
            // race with the manager's release path — turn may have already been swept.
        }

        final AgentExecutionResult result = outcome.getFuture().toCompletableFuture()
                .get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        assertThat(result).isNotNull();

        assertThat(completed.await(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS))
                .as("events() must signal onComplete after release").isTrue();
        assertThat(errorRef.get()).isNull();
        assertThat(session.awaitClosed()).as("session must be closed by the cache eviction listener").isTrue();
    }

    @Test
    @DisplayName("WS-02-B8: inbox-deferred messages are collected by lock holder after first turn")
    void inboxDeliveryCollectedByHolder() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-8");

        final SubmitDisposition first = harness.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition second = harness.manager().submit(RequestFixtures.submit(id, "alpha", "second"));
        assertThat(second.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // Complete first turn — the manager's loop should pick up the inbox message and submit it as the next turn.
        session.completeCurrentTurn(TestLiveSession.ok("first-done"));

        // Wait for the second submit to arrive at the test session.
        final long deadline = System.currentTimeMillis() + 2_000L;
        while (session.submittedInputs().size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        final List<String> inputs = session.submittedInputs();
        assertThat(inputs).containsExactly("first", "second");

        session.completeCurrentTurn(TestLiveSession.ok("second-done"));

        first.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
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
