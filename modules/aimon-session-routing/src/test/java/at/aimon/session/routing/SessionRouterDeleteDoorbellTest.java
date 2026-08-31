package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * What happens to a doorbell that rings while {@code deleteSession} holds the turn gate.
 *
 * <p>
 * A delete takes the same local turn gate a turn does, and holds it for its whole duration. {@code tryDrainOnce} drops
 * a doorbell that finds that gate taken, on the standing understanding that whoever holds it re-rings on the way out —
 * that is what {@code runDrainOnly} and {@code runTurnLoop} both do in their {@code finally}. Delete did not, and every
 * peer that heard the same {@code MESSAGE_ENQUEUED} had already found the session held here and given up, so nothing
 * else was ever going to look.
 *
 * <p>
 * That only matters when the delete does <em>not</em> happen, which is the case pinned below: the record survives, its
 * queued message survives, and the notice about it has to survive too. The successful delete is the opposite case and
 * is pinned second — there a re-ring would provision the deleted record straight back and run a turn the caller asked
 * to erase, so the notice is dropped on purpose rather than answered.
 */
@DisplayName("SessionRouter deleteSession and the doorbell its turn gate swallowed")
class SessionRouterDeleteDoorbellTest {

    private SessionLeaseStore leaseStore;
    private SessionSignalBus bus;
    private SessionInbox inbox;
    private final InMemorySessionRecordStore backing = new InMemorySessionRecordStore();

    private TestManagerHarness node;

    @BeforeEach
    void wireSharedBackend() {
        leaseStore = new InMemorySessionLeaseStore();
        bus = new InMemorySignalBus();
        inbox = new InMemorySessionInbox();
    }

    @AfterEach
    void closeNode() {
        if (node != null) {
            node.close();
        }
    }

    @Test
    @DisplayName("a delete that fails re-rings the doorbell it swallowed, so the queued message still runs")
    void aFailedDeleteAnswersTheDoorbellItSwallowed() throws Exception {
        final BlockingThenFailingDelete repository = new BlockingThenFailingDelete(backing);
        node = TestManagerHarness.builder().nodeId("node-A").leaseStore(leaseStore).signalBus(bus).inbox(inbox)
                .repository(repository).build();
        final SessionId id = SessionId.of("c-del-doorbell-1");
        backing.provision(id, "alpha");
        node.manager().events(id);

        final AtomicBoolean threw = new AtomicBoolean();
        final Thread deleting = new Thread(() -> {
            try {
                node.manager().deleteSession(id);
            } catch (RuntimeException expected) {
                threw.set(true);
            }
        }, "deleting");
        deleting.setDaemon(true);
        deleting.start();

        // Parked inside store.records().delete, which is past the inbox purge — so the message below is one that landed
        // while the delete was in flight, exactly the message the swallowed doorbell was announcing.
        assertThat(repository.awaitEntered()).isTrue();
        inbox.deliver(queued(id, "alpha", "hello"));
        ringDoorbell(id);

        // The doorbell is dispatched to a worker, so give that worker time to find the gate taken and give up. Without
        // this the release below could beat it there and the drain would succeed for the ordinary reason, proving
        // nothing. The session staying unopened is what says the doorbell really was refused.
        Thread.sleep(200L);
        assertThat(node.session(id)).as("a doorbell that met the delete's turn gate must not have opened anything")
                .isNull();

        repository.release();
        deleting.join(TestLiveSession.DEFAULT_AWAIT_MS);
        assertThat(threw).as("the delete must have failed — otherwise this is the other test").isTrue();
        assertThat(backing.exists(id)).as("a failed delete leaves the record where it was").isTrue();

        final TestLiveSession session = awaitSession(id);
        assertThat(session.awaitTurnStarted()).as("the message the delete stranded must still get its turn").isTrue();
        assertThat(session.submittedInputs()).containsExactly("hello");
        session.completeCurrentTurn(TestLiveSession.ok("done"));
    }

