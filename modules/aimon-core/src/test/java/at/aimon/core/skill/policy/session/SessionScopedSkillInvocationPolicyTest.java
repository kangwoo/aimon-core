package at.aimon.core.skill.policy.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.policy.RuleBasedSkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationRequest;
import at.aimon.core.skill.policy.agent.ApprovalCachingSkillInvocationPolicy;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;

/** Unit tests for {@link SessionScopedSkillInvocationPolicy}. */
class SessionScopedSkillInvocationPolicyTest {

    private InMemorySessionApprovalStore store;
    private CountingPolicy delegate;
    private SessionScopedSkillInvocationPolicy policy;
    private SessionId convId;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionApprovalStore();
        delegate = new CountingPolicy(SkillInvocationDecision.ASK);
        policy = new SessionScopedSkillInvocationPolicy(store, delegate);
        convId = SessionId.of("conv-1");
    }

    @Test
    void cacheHitShortCircuitsDelegate() {
        store.put(convId, "commit", SkillInvocationDecision.ALLOW);

        assertThat(policy.check(reqWithSession("commit"))).isEqualTo(SkillInvocationDecision.ALLOW);
        assertThat(delegate.callCount.get()).isZero();
    }

    @Test
    void cacheMissDelegatesToWrappedPolicy() {
        assertThat(policy.check(reqWithSession("commit"))).isEqualTo(SkillInvocationDecision.ASK);
        assertThat(delegate.callCount.get()).isOne();
    }

    @Test
    @DisplayName("a request carrying neither conversation id falls straight through")
    void requestWithoutConversationIdSkipsCacheLookup() {
        // Even if some other session cached an ALLOW, requests without a session id must not pick it up.
        store.put(convId, "commit", SkillInvocationDecision.ALLOW);
        delegate = new CountingPolicy(SkillInvocationDecision.DENY);
        policy = new SessionScopedSkillInvocationPolicy(store, delegate);

        assertThat(policy.check(reqWithoutSession("commit"))).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(delegate.callCount.get()).isOne();
    }

    @Test
    @DisplayName("a decision stored for the invoking conversation answers for the run it spawned")
    void invokingConversationHitAnswersForTheSpawnedRun() {
        // The fork's own id is unknown to the store — nothing was ever written under it, because the user never saw it.
        store.put(convId, "commit", SkillInvocationDecision.ALLOW);

        assertThat(policy.check(reqFromFork("commit"))).isEqualTo(SkillInvocationDecision.ALLOW);
        assertThat(delegate.callCount.get()).isZero();
    }

    @Test
    @DisplayName("a refusal is inherited the same way a grant is")
    void invokingConversationDenyIsInheritedToo() {
        delegate = new CountingPolicy(SkillInvocationDecision.ALLOW);
        policy = new SessionScopedSkillInvocationPolicy(store, delegate);
        store.put(convId, "commit", SkillInvocationDecision.DENY);

        assertThat(policy.check(reqFromFork("commit"))).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(delegate.callCount.get()).isZero();
    }

    @Test
    @DisplayName("the caller's own conversation is consulted before its invoker")
    void ownConversationWinsOverInvoker() {
        final SessionId ownId = SessionId.generate();
        store.put(ownId, "commit", SkillInvocationDecision.DENY);
        store.put(convId, "commit", SkillInvocationDecision.ALLOW);

        final SkillInvocationRequest request = SkillInvocationRequest.builder().skill(skill("commit")).sessionId(ownId)
                .invokingSessionId(convId).build();

        assertThat(policy.check(request)).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(delegate.callCount.get()).isZero();
    }

    @Test
    void cachedDenyOverridesPermissiveDelegate() {
        delegate = new CountingPolicy(SkillInvocationDecision.ALLOW);
        policy = new SessionScopedSkillInvocationPolicy(store, delegate);
        store.put(convId, "commit", SkillInvocationDecision.DENY);

        assertThat(policy.check(reqWithSession("commit"))).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(delegate.callCount.get()).isZero();
    }

    @Test
    void cacheIsScopedPerConversationId() {
        store.put(SessionId.of("conv-other"), "commit", SkillInvocationDecision.ALLOW);

        // convId has no entry, so the delegate runs.
        assertThat(policy.check(reqWithSession("commit"))).isEqualTo(SkillInvocationDecision.ASK);
        assertThat(delegate.callCount.get()).isOne();
    }

    @Test
    void constructorRejectsNullStore() {
        assertThatThrownBy(
                () -> new SessionScopedSkillInvocationPolicy(null, RuleBasedSkillInvocationPolicy.builder().build()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullDelegate() {
        assertThatThrownBy(() -> new SessionScopedSkillInvocationPolicy(store, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void checkRejectsNullRequest() {
        assertThatThrownBy(() -> policy.check(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void accessorsExposeUnderlyingComponents() {
        assertThat(policy.getStore()).isSameAs(store);
        assertThat(policy.getDelegate()).isSameAs(delegate);
    }

    /**
     * The full production chain: session store -> agent store -> rules. These cases pin the ordering decision
     * itself, so reversing the two decorators at the wiring site breaks a test rather than silently changing which
     * answer wins.
     */
    @Nested
    @DisplayName("narrow-first chain")
    class NarrowFirstChain {

        private InMemoryAgentApprovalStore agentStore;
        private SkillInvocationPolicy chain;
        private AgentRuntimeId agentId;

        @BeforeEach
        void setUp() {
            agentStore = new InMemoryAgentApprovalStore();
            agentId = AgentRuntimeId.of("agent:test-1");
            // A deny pattern rather than the default decision: the rule policy allows "safe" skills (no declared tools
            // or hooks) before it ever reaches defaultDecision, and the fixture skills here are safe. Only an explicit
            // pattern gives the tail of the chain an answer that neither store could have produced.
            chain = new SessionScopedSkillInvocationPolicy(store,
                    new ApprovalCachingSkillInvocationPolicy(agentStore, RuleBasedSkillInvocationPolicy.builder()
                            .addDenyPattern("blocked-*").defaultDecision(SkillInvocationDecision.ASK).build()));
        }

        @Test
        @DisplayName("a DENY in this conversation beats an agent-wide ALLOW granted earlier")
        void conversationDenyBeatsAgentAllow() {
            agentStore.put(agentId, "deploy", SkillInvocationDecision.ALLOW);
            store.put(convId, "deploy", SkillInvocationDecision.DENY);

            // Broad-first ordering would answer ALLOW here, which would make "deny in this session"
            // unimplementable: the standing grant would always answer first.
            assertThat(chain.check(fullRequest("deploy"))).isEqualTo(SkillInvocationDecision.DENY);
        }

        @Test
        @DisplayName("an ALLOW in this conversation beats an agent-wide DENY granted earlier")
        void conversationAllowBeatsAgentDeny() {
            agentStore.put(agentId, "deploy", SkillInvocationDecision.DENY);
            store.put(convId, "deploy", SkillInvocationDecision.ALLOW);

            assertThat(chain.check(fullRequest("deploy"))).isEqualTo(SkillInvocationDecision.ALLOW);
        }

        @Test
        @DisplayName("with nothing stored for this conversation the agent-wide grant still applies")
        void agentGrantAppliesWhenConversationHasNoEntry() {
            agentStore.put(agentId, "deploy", SkillInvocationDecision.ALLOW);

            assertThat(chain.check(fullRequest("deploy"))).isEqualTo(SkillInvocationDecision.ALLOW);
        }

        @Test
        void bothStoresMissReachesTheRules() {
            assertThat(chain.check(fullRequest("blocked-deploy"))).isEqualTo(SkillInvocationDecision.DENY);
        }

        @Test
        @DisplayName("a conversation ALLOW overrides a rule that denies — the cache sits ahead of the rules")
        void conversationAllowOverridesRuleDeny() {
            store.put(convId, "blocked-deploy", SkillInvocationDecision.ALLOW);

            assertThat(chain.check(fullRequest("blocked-deploy"))).isEqualTo(SkillInvocationDecision.ALLOW);
        }

        private SkillInvocationRequest fullRequest(String name) {
            return SkillInvocationRequest.builder().skill(skill(name)).agentRuntimeId(agentId).sessionId(convId)
                    .build();
        }
    }

    private SkillInvocationRequest reqWithSession(String name) {
        return SkillInvocationRequest.builder().skill(skill(name)).sessionId(convId).build();
    }

    /**
     * A request as a subagent fork makes it: its own freshly minted session id, plus the id of the session
     * that spawned it. The two never match, and only the latter has ever been granted anything.
     */
    private SkillInvocationRequest reqFromFork(String name) {
        return SkillInvocationRequest.builder().skill(skill(name)).sessionId(SessionId.generate())
                .invokingSessionId(convId).build();
    }

    private static SkillInvocationRequest reqWithoutSession(String name) {
        return SkillInvocationRequest.builder().skill(skill(name)).build();
    }

    private static Skill skill(String name) {
        return Skill.builder().name(name).metadata(SkillMetadata.builder().name(name).description("d").build())
                .content(SkillContent.of("body")).build();
    }

    /** Trivial policy that records how many times it was consulted. */
    private static final class CountingPolicy implements SkillInvocationPolicy {

        private final SkillInvocationDecision response;
        private final AtomicInteger callCount = new AtomicInteger();

        CountingPolicy(SkillInvocationDecision response) {
            this.response = response;
        }

        @Override
        public SkillInvocationDecision check(SkillInvocationRequest request) {
            callCount.incrementAndGet();
            return response;
        }
    }
}
