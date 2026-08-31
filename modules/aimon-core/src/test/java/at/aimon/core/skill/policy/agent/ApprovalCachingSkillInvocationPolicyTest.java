package at.aimon.core.skill.policy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.policy.RuleBasedSkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationRequest;

/** Unit tests for {@link ApprovalCachingSkillInvocationPolicy}. */
class ApprovalCachingSkillInvocationPolicyTest {

    private InMemoryAgentApprovalStore store;
    private CountingPolicy delegate;
    private ApprovalCachingSkillInvocationPolicy policy;
    private AgentRuntimeId ctxId;

    @BeforeEach
    void setUp() {
        store = new InMemoryAgentApprovalStore();
        delegate = new CountingPolicy(SkillInvocationDecision.ASK);
        policy = new ApprovalCachingSkillInvocationPolicy(store, delegate);
        ctxId = AgentRuntimeId.of("agent:test-1");
    }

    @Test
    void cacheHitShortCircuitsDelegate() {
        store.put(ctxId, "commit", SkillInvocationDecision.ALLOW);

        assertThat(policy.check(reqWithContext("commit"))).isEqualTo(SkillInvocationDecision.ALLOW);
        assertThat(delegate.callCount.get()).isZero();
    }

    @Test
    void cacheMissDelegatesToWrappedPolicy() {
        assertThat(policy.check(reqWithContext("commit"))).isEqualTo(SkillInvocationDecision.ASK);
        assertThat(delegate.callCount.get()).isOne();
    }

    @Test
    void requestWithoutRuntimeIdSkipsCacheLookup() {
        // Even if some other context cached an ALLOW, requests without a context id must not pick it up.
        store.put(ctxId, "commit", SkillInvocationDecision.ALLOW);
        delegate = new CountingPolicy(SkillInvocationDecision.DENY);
        policy = new ApprovalCachingSkillInvocationPolicy(store, delegate);

        assertThat(policy.check(reqWithoutContext("commit"))).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(delegate.callCount.get()).isOne();
    }

    @Test
    void cachedDenyOverridesPermissiveDelegate() {
        // Underlying policy would allow, but the cached deny wins.
        delegate = new CountingPolicy(SkillInvocationDecision.ALLOW);
        policy = new ApprovalCachingSkillInvocationPolicy(store, delegate);
        store.put(ctxId, "commit", SkillInvocationDecision.DENY);

        assertThat(policy.check(reqWithContext("commit"))).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(delegate.callCount.get()).isZero();
    }

    @Test
    void cacheIsScopedPerRuntimeId() {
        final AgentRuntimeId other = AgentRuntimeId.of("agent:test-2");
        store.put(other, "commit", SkillInvocationDecision.ALLOW);

        // ctxId has no entry, so the delegate runs.
        assertThat(policy.check(reqWithContext("commit"))).isEqualTo(SkillInvocationDecision.ASK);
        assertThat(delegate.callCount.get()).isOne();
    }

    @Test
    void integrationWithRuleBasedDelegate() {
        // Wire a real rule-based policy that would deny "wiki-*" by default. Cached ALLOW must override the rule.
        final SkillInvocationPolicy rules = RuleBasedSkillInvocationPolicy.builder().addDenyPattern("wiki-*").build();
        final ApprovalCachingSkillInvocationPolicy combined = new ApprovalCachingSkillInvocationPolicy(store, rules);
        store.put(ctxId, "wiki-update", SkillInvocationDecision.ALLOW);

        assertThat(combined.check(reqWithContext("wiki-update"))).isEqualTo(SkillInvocationDecision.ALLOW);
        // Cache miss for sibling skill: rule deny applies.
        assertThat(combined.check(reqWithContext("wiki-delete"))).isEqualTo(SkillInvocationDecision.DENY);
    }

    @Test
    void constructorRejectsNullStore() {
        assertThatThrownBy(
                () -> new ApprovalCachingSkillInvocationPolicy(null, RuleBasedSkillInvocationPolicy.builder().build()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullDelegate() {
        assertThatThrownBy(() -> new ApprovalCachingSkillInvocationPolicy(store, null))
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

    private SkillInvocationRequest reqWithContext(String name) {
        return SkillInvocationRequest.builder().skill(skill(name)).agentRuntimeId(ctxId).build();
    }

    private static SkillInvocationRequest reqWithoutContext(String name) {
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
