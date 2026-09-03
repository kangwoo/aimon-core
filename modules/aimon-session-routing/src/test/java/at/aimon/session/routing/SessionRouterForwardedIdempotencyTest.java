package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Regression for the bug where a submit that reserved an idempotency key and then lost the session lock
 * {@code compareAndReset} the key away before forwarding the turn to the inbox.
 *
 * <p>
 * Deleting the reservation broke both halves of the idempotency contract for forwarded turns: a client retry arriving
 * during the queue wait no longer saw an entry and was executed a second time, and the holder's eventual
 * {@code markDone} — which only updates an entry that still exists — silently did nothing, so the result was never
 * cached for replay. The manager now hands the entry over instead
 * ({@link at.aimon.core.agent.session.idempotency.IdempotencyStore#releaseHolder}).
 */
@DisplayName("SessionRouter keeps the idempotency reservation across a forward to the inbox")
class SessionRouterForwardedIdempotencyTest {

    private static final String KEY = "k-forward-1";

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a forwarded turn keeps its key reserved, dedupes retries, and caches its result for replay")
    void forwardedTurnKeepsReservationAndCachesResult() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-forward-1");

        // Occupy the lock so the keyed submit below has to forward.
        final SubmitDisposition first = harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(first.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).as("first turn must have started").isTrue();

        final SubmitDisposition forwarded = harness.manager().submit(keyed(id, "queue me"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // The reservation must survive the forward, but must no longer name a holder: no node is executing it, so
        // nobody touches it and the holder-loss sweeper must not read it as a lost turn.
        final Optional<IdempotencyEntry> reserved = harness.idempotencyStore().find(KEY);
        assertThat(reserved).as("the forwarded key must stay reserved").isPresent();
        assertThat(reserved.orElseThrow().getStatus()).isEqualTo(IdempotencyEntry.Status.IN_FLIGHT);
        assertThat(reserved.orElseThrow().getHolderId()).as("a queued reservation has no holder").isEmpty();

        // A retry while the message waits in the queue must collapse onto the queued turn, not start a second one.
        final SubmitDisposition retry = harness.manager().submit(keyed(id, "queue me"));
        assertThat(retry.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // Drain: the holder picks the message up and runs it, then marks the surviving entry done.
        session.completeCurrentTurn(TestLiveSession.ok("done-1"));
        assertThat(session.awaitTurnCount(2)).as("the queued turn must be drained by the holder").isTrue();
        assertThat(session.submittedInputs()).containsExactly("hello", "queue me");
        session.completeCurrentTurn(TestLiveSession.ok("done-2"));
        first.getFuture().toCompletableFuture().get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);

        final IdempotencyEntry done = awaitDone();
        assertThat(done.getResult()).as("the forwarded turn's result must be cached").isPresent();
        assertThat(done.getResult().orElseThrow().getFinalAnswer()).isEqualTo("done-2");

        // ...and a late retry replays that cached result instead of running the input a second time.
        final SubmitDisposition replay = harness.manager().submit(keyed(id, "queue me"));
        assertThat(replay.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final AgentExecutionResult replayed = replay.getFuture().toCompletableFuture()
                .get(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        assertThat(replayed.getFinalAnswer()).isEqualTo("done-2");
        assertThat(session.submittedInputs()).as("a replayed key must not reach the session again").hasSize(2);
    }

    private static SubmitRequest keyed(SessionId id, String input) {
        return SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput(input)
                .initiator(Principal.user("tester")).idempotencyKey(KEY).build();
    }

    private IdempotencyEntry awaitDone() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            final Optional<IdempotencyEntry> entry = harness.idempotencyStore().find(KEY);
            if (entry.isPresent() && entry.get().getStatus() == IdempotencyEntry.Status.DONE) {
                return entry.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "Idempotency entry " + KEY + " never reached DONE — markDone found no entry to update");
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
