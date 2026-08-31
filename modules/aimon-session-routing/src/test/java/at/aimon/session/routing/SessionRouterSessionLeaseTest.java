package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Stage 3b: the session lease is scoped to the <em>session</em>, not to a turn.
 *
 * <p>
 * Until this stage the lease was won in {@code submit} and returned in {@code runTurnLoop}'s {@code finally}, so
 * between
 * turns nobody held the session. Every existing test around holdership passes because its test session
 * <em>completes</em> its turn — the tree had no test at all for the state that matters most now: <b>a holder sitting
 * idle
 * with the lease still in hand</b>. That state is what makes a session worth having (the next turn skips the election
 * entirely) and it is also the state that leaks a session to one node forever if the return path is wrong. Hence
 * the
 * two halves below: one asserting the lease survives a turn, and one per way a session can go away asserting that it
 * does
 * not survive the session.
 *
 * <p>
 * The exclusion the turn-scoped lease used to provide as a side effect — a second submission for the same session
 * finding it "held elsewhere" even when the holder was this very node — is now a node-local turn gate. The last test
 * pins
 * that: it would deadlock (message stuck in the inbox) or corrupt (two concurrent turns on one session) if the gate
 * were
 * missing, and {@code TestLiveSession} fails loudly on the latter.
 */
@DisplayName("SessionRouter session-scoped lease")
class SessionRouterSessionLeaseTest {

    private static final String NODE_ID = "node-lease";

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("the lease outlives the turn, and the next turn reuses it rather than re-electing")
    void leaseSurvivesTheTurnAndIsReusedByTheNext() throws Exception {
        harness = TestManagerHarness.builder().nodeId(NODE_ID).build();
        final SessionId id = SessionId.of("c-lease-1");

        runOneTurn(id, "first", "first-done");

        final LeaseHolder afterFirst = harness.leaseStore().findHolder(id).orElseThrow();
        assertThat(afterFirst.getHolderId()).as("an idle holder keeps the conversation").isEqualTo(NODE_ID);

        runOneTurn(id, "second", "second-done");

        final LeaseHolder afterSecond = harness.leaseStore().findHolder(id).orElseThrow();
        // The token is minted per acquisition, so an unchanged token is proof the second turn did not release and
        // re-take the lease — it inherited the one the session already held.
        assertThat(afterSecond.getFencingToken()).as("the second turn reuses the session's lease")
                .isEqualTo(afterFirst.getFencingToken());
        assertThat(harness.session(id).submittedInputs()).containsExactly("first", "second");
    }

    @Test
    @DisplayName("idle-TTL eviction returns the lease")
    void idleTtlEvictionReturnsTheLease() throws Exception {
        // The manager floors its sweep cadence at one second, so a TTL below that expires between two sweeps and the
        // second sweep is the one that closes the session. Nothing here waits on the TTL itself.
        harness = TestManagerHarness.builder().nodeId(NODE_ID).idleTtl(Duration.ofMillis(200)).build();
        final SessionId id = SessionId.of("c-lease-2");

        runOneTurn(id, "hello", "done");
        assertThat(harness.leaseStore().findHolder(id)).as("held while the session is cached").isPresent();

        assertThat(awaitLeaseReturned(id)).as("the idle sweep must return the lease, not only close the session")
                .isTrue();
        assertThat(harness.session(id).isClosed()).as("the session was closed, which is what returned it").isTrue();
    }

    @Test
    @DisplayName("releaseSession returns the lease")
    void releaseConversationReturnsTheLease() throws Exception {
        harness = TestManagerHarness.builder().nodeId(NODE_ID).releaseInterruptTimeout(Duration.ofMillis(50)).build();
        final SessionId id = SessionId.of("c-lease-3");

        runOneTurn(id, "hello", "done");
        assertThat(harness.leaseStore().findHolder(id)).isPresent();

        harness.manager().releaseSession(id);

        assertThat(harness.leaseStore().findHolder(id)).as("an explicit release must not leave the lease behind")
                .isEmpty();
    }

