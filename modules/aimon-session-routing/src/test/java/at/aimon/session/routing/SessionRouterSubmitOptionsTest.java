package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Verifies the {@link SubmitOptions} plumbing through the web manager:
 *
 * <ul>
 * <li>The first turn forwards the request's {@link SubmitRequest#getSubmitOptions() submitOptions} into
 * {@code LiveSession.submitAsync(input, submitOptions, listener)}.
 * <li>An inbox-deferred turn (busy session) preserves the submitter's {@code submitOptions} when the manager dispatches
 * it as the next turn — proving the {@code SubmitRequest → InboundMessage → submitAsync} round-trip is intact.
 * </ul>
 */
@DisplayName("SessionRouter SubmitOptions plumbing")
class SessionRouterSubmitOptionsTest {

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("first turn forwards SubmitOptions from SubmitRequest into the underlying LiveSession.submitAsync")
    void firstTurnForwardsSubmitOptions() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-opts-1");

        final SubmitOptions options = SubmitOptions.builder().principal(Principal.user("u-1", "alice"))
                .systemPromptVariable("region", "eu").executionAttribute("ab.x", true).userContextInjection(false)
                .build();

        final SubmitRequest request = SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("hello")
                .initiator(Principal.user("tester")).submitOptions(options).build();

        final SubmitDisposition outcome = harness.manager().submit(request);
        assertThat(outcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();
        assertThat(session.submittedOptions()).hasSize(1);
        assertThat(session.submittedOptions().get(0)).isEqualTo(options);

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("inbox-deferred turn round-trips SubmitOptions from SubmitRequest → InboundMessage → submitAsync")
    void inboxDeferredTurnPreservesSubmitOptions() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-opts-2");

        // First request occupies the session — we want to assert against the SECOND (deferred) turn's options.
        final SubmitOptions firstOptions = SubmitOptions.builder().executionAttribute("turn", "first").build();
        final SubmitRequest firstRequest = SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("first")
                .initiator(Principal.user("tester")).submitOptions(firstOptions).build();
        final SubmitDisposition first = harness.manager().submit(firstRequest);

        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // Second request: lands in the inbox because the session is busy.
        final SubmitOptions secondOptions = SubmitOptions.builder().principal(Principal.user("u-2", "bob"))
                .systemPromptVariables(Map.of("trace.id", "abc-123")).userContextInjection(true).build();
        final SubmitRequest secondRequest = SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("second")
                .initiator(Principal.user("tester")).submitOptions(secondOptions).build();
        final SubmitDisposition second = harness.manager().submit(secondRequest);
        assertThat(second.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // Release the first turn so the manager's loop drains the inbox and submits the deferred message.
        session.completeCurrentTurn(TestLiveSession.ok("first-done"));

        final long deadline = System.currentTimeMillis() + 2_000L;
        while (session.submittedInputs().size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(session.submittedInputs()).containsExactly("first", "second");
        assertThat(session.submittedOptions()).hasSize(2);
        assertThat(session.submittedOptions().get(0)).isEqualTo(firstOptions);
        assertThat(session.submittedOptions().get(1)).isEqualTo(secondOptions);

        session.completeCurrentTurn(TestLiveSession.ok("second-done"));
        first.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
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