    @Test
    @DisplayName("a delete that succeeds drops the doorbell rather than provisioning the record back")
    void aSuccessfulDeleteDropsTheDoorbell() throws Exception {
        final BlockingThenFailingDelete repository = new BlockingThenFailingDelete(backing);
        repository.succeed();
        node = TestManagerHarness.builder().nodeId("node-A").leaseStore(leaseStore).signalBus(bus).inbox(inbox)
                .repository(repository).build();
        final SessionId id = SessionId.of("c-del-doorbell-2");
        backing.provision(id, "alpha");
        node.manager().events(id);

        final Thread deleting = new Thread(() -> node.manager().deleteSession(id), "deleting");
        deleting.setDaemon(true);
        deleting.start();

        assertThat(repository.awaitEntered()).isTrue();
        inbox.deliver(queued(id, "alpha", "too-late"));
        ringDoorbell(id);
        Thread.sleep(200L);

        repository.release();
        deleting.join(TestLiveSession.DEFAULT_AWAIT_MS);
        assertThat(deleting.isAlive()).isFalse();
        assertThat(backing.exists(id)).isFalse();

        // Long enough for a re-ring's pass to have opened something, had one been scheduled. The record staying absent
        // is the assertion that matters: runDrainOnly provisions before it opens, so a session here would mean the
        // delete had been undone by the notice it left behind.
        Thread.sleep(300L);
        assertThat(backing.exists(id)).as("nothing may provision the record back on the way out of a delete").isFalse();
        assertThat(node.session(id)).as("and so nothing may open a session for it either").isNull();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------------------------------

    private static InboundMessage queued(SessionId id, String agentRef, String input) {
        return InboundMessage.builder().sessionId(id).agentRef(agentRef).userInput(input).turnId(TurnId.generate())
                .priority(QueuedInputPriority.NEXT).initiator(Principal.user("tester")).deliveredAt(Instant.now())
                .build();
    }

    /** Rings {@code id}'s doorbell as a peer would — the origin must be foreign, or {@code onSignal} drops it. */
    private void ringDoorbell(SessionId id) {
        bus.publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.MESSAGE_ENQUEUED)
                .originNodeId("node-Z").build());
    }

    private TestLiveSession awaitSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (node.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        final TestLiveSession session = node.session(id);
        assertThat(session).as("a session for %s should have been opened", id).isNotNull();
        return session;
    }

    /**
     * A repository whose {@code delete} parks until the test lets it go, then either throws or delegates.
     *
     * <p>
     * The park is what makes the doorbell's timing controllable: {@code delete} is called with the turn gate held and
     * after the inbox purge, so a message delivered while it is parked is one the delete cannot have swept.
     */
    private static final class BlockingThenFailingDelete implements SessionRecordStore {

        private final SessionRecordStore delegate;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicBoolean shouldSucceed = new AtomicBoolean();

        BlockingThenFailingDelete(SessionRecordStore delegate) {
            this.delegate = delegate;
        }

        void succeed() {
            shouldSucceed.set(true);
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
        }

        void release() {
            gate.countDown();
        }

        @Override
        public void delete(SessionId sessionId) {
            entered.countDown();
            try {
                gate.await(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (shouldSucceed.get()) {
                delegate.delete(sessionId);
                return;
            }
            // Stands in for the fenced write a peer's steal would refuse: the delete is over, and the record is not.
            throw new IllegalStateException("delete refused for " + sessionId.value());
        }

        @Override
        public void mergeFromSnapshot(SessionSnapshot snapshot) {
            delegate.mergeFromSnapshot(snapshot);
        }

        @Override
        public SessionRecordView provision(SessionId sessionId, String agentRef) {
            return delegate.provision(sessionId, agentRef);
        }

        @Override
        public void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals,
                ExecutionBudget budgetOverride) {
            delegate.setTotalsAndBudgetOverride(sessionId, totals, budgetOverride);
        }

        @Override
        public int incrementCompactionFailureCount(SessionId sessionId) {
            return delegate.incrementCompactionFailureCount(sessionId);
        }

        @Override
        public void resetCompactionFailureCount(SessionId sessionId) {
            delegate.resetCompactionFailureCount(sessionId);
        }

        @Override
        public Optional<SessionRecordView> load(SessionId sessionId) {
            return delegate.load(sessionId);
        }

        @Override
        public List<SessionId> listSessionIds() {
            return delegate.listSessionIds();
        }

        @Override
        public boolean exists(SessionId sessionId) {
            return delegate.exists(sessionId);
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }
}
