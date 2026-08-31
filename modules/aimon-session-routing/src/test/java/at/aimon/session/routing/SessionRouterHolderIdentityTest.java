package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Stage 3b: the lease holder id and the idempotency reserver id are two separate identities.
 *
 * <p>
 * They used to be one string — {@code nodeId + "/" + thread + "/" + turnSeq} — worn by both the lease
 * ({@code store.claim}) and the reservation ({@code IdempotencyEntry.holderId}). That conflation is why neither shape
 * this test asserts was reachable before:
 *
 * <ul>
 * <li>The <b>lease</b> side wants a <em>node-derived, stable</em> id, because a lease that outlives a single turn (the
 * session-lifetime lease this stage builds toward) has no per-turn string to name its holder with, and because "am I
 * the
 * holder?" is only answerable by comparing against something the node knows about itself.
 * <li>The <b>reservation</b> side wants a <em>per-attempt</em> id, and shrinking it to the node id would open an ABA on
 * one key <em>within one node</em>: attempt 1 reserves key K and stalls, the sweeper resets K after the secondary TTL,
 * a
 * client retry re-reserves K as attempt 2 on the same node, and attempt 1's late
 * {@code compareAndReset(K, nodeId)} then erases attempt 2's live reservation — after which the next retry executes a
 * second time. The per-attempt suffix is exactly what makes that CAS fail.
 * </ul>
 *
 * <p>
 * Both halves are asserted together and against the same two in-flight turns, because each one alone is satisfiable by
 * the pre-Stage-3b code in isolation; only the pair is evidence that the identities came apart.
 */
@DisplayName("SessionRouter holder identity")
class SessionRouterHolderIdentityTest {

    private static final String NODE_ID = "node-alpha";
    private static final String AGENT = "alpha";

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("lease holder is the bare node id while each reservation keeps a per-attempt holder")
    void leaseHolderIsNodeIdAndReservationHolderIsPerAttempt() throws Exception {
        harness = TestManagerHarness.builder().nodeId(NODE_ID).build();

        final SessionId first = SessionId.of("c-identity-1");
        final SessionId second = SessionId.of("c-identity-2");

        final SubmitDisposition firstOutcome = harness.manager().submit(submitWithKey(first, "k-identity-1"));
        final SubmitDisposition secondOutcome = harness.manager().submit(submitWithKey(second, "k-identity-2"));
        assertThat(firstOutcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);
        assertThat(secondOutcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        // Both turns are parked in TestLiveSession until this test releases them, so the reservations are still
        // IN_FLIGHT with a holder — markDone clears it, and asserting after completion would prove nothing.
        final TestLiveSession firstSession = waitForSession(first);
        final TestLiveSession secondSession = waitForSession(second);
        assertThat(firstSession.awaitTurnStarted()).isTrue();
        assertThat(secondSession.awaitTurnStarted()).isTrue();

        final LeaseHolder firstHolder = harness.leaseStore().findHolder(first).orElseThrow();
        final LeaseHolder secondHolder = harness.leaseStore().findHolder(second).orElseThrow();
        assertThat(firstHolder.getHolderId()).as("lease holder is the node, not the turn").isEqualTo(NODE_ID);
        assertThat(secondHolder.getHolderId()).as("lease holder is the node, not the turn").isEqualTo(NODE_ID);
        // Exclusion still comes from the fencing token, which is per-acquisition even though the holder id is not.
        assertThat(firstHolder.getFencingToken()).isNotEqualTo(secondHolder.getFencingToken());

        final IdempotencyEntry firstEntry = harness.idempotencyStore().find("k-identity-1").orElseThrow();
        final IdempotencyEntry secondEntry = harness.idempotencyStore().find("k-identity-2").orElseThrow();
        final String firstReserver = firstEntry.getHolderId().orElseThrow();
        final String secondReserver = secondEntry.getHolderId().orElseThrow();
        assertThat(firstReserver).as("reservation holder is per attempt, so a stale CAS cannot match a live one")
                .isNotEqualTo(secondReserver);
        assertThat(firstReserver).as("reservation holder is not the node id").isNotEqualTo(NODE_ID);
        assertThat(secondReserver).as("reservation holder is not the node id").isNotEqualTo(NODE_ID);

        firstSession.completeCurrentTurn(TestLiveSession.ok("done-1"));
        secondSession.completeCurrentTurn(TestLiveSession.ok("done-2"));
        firstOutcome.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);
        secondOutcome.getFuture().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static SubmitRequest submitWithKey(SessionId id, String idempotencyKey) {
        return SubmitRequest.builder().sessionId(id).agentRef(AGENT).userInput("hold")
                .initiator(Principal.user("tester")).idempotencyKey(idempotencyKey).build();
    }

    private TestLiveSession waitForSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return harness.session(id);
    }
}