    @Test
    @DisplayName("closeGracefully returns every idle held lease")
    void closeGracefullyReturnsIdleHeldLeases() throws Exception {
        harness = TestManagerHarness.builder().nodeId(NODE_ID).build();
        final SessionId first = SessionId.of("c-lease-4a");
        final SessionId second = SessionId.of("c-lease-4b");

        runOneTurn(first, "hello", "done");
        runOneTurn(second, "hello", "done");
        assertThat(harness.leaseStore().findHolder(first)).isPresent();
        assertThat(harness.leaseStore().findHolder(second)).isPresent();

        assertThat(harness.manager().closeGracefully(Duration.ofSeconds(2))).as("no turn is in flight").isTrue();

        // Before Stage 3b this shut down having returned exactly zero leases, because there were none to return: the
        // turn loop had already given each one back. Now a draining node owes the cluster every lease it is holding,
        // or the sessions it served stay pinned to a dead node for a full lockLease.
        assertThat(harness.leaseStore().findHolder(first)).isEmpty();
        assertThat(harness.leaseStore().findHolder(second)).isEmpty();
    }

    @Test
    @DisplayName("a second submission during a turn is queued and drained by the holder, not run concurrently")
    void secondSubmissionDuringATurnIsQueued() throws Exception {
        harness = TestManagerHarness.builder().nodeId(NODE_ID).build();
        final SessionId id = SessionId.of("c-lease-5");

        final SubmitDisposition first = harness.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        assertThat(first.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();

        // The lease no longer says "somebody else has it" — this node holds it for the whole session. What keeps the
        // second submission out of the running session is the node-local turn gate.
        final SubmitDisposition second = harness.manager().submit(RequestFixtures.submit(id, "alpha", "second"));
        assertThat(second.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
        assertThat(harness.inbox().isEmpty(id)).as("the queued message waits in the inbox").isFalse();

        session.completeCurrentTurn(TestLiveSession.ok("first-done"));

        // Both turns are completed before either future is read: the holder drains the queue inside the same turn loop
        // and only resolves the submitter's future once the whole pass is done, so waiting on the first future here
        // would deadlock against the second turn this thread has yet to complete.
        assertThat(session.awaitTurnInFlight()).as("the holder must pick the queued message up").isTrue();
        assertThat(session.submittedInputs()).hasSize(2);
        session.completeCurrentTurn(TestLiveSession.ok("second-done"));

        assertThat(first.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("first-done");
        assertThat(second.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo("second-done");
        assertThat(session.submittedInputs()).containsExactly("first", "second");
    }

    /**
     * Runs one turn to completion as setup for whatever the caller actually asserts.
     *
     * <p>
     * The route is deliberately not pinned. The turn gate outlives the caller-visible future by the width of the
     * post-turn cleanup — the lease decision, the status publish, the unpin — so a client that submits the instant its
     * previous answer arrives can legitimately find the gate still held and be queued rather than run inline. Both
     * routes reach the same session and produce the same answer, which is all this helper promises. The tests that
     * care about a specific route pin it themselves: {@code secondSubmissionDuringATurnIsQueued} for the queued one.
     */
    private void runOneTurn(SessionId id, String input, String answer) throws Exception {
        final SubmitDisposition outcome = harness.manager().submit(RequestFixtures.submit(id, "alpha", input));
        assertThat(outcome.getKind()).isIn(SubmitDisposition.Kind.EXECUTED_LOCALLY, SubmitDisposition.Kind.FORWARDED);
        final TestLiveSession session = waitForSession(id);
        // Not awaitTurnStarted(): its latch is one-shot, so on the second call through here it returns without waiting
        // for the second turn at all, and the completion below lands on an idle session.
        assertThat(session.awaitTurnInFlight()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok(answer));
        assertThat(outcome.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS).getFinalAnswer())
                .isEqualTo(answer);
    }

    private boolean awaitLeaseReturned(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            final Optional<LeaseHolder> holder = harness.leaseStore().findHolder(id);
            if (holder.isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private TestLiveSession waitForSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(harness.session(id)).as("session for %s should be opened", id).isNotNull();
        return harness.session(id);
    }
}
