package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionNotHeldException;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.llm.TokenUsage;

/**
 * Tests for {@link DefaultSessionStore} — the composite that folds election, record provisioning and the agent
 * binding into one decision.
 *
 * <p>
 * Three properties carry the design and each has its own section below: {@code claim} answers with exactly one of three
 * outcomes and never leaves a lease behind on the two that are not {@code Acquired}; {@code acquire} is the same
 * election
 * without the binding question, for the callers that have no agent to assert; and {@code records()} refuses a write
 * from a
 * node that does not hold the session, which is what keeps a superseded holder from appending to history behind
 * the new
 * holder's back.
 *
 * <p>
 * Two stores over one pair of backends stand in for two nodes. That is the arrangement the class javadoc insists on and
 * the
 * only way to observe the fencing behaviour at all — a single shared store would consider both nodes' leases its own.
 */
@DisplayName("DefaultSessionStore")
class DefaultSessionStoreTest {

    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final String AGENT = "agent:ops";
    /** Static so {@link #mutators()} can name it; {@code conv} is the same id, kept for readability at call sites. */
    private static final SessionId CONV = SessionId.of("conv-1");

    private MutableClock clock;
    private SessionLeaseStore leases;
    private SessionRecordStore repository;
    private DefaultSessionStore nodeA;
    private DefaultSessionStore nodeB;
    private SessionId conv;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        leases = new InMemorySessionLeaseStore(clock);
        repository = new InMemorySessionRecordStore();
        nodeA = new DefaultSessionStore(leases, repository);
        nodeB = new DefaultSessionStore(leases, repository);
        conv = CONV;
    }

    @Nested
    @DisplayName("claim")
    class Claim {

        @Test
        @DisplayName("acquires an unheld conversation, provisions its record, and writes the binding")
        void acquiresAndBindsFirstTurn() {
            final ClaimResult result = nodeA.claim(conv, AGENT, "node-A", LEASE);

            assertThat(result).isInstanceOf(ClaimResult.Acquired.class);
            final ClaimResult.Acquired acquired = (ClaimResult.Acquired) result;
            assertThat(acquired.getLease().getHolderId()).isEqualTo("node-A");
            assertThat(acquired.getRecord().getId()).isEqualTo(conv);
            // The record is returned already carrying the binding — not as it looked before the write. A caller that
            // read getAgentRef() off a pre-write snapshot would see an unbound session on every first turn.
            assertThat(acquired.getRecord().getAgentRef()).contains(AGENT);
            assertThat(repository.load(conv).orElseThrow().getAgentRef()).contains(AGENT);
        }

        @Test
        @DisplayName("accepts a second claim naming the same agent as the persisted binding")
        void acceptsMatchingBinding() {
            final ClaimResult first = nodeA.claim(conv, AGENT, "node-A", LEASE);
            nodeA.release(((ClaimResult.Acquired) first).getLease());

            final ClaimResult second = nodeB.claim(conv, AGENT, "node-B", LEASE);

            assertThat(second).isInstanceOf(ClaimResult.Acquired.class);
            assertThat(((ClaimResult.Acquired) second).getRecord().getAgentRef()).contains(AGENT);
        }

        @Test
        @DisplayName("reports HeldElsewhere, naming the holder, when another node owns the conversation")
        void reportsHeldElsewhere() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);

            final ClaimResult result = nodeB.claim(conv, AGENT, "node-B", LEASE);

            assertThat(result).isInstanceOf(ClaimResult.HeldElsewhere.class);
            final Optional<LeaseHolder> holder = ((ClaimResult.HeldElsewhere) result).getHolder();
            assertThat(holder).isPresent();
            assertThat(holder.orElseThrow().getHolderId()).isEqualTo("node-A");
        }

        @Test
        @DisplayName("a losing claim does not make the loser a local holder")
        void losingClaimGrantsNoWriteRights() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);
            nodeB.claim(conv, AGENT, "node-B", LEASE);

            assertThatExceptionOfType(SessionNotHeldException.class)
                    .isThrownBy(() -> nodeB.records().provision(conv, AGENT));
        }

        @Test
        @DisplayName("reports AgentConflict when the persisted binding names a different agent")
        void reportsAgentConflict() {
            final ClaimResult first = nodeA.claim(conv, AGENT, "node-A", LEASE);
            nodeA.release(((ClaimResult.Acquired) first).getLease());

            final ClaimResult result = nodeB.claim(conv, "agent:other", "node-B", LEASE);

            assertThat(result).isInstanceOf(ClaimResult.AgentConflict.class);
            final ClaimResult.AgentConflict conflict = (ClaimResult.AgentConflict) result;
            assertThat(conflict.getBoundAgentRef()).isEqualTo(AGENT);
            assertThat(conflict.getRequestedAgentRef()).isEqualTo("agent:other");
            // A conflict must be detected without the losing claim having written anything: provision binds only an
            // unbound record, so the established binding is what claim reads back and what stays behind.
            assertThat(repository.load(conv).orElseThrow().getAgentRef()).contains(AGENT);
        }

        @Test
        @DisplayName("a rejected claim returns the lease, so the conversation is not pinned")
        void agentConflictReturnsTheLease() {
            final ClaimResult first = nodeA.claim(conv, AGENT, "node-A", LEASE);
            nodeA.release(((ClaimResult.Acquired) first).getLease());
            nodeB.claim(conv, "agent:other", "node-B", LEASE);

            // Without the release inside claim, a client retrying with the wrong agentRef would lock everyone out of
            // the session for a full lease period on every attempt.
            assertThat(leases.findHolder(conv)).isEmpty();
            assertThat(nodeA.claim(conv, AGENT, "node-A", LEASE)).isInstanceOf(ClaimResult.Acquired.class);
        }

        @Test
        @DisplayName("a rejected claim leaves no local holdership behind either")
        void agentConflictGrantsNoWriteRights() {
            final ClaimResult first = nodeA.claim(conv, AGENT, "node-A", LEASE);
            nodeA.release(((ClaimResult.Acquired) first).getLease());
            nodeB.claim(conv, "agent:other", "node-B", LEASE);

            assertThatExceptionOfType(SessionNotHeldException.class)
                    .isThrownBy(() -> nodeB.records().provision(conv, "agent:other"));
        }

        @Test
        @DisplayName("only one of two racing first claims establishes the binding")
        void firstTurnRaceHasOneWinner() {
            final ClaimResult a = nodeA.claim(conv, "agent:a", "node-A", LEASE);
            final ClaimResult b = nodeB.claim(conv, "agent:b", "node-B", LEASE);

            // The defect this whole type exists to close: as two separate steps, both nodes validated against the same
            // unbound record and both concluded they were free to bind it.
            assertThat(a).isInstanceOf(ClaimResult.Acquired.class);
            assertThat(b).isInstanceOf(ClaimResult.HeldElsewhere.class);
            assertThat(repository.load(conv).orElseThrow().getAgentRef()).contains("agent:a");
        }

        @Test
        @DisplayName("preserves an existing record rather than overwriting it")
        void preservesExistingRecord() {
            repository.mergeFromSnapshot(SessionSnapshot.of(conv, "you are a careful operator", List.of()));

            final ClaimResult result = nodeA.claim(conv, AGENT, "node-A", LEASE);

            final SessionRecordView record = ((ClaimResult.Acquired) result).getRecord();
            assertThat(record.getSystemPrompt()).isEqualTo("you are a careful operator");
        }

        @Test
        @DisplayName("null arguments are rejected")
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(() -> nodeA.claim(null, AGENT, "node-A", LEASE));
            assertThatNullPointerException().isThrownBy(() -> nodeA.claim(conv, null, "node-A", LEASE));
            assertThatNullPointerException().isThrownBy(() -> nodeA.claim(conv, AGENT, null, LEASE));
            assertThatNullPointerException().isThrownBy(() -> nodeA.claim(conv, AGENT, "node-A", null));
        }
    }

    @Nested
    @DisplayName("acquire")
    class Acquire {

        @Test
        @DisplayName("provisions the record without asking about the binding")
        void provisionsWithoutBinding() {
            final SessionLease lease = nodeA.acquire(conv, "node-A", LEASE).orElseThrow();

            assertThat(lease.getHolderId()).isEqualTo("node-A");
            assertThat(repository.exists(conv)).isTrue();
            assertThat(repository.load(conv).orElseThrow().getAgentRef()).isEmpty();
        }

        @Test
        @DisplayName("does not reject a conversation already bound to some other agent")
        void ignoresExistingBinding() {
            repository.provision(conv, AGENT);

            // The drain and delete paths must not be refused over a binding they never asserted — a drain adopts
            // whatever is there and a delete is about to remove it.
            assertThat(nodeA.acquire(conv, "node-A", LEASE)).isPresent();
        }

        @Test
        @DisplayName("is empty when another node holds the conversation")
        void emptyWhenHeldElsewhere() {
            nodeA.acquire(conv, "node-A", LEASE).orElseThrow();

            assertThat(nodeB.acquire(conv, "node-B", LEASE)).isEmpty();
        }

        @Test
        @DisplayName("the holder can then bind the conversation it acquired unbound")
        void holderMayBindAfterAcquiring() {
            nodeA.acquire(conv, "node-A", LEASE).orElseThrow();

            final SessionRecordView bound = nodeA.records().provision(conv, AGENT);

            // This is the drain path's "adopt the first queued message's agent" fallback: acquire holds the
            // session in order to read the message that names the agent, then binds what it read.
            assertThat(bound.getAgentRef()).contains(AGENT);
            assertThat(repository.load(conv).orElseThrow().getAgentRef()).contains(AGENT);
        }

        @Test
        @DisplayName("null arguments are rejected")
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(() -> nodeA.acquire(null, "node-A", LEASE));
            assertThatNullPointerException().isThrownBy(() -> nodeA.acquire(conv, null, LEASE));
            assertThatNullPointerException().isThrownBy(() -> nodeA.acquire(conv, "node-A", null));
        }
    }

    @Nested
    @DisplayName("renew and release")
    class RenewAndRelease {

        @Test
        @DisplayName("renew extends a lease this node still holds")
        void renewSucceedsForCurrentLease() {
            final SessionLease lease = nodeA.acquire(conv, "node-A", LEASE).orElseThrow();
            clock.advance(Duration.ofSeconds(9));

            assertThat(nodeA.renew(lease, LEASE)).isTrue();
            assertThat(leases.findHolder(conv).orElseThrow().getExpiresAt()).isEqualTo(clock.instant().plus(LEASE));
        }

        @Test
        @DisplayName("a failed renew drops local holdership, so the next write fails locally")
        void failedRenewForgetsTheLease() {
            final SessionLease mine = nodeA.acquire(conv, "node-A", LEASE).orElseThrow();
            clock.advance(LEASE);
            nodeB.acquire(conv, "node-B", LEASE).orElseThrow();

            assertThat(nodeA.renew(mine, LEASE)).isFalse();

            // The reason renewal goes through the store rather than straight to the backend: a node that lost its
            // lease must stop believing it holds one, immediately.
            assertThatExceptionOfType(SessionNotHeldException.class)
                    .isThrownBy(() -> nodeA.records().provision(conv, AGENT)).withMessageContaining("holds no lease");
        }

        @Test
        @DisplayName("release ends write rights for this node and frees the conversation")
        void releaseFreesTheConversation() {
            final SessionLease lease = nodeA.acquire(conv, "node-A", LEASE).orElseThrow();
            nodeA.release(lease);

            assertThatExceptionOfType(SessionNotHeldException.class)
                    .isThrownBy(() -> nodeA.records().provision(conv, AGENT));
            assertThat(nodeB.acquire(conv, "node-B", LEASE)).isPresent();
        }

        @Test
        @DisplayName("release of a superseded lease leaves the current holder's rights intact")
        void releaseIsTokenMatched() {
            final SessionLease stale = nodeA.acquire(conv, "node-A", LEASE).orElseThrow();
            clock.advance(LEASE);
            nodeA.acquire(conv, "node-A/retry", LEASE).orElseThrow();

            nodeA.release(stale);

            // Same store, two successive leases: forgetting must be matched on the token, or a late release from the
            // abandoned turn would revoke the live one's write rights.
            nodeA.records().provision(conv, AGENT);
            assertThat(repository.load(conv).orElseThrow().getAgentRef()).contains(AGENT);
        }

        @Test
        @DisplayName("null arguments are rejected")
        void rejectsNulls() {
            final SessionLease lease = nodeA.acquire(conv, "node-A", LEASE).orElseThrow();

            assertThatNullPointerException().isThrownBy(() -> nodeA.renew(null, LEASE));
            assertThatNullPointerException().isThrownBy(() -> nodeA.renew(lease, null));
            assertThatNullPointerException().isThrownBy(() -> nodeA.release(null));
        }
    }

    /**
     * The complete set of mutators on the fenced view, each paired with the operation name its refusal must name. Add a
     * mutator to {@link SessionRecordStore} and this list is what makes forgetting to fence it visible.
     */
    static Stream<Arguments> mutators() {
        return Stream.of(
                Arguments.of("mergeFromSnapshot",
                        (Consumer<SessionRecordStore>) r -> r.mergeFromSnapshot(SessionSnapshot.of(CONV))),
                Arguments.of("provision", (Consumer<SessionRecordStore>) r -> r.provision(CONV, AGENT)),
                Arguments.of("setTotalsAndBudgetOverride",
                        (Consumer<SessionRecordStore>) r -> r.setTotalsAndBudgetOverride(CONV, SessionTotals.empty(),
                                null)),
                Arguments.of("incrementCompactionFailureCount",
                        (Consumer<SessionRecordStore>) r -> r.incrementCompactionFailureCount(CONV)),
                Arguments.of("resetCompactionFailureCount",
                        (Consumer<SessionRecordStore>) r -> r.resetCompactionFailureCount(CONV)),
                Arguments.of("delete", (Consumer<SessionRecordStore>) r -> r.delete(CONV)));
    }

    @Nested
    @DisplayName("records()")
    class Records {

        @Test
        @DisplayName("accepts every mutation from the holder")
        void holderMayWrite() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);

            nodeA.records().provision(conv, AGENT);
            nodeA.records().mergeFromSnapshot(SessionSnapshot.of(conv, "prompt", List.of()));
            nodeA.records().setTotalsAndBudgetOverride(conv,
                    SessionTotals.empty().plusTurn(2, TokenUsage.of(10, 5, 15)), null);

            final SessionRecordView written = repository.load(conv).orElseThrow();
            assertThat(written.getSystemPrompt()).isEqualTo("prompt");
            assertThat(written.getAgentRef()).contains(AGENT);
            assertThat(written.getSessionTotals().getTurnCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("provision leaves an established binding alone")
        void provisionNeverRebinds() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);

            final SessionRecordView after = nodeA.records().provision(conv, "agent:other");

            // "Bind if unbound" and never "rebind": claim relies on this to answer AgentConflict, and it is the reason
            // provision may be called before the caller knows whether it is entitled to the session at all.
            assertThat(after.getAgentRef()).contains(AGENT);
            assertThat(repository.load(conv).orElseThrow().getAgentRef()).contains(AGENT);
        }

        /**
         * Every mutator, not a representative one. Fencing is per-method here — each override calls
         * {@code requireHeld} itself — so one unfenced method is an unfenced write path, and a test that samples the
         * set cannot see it. Dropping the guard from any single override must fail this.
         */
        @ParameterizedTest(name = "{0} refuses a node that never held the conversation")
        @MethodSource("at.aimon.core.agent.session.store.DefaultSessionStoreTest#mutators")
        @DisplayName("refuses every mutation from a node that never held the conversation")
        void nonHolderMayNotWrite(String name, Consumer<SessionRecordStore> mutation) {
            nodeA.claim(conv, AGENT, "node-A", LEASE);

            assertThatExceptionOfType(SessionNotHeldException.class).isThrownBy(() -> mutation.accept(nodeB.records()))
                    .withMessageContaining("holds no lease").withMessageContaining(name);
        }

        @Test
        @DisplayName("refuses a mutation once the lease has lapsed, even with no successor")
        void lapsedLeaseMayNotWrite() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);
            clock.advance(LEASE);

            // findHolder, not extend, is what re-proves holdership — precisely so this case fails. extend would have
            // renewed the lapsed lease and let the write through.
            assertThatExceptionOfType(SessionNotHeldException.class)
                    .isThrownBy(() -> nodeA.records().provision(conv, AGENT))
                    .withMessageContaining("no longer current");
        }

        @Test
        @DisplayName("refuses a mutation after another node has taken over")
        void supersededHolderMayNotWrite() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);
            clock.advance(LEASE);
            nodeB.claim(conv, AGENT, "node-B", LEASE);

            assertThatExceptionOfType(SessionNotHeldException.class)
                    .isThrownBy(
                            () -> nodeA.records().mergeFromSnapshot(SessionSnapshot.of(conv, "rejected", List.of())))
                    .withMessageContaining("no longer current");
            assertThat(repository.load(conv).orElseThrow().getSystemPrompt()).isNull();
        }

        @Test
        @DisplayName("a refused write for a lost lease is remembered, so the retry fails without a backend round trip")
        void rejectionForgetsTheLease() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);
            clock.advance(LEASE);

            assertThatThrownBy(() -> nodeA.records().provision(conv, AGENT)).hasMessageContaining("no longer current");
            assertThatThrownBy(() -> nodeA.records().provision(conv, AGENT)).hasMessageContaining("holds no lease");
        }

        @Test
        @DisplayName("reads pass through unfenced")
        void readsAreNotFenced() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);

            // A status projection legitimately inspects sessions it does not hold, and a read cannot corrupt
            // history.
            assertThat(nodeB.records().load(conv)).isPresent();
            assertThat(nodeB.records().exists(conv)).isTrue();
            assertThat(nodeB.records().listSessionIds()).contains(conv);
        }

        @Test
        @DisplayName("clear() is rejected outright — holdership cannot be proven for every conversation at once")
        void clearIsUnsupported() {
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> nodeA.records().clear());
        }

        @Test
        @DisplayName("delete requires holdership")
        void deleteIsFenced() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);

            assertThatExceptionOfType(SessionNotHeldException.class).isThrownBy(() -> nodeB.records().delete(conv));
            nodeA.records().delete(conv);
            assertThat(repository.exists(conv)).isFalse();
        }
    }

    @Nested
    @DisplayName("load and findHolder")
    class Reads {

        @Test
        @DisplayName("load is unfenced and empty for an unknown conversation")
        void loadIsUnfenced() {
            assertThat(nodeA.load(conv)).isEmpty();
            nodeA.claim(conv, AGENT, "node-A", LEASE);
            assertThat(nodeB.load(conv)).isPresent();
        }

        @Test
        @DisplayName("findHolder names the current holder from any node")
        void findHolderIsObservational() {
            nodeA.claim(conv, AGENT, "node-A", LEASE);

            assertThat(nodeB.findHolder(conv).orElseThrow().getHolderId()).isEqualTo("node-A");
        }

        @Test
        @DisplayName("null arguments are rejected")
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(() -> nodeA.load(null));
            assertThatNullPointerException().isThrownBy(() -> nodeA.findHolder(null));
        }
    }

    @Test
    @DisplayName("the constructor rejects null backends")
    void constructorRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new DefaultSessionStore(null, repository));
        assertThatNullPointerException().isThrownBy(() -> new DefaultSessionStore(leases, null));
    }

    /** A clock the test moves by hand, so lease expiry needs no sleeping. */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration amount) {
            now.updateAndGet(current -> current.plus(amount));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
