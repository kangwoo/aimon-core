package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * A keyed submission that runs on its own node still caches its result — the plainest idempotency path there is, and
 * the one the drain pass can silently break.
 *
 * <p>
 * The drain pass takes each queued message's reservation over before running it, and refuses to cache the result of a
 * message it does not hold the reservation for. The submission that <em>opened</em> the pass is the awkward case: its
 * entry already names this node from submit time, so the take-over could only refuse it, and the pass would then
 * decline to cache the one result its caller is actually waiting on. {@code runTurnLoop} therefore tells {@code drain}
 * which identity that reservation is held under, and this test is the guard on that argument.
 *
 * <p>
 * Passing the wrong thing there is a one-token mistake with no local symptom: the turn succeeds and the caller is
 * answered, so nothing fails. What breaks is later and elsewhere — the entry stays {@code IN_FLIGHT} naming this node,
 * the touch slot is cleared at turn end so nobody refreshes it, and after the secondary TTL the holder-loss sweeper
 * resets the key of a caller that was answered correctly minutes ago. The client's retry then re-executes instead of
 * replaying. It is the most common keyed path in production, which is why it gets a test of its own rather than a
 * corner of one about forwarded turns.
 */
@DisplayName("SessionRouter caches the result of a keyed turn it runs itself")
class SessionRouterLocalKeyedTurnIdempotencyTest {

    private static final String KEY = "k-local-1";

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a keyed turn executed locally reaches DONE with its result, so a retry replays instead of re-running")
    void aKeyedLocalTurnCachesItsResult() throws Exception {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-local-keyed-1");

        final SubmitDisposition submitted = harness.manager().submit(keyed(id, "hello"));
        assertThat(submitted.getKind()).as("no peer holds this session, so it runs here")
                .isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        final TestLiveSession session = awaitSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("hello-done"));

        assertThat(submitted.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("hello-done");

        // The assertion that matters is this one rather than the future above: the caller is answered either way, and
        // an entry left IN_FLIGHT here would only show itself a secondary TTL later, when the sweeper resets the key
        // of a turn that has already succeeded.
        final IdempotencyEntry cached = awaitDone(id);
        assertThat(cached.getStatus()).isEqualTo(IdempotencyEntry.Status.DONE);
        assertThat(cached.getResult().orElseThrow().getFinalAnswer()).isEqualTo("hello-done");
        assertThat(cached.getHolderId()).as("a settled entry names nobody — there is no turn left to lose").isEmpty();

        // What the cache is for: the same key again is a replay, not a second execution.
        final SubmitDisposition replay = harness.manager().submit(keyed(id, "hello"));
        assertThat(replay.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("hello-done");
        assertThat(session.submittedInputs()).as("the replay must not have run a second turn").containsExactly("hello");
    }

    private static SubmitRequest keyed(SessionId id, String input) {
        return SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput(input)
                .initiator(Principal.user("tester")).idempotencyKey(KEY).build();
    }

    private TestLiveSession awaitSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("a session for %s should have been opened", id).isNotNull();
        return session;
    }

    private IdempotencyEntry awaitDone(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            final Optional<IdempotencyEntry> found = harness.idempotencyStore().find(KEY);
            if (found.isPresent() && found.get().getStatus() == IdempotencyEntry.Status.DONE) {
                return found.orElseThrow();
            }
            Thread.sleep(25L);
        }
        return harness.idempotencyStore().find(KEY)
                .orElseThrow(() -> new AssertionError("the reservation for " + KEY + " is gone"));
    }
}
