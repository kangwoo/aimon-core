package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionNotHeldException;
import at.aimon.core.agent.session.store.ClaimResult;
import at.aimon.core.agent.session.store.DefaultSessionStore;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionRecord;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionStore;
import at.aimon.core.llm.Message;

/**
 * Tests for {@link SessionRecordCompactionFailureStore} — the record-backed circuit breaker.
 *
 * <p>
 * Two things separate this from {@link InMemoryCompactionFailureStoreTest}: the counter outlives the store instance
 * that wrote it (that is the whole point — a second node reads the streak the first node counted), and the write goes
 * through the lease fence. So the fixture is a real {@link DefaultSessionStore} over shared backends rather than a
 * mock, with one store per simulated node, which is how the multi-node harnesses model two nodes in one JVM.
 *
 * <p>
 * Every degradation asserted below describes something a live deployment does routinely — a fork with no session of
 * its own, an eviction mid-turn — not an error path.
 */
class SessionRecordCompactionFailureStoreTest {

    private static final SessionId SESSION = SessionId.of("conv-breaker");
    private static final String AGENT = "agent:test";
    private static final Duration LEASE = Duration.ofMinutes(5);

    private InMemorySessionRecordStore records;
    private InMemorySessionLeaseStore leases;

    @BeforeEach
    void setUp() {
        records = new InMemorySessionRecordStore();
        leases = new InMemorySessionLeaseStore();
    }

    @Test
    void countsConsecutiveFailuresOnTheRecord() {
        final CompactionFailureStore breaker = breakerOn(claimingNode("node-a"));

        assertThat(breaker.get(SESSION)).isZero();
        assertThat(breaker.recordFailure(SESSION)).isEqualTo(1);
        assertThat(breaker.recordFailure(SESSION)).isEqualTo(2);
        assertThat(breaker.get(SESSION)).isEqualTo(2);
        assertThat(records.load(SESSION).orElseThrow().getCompactionFailureCount()).isEqualTo(2);
    }

    @Test
    void resetClearsTheStreak() {
        final CompactionFailureStore breaker = breakerOn(claimingNode("node-a"));
        breaker.recordFailure(SESSION);
        breaker.recordFailure(SESSION);

        breaker.reset(SESSION);

        assertThat(breaker.get(SESSION)).isZero();
        assertThat(breaker.recordFailure(SESSION)).isEqualTo(1);
    }

    @Test
    void aSecondNodeContinuesTheStreakTheFirstOneCounted() {
        // The reason the counter is on the record at all: a per-process store would hand node B a fresh 1 here, and a
        // session whose every turn lands on a different node would never trip the breaker.
        final Node nodeA = claimingNode("node-a");
        final CompactionFailureStore breakerA = breakerOn(nodeA);
        breakerA.recordFailure(SESSION);
        breakerA.recordFailure(SESSION);
        nodeA.store.release(nodeA.lease);

        final CompactionFailureStore breakerB = breakerOn(claimingNode("node-b"));

        assertThat(breakerB.get(SESSION)).isEqualTo(2);
        assertThat(breakerB.recordFailure(SESSION)).isEqualTo(3);
    }

    @Test
    void doesNotDisturbTheRestOfTheRecord() {
        records.save(new SessionRecord(SESSION, "sys", List.of(Message.user("hello")), 0, AGENT));
        final CompactionFailureStore breaker = breakerOn(claimingNode("node-a"));

        breaker.recordFailure(SESSION);
        breaker.reset(SESSION);

        assertThat(records.load(SESSION).orElseThrow().getMessages()).hasSize(1);
        assertThat(records.load(SESSION).orElseThrow().getSystemPrompt()).isEqualTo("sys");
        assertThat(records.load(SESSION).orElseThrow().getAgentRef()).contains(AGENT);
    }

