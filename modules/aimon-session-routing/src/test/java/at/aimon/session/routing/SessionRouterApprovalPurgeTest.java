package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Session-scoped skill approvals must not outlive the session they were granted in.
 *
 * <p>
 * The store is in-memory and node-local, so nothing else can clean it up: once a session is deleted its
 * repository row is gone and no later event can correct a stale entry. A session reusing the same
 * {@link SessionId} would then inherit approvals the user granted to a different session — silently, since
 * a cache hit skips the prompt entirely.
 */
@DisplayName("SessionRouter session approval purge")
class SessionRouterApprovalPurgeTest {

    private static final String SKILL = "deploy";

    private TestManagerHarness harness;
    private SessionRouter builtManager;
    private SessionApprovalStore approvals;

    @BeforeEach
    void setUp() {
        approvals = new InMemorySessionApprovalStore();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
        if (builtManager != null) {
            builtManager.close();
        }
    }

    @Test
    @DisplayName("delete drops the conversation's approvals, so reusing the id starts unapproved")
    void deleteDropsApprovalsSoAReusedIdStartsUnapproved() {
        harness = TestManagerHarness.builder().sessionApprovals(approvals).build();
        final SessionId id = SessionId.of("c-purge-1");
        seed(id);
        approvals.put(id, SKILL, SkillInvocationDecision.ALLOW);

        harness.manager().deleteSession(id);

        assertThat(harness.repository().exists(id)).isFalse();
        assertThat(approvals.get(id, SKILL)).as("a new conversation on the same id must be asked again").isEmpty();
    }

    @Test
    @DisplayName("delete leaves other conversations' approvals alone")
    void deleteLeavesOtherConversationsAlone() {
        harness = TestManagerHarness.builder().sessionApprovals(approvals).build();
        final SessionId deleted = SessionId.of("c-purge-2");
        final SessionId bystander = SessionId.of("c-purge-2-other");
        seed(deleted);
        approvals.put(deleted, SKILL, SkillInvocationDecision.ALLOW);
        approvals.put(bystander, SKILL, SkillInvocationDecision.ALLOW);

        harness.manager().deleteSession(deleted);

        assertThat(approvals.get(deleted, SKILL)).isEmpty();
        assertThat(approvals.get(bystander, SKILL)).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    @DisplayName("release drops the conversation's approvals")
    void releaseDropsApprovals() {
        harness = TestManagerHarness.builder().sessionApprovals(approvals).build();
        final SessionId id = SessionId.of("c-purge-3");
        approvals.put(id, SKILL, SkillInvocationDecision.ALLOW);

        harness.manager().releaseSession(id);

        assertThat(approvals.get(id, SKILL)).isEmpty();
    }

    @Test
    @DisplayName("EVICT from a peer node drops this node's copy — the peer's delete cannot reach it")
    void peerEvictDropsTheLocalCopy() throws Exception {
        harness = TestManagerHarness.builder().nodeId("node-A").sessionApprovals(approvals).build();
        final SessionId id = SessionId.of("c-purge-4");
        approvals.put(id, SKILL, SkillInvocationDecision.ALLOW);

        // events(...) arms this node's signal rail; without it the manager never hears the peer's EVICT.
        final CountDownLatch terminated = new CountDownLatch(1);
        harness.manager().events(id).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentExecutionEvent item) {
                /* terminal InterruptedAt — not under test here */ }

            @Override
            public void onError(Throwable throwable) {
                /* not expected */ }

            @Override
            public void onComplete() {
                terminated.countDown();
            }
        });

        harness.signalBus().publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.EVICT)
                .originNodeId("node-B").payload(Map.of("reason", InterruptReason.SESSION_RELEASED.name())).build());

        // The handler purges before it completes the publisher, so onComplete means the purge has already run.
        assertThat(terminated.await(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS))
                .as("EVICT must reach the handler").isTrue();
        assertThat(approvals.get(id, SKILL)).isEmpty();
    }

    @Test
    @DisplayName("a throwing approval store never fails the delete")
    void aThrowingStoreDoesNotFailTheDelete() {
        final ThrowingApprovalStore throwing = new ThrowingApprovalStore();
        harness = TestManagerHarness.builder().sessionApprovals(throwing).build();
        final SessionId id = SessionId.of("c-purge-5");
        seed(id);

        assertThatCode(() -> harness.manager().deleteSession(id)).doesNotThrowAnyException();

        assertThat(throwing.invalidated).containsExactly(id);
        assertThat(harness.repository().exists(id)).as("the conversation must still be deleted").isFalse();
    }

    @Test
    @DisplayName("no store wired: release and delete behave exactly as before")
    void withoutAStoreTheLifecycleIsUnchanged() {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("c-purge-6");
        seed(id);

        assertThatCode(() -> harness.manager().releaseSession(id)).doesNotThrowAnyException();
        assertThatCode(() -> harness.manager().deleteSession(id)).doesNotThrowAnyException();
        assertThat(harness.repository().exists(id)).isFalse();
    }

    @Test
    @DisplayName("manager shutdown keeps approvals — evicting a session is not the end of the conversation")
    void shutdownKeepsApprovals() {
        harness = TestManagerHarness.builder().sessionApprovals(approvals).build();
        final SessionId id = SessionId.of("c-purge-7");
        approvals.put(id, SKILL, SkillInvocationDecision.ALLOW);

        harness.close();
        harness = null;

        // Same boundary as idle-TTL eviction: the session survives, so the answer the user gave it must too.
        assertThat(approvals.get(id, SKILL)).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    @DisplayName("the public builder actually wires the store through to the manager")
    void publicBuilderWiresTheStore() {
        final ConcurrentMap<SessionId, TestLiveSession> sessions = new ConcurrentHashMap<>();
        final LiveSessionOpener opener = (id, agentRuntimeId, options, attrs) -> sessions.computeIfAbsent(id,
                TestLiveSession::new);
        // Asserting through the built manager rather than on the builder: a getter-level check would still pass if
        // SessionRouterConfig dropped the store on the way to DefaultSessionRouter.
        builtManager = SessionRouter.builder().sessionOpener(opener)
                .sessionRecordStore(new InMemorySessionRecordStore()).idleTtl(Duration.ofMinutes(10))
                .nodeId("node-" + UUID.randomUUID()).sessionApprovalStore(approvals).build();

        final SessionId id = SessionId.of("c-purge-8");
        approvals.put(id, SKILL, SkillInvocationDecision.ALLOW);

        builtManager.releaseSession(id);

        assertThat(approvals.get(id, SKILL)).isEmpty();
    }

    private void seed(SessionId id) {
        harness.repository().provision(id, "alpha");
    }

    /** Stands in for a shared-backend store that is momentarily unreachable. */
    private static final class ThrowingApprovalStore implements SessionApprovalStore {

        private final CopyOnWriteArrayList<SessionId> invalidated = new CopyOnWriteArrayList<>();

        @Override
        public Optional<SkillInvocationDecision> get(SessionId sessionId, String skillName) {
            return Optional.empty();
        }

        @Override
        public void put(SessionId sessionId, String skillName, SkillInvocationDecision decision) {
            /* not used */ }

        @Override
        public void invalidate(SessionId sessionId) {
            invalidated.add(sessionId);
            throw new IllegalStateException("approval backend unreachable");
        }
    }
}