    @Test
    void anIdThatNamesNoRecordCountsNothingAndProvisionsNothing() {
        // A subagent fork labels its transcript with its ExecutionId, so the id that reaches the guard names no
        // session — nothing ever provisioned it. Asserted against the raw store because that is the only way to reach
        // the no-record path: through the fenced view a fork is refused one step earlier (it holds no lease for a
        // label it never claimed), which lands on the same 0 by the other route. Either way the count never rises, so
        // the shared breaker stays open for runs that have no session to break it for.
        final SessionId forkLabel = SessionId.of("subagent:reviewer:01HV9Z");
        final CompactionFailureStore breaker = new SessionRecordCompactionFailureStore(records);

        assertThat(breaker.recordFailure(forkLabel)).isZero();
        assertThat(breaker.recordFailure(forkLabel)).isZero();
        assertThat(breaker.get(forkLabel)).isZero();
        assertThat(records.exists(forkLabel)).isFalse();

        breaker.reset(forkLabel);
        assertThat(records.exists(forkLabel)).isFalse();
    }

    @Test
    void aForkReachingTheFencedViewIsRefusedRatherThanCounted() {
        // The production shape of the case above: the guard inside a fork calls with the fork's transcript label while
        // this node holds a lease only for the real session.
        final SessionId forkLabel = SessionId.of("subagent:reviewer:01HV9Z");
        final CompactionFailureStore breaker = breakerOn(claimingNode("node-a"));

        assertThat(breaker.recordFailure(forkLabel)).isZero();
        assertThat(records.exists(forkLabel)).isFalse();
    }

    @Test
    void losingTheLeaseMidTurnCountsNothingRatherThanFailingTheTurn() {
        // Eviction mid-turn: the fenced write is refused. The turn is already doomed and the breaker must not be what
        // reports it — failing to record a compaction failure is not itself a compaction failure.
        final Node node = claimingNode("node-a");
        final CompactionFailureStore breaker = breakerOn(node);
        breaker.recordFailure(SESSION);
        node.store.release(node.lease);

        assertThat(breaker.recordFailure(SESSION)).isZero();
        assertThat(breaker.get(SESSION)).isEqualTo(1);
        // reset degrades the same way — silently, not by throwing out of the caller's finally block.
        breaker.reset(SESSION);
        assertThat(breaker.get(SESSION)).isEqualTo(1);
    }

    @Test
    void theFenceIsRealAndThisIsWhatItLooksLikeWithoutTheSwallow() {
        // Pins what the degradation above is degrading from: the same two writes, unmediated, throw. If FencedRecords
        // ever stopped fencing them, this test — not the one above — is the one that fails.
        final Node node = claimingNode("node-a");
        final SessionRecordStore fenced = node.store.records();
        node.store.release(node.lease);

        assertThatThrownBy(() -> fenced.incrementCompactionFailureCount(SESSION))
                .isInstanceOf(SessionNotHeldException.class);
        assertThatThrownBy(() -> fenced.resetCompactionFailureCount(SESSION))
                .isInstanceOf(SessionNotHeldException.class);
    }

    @Test
    void rejectsNullArguments() {
        final CompactionFailureStore breaker = breakerOn(claimingNode("node-a"));

        assertThatThrownBy(() -> new SessionRecordCompactionFailureStore(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> breaker.get(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> breaker.recordFailure(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> breaker.reset(null)).isInstanceOf(NullPointerException.class);
    }

    private CompactionFailureStore breakerOn(Node node) {
        return new SessionRecordCompactionFailureStore(node.store.records());
    }

    /** Builds a node-scoped store over the shared backends and has it claim {@link #SESSION}. */
    private Node claimingNode(String holderId) {
        final SessionStore store = new DefaultSessionStore(leases, records);
        final ClaimResult result = store.claim(SESSION, AGENT, holderId, LEASE);
        if (!(result instanceof ClaimResult.Acquired acquired)) {
            throw new IllegalStateException(holderId + " failed to claim the session: " + result);
        }
        return new Node(store, acquired.getLease());
    }

    /** One simulated node: its own store (its own view of which leases it holds) plus the lease it won. */
    private static final class Node {

        private final SessionStore store;
        private final SessionLease lease;

        Node(SessionStore store, SessionLease lease) {
            this.store = store;
            this.lease = lease;
        }
    }
}
