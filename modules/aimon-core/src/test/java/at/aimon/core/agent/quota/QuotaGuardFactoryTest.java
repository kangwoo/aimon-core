package at.aimon.core.agent.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.quota.impl.DefaultQuotaScopeResolver;
import at.aimon.core.agent.quota.impl.InMemoryUsageLedger;
import at.aimon.core.agent.quota.impl.StaticQuotaPolicy;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.TokenUsage;

@DisplayName("QuotaGuardFactory")
class QuotaGuardFactoryTest {

    private static final QuotaResolutionContext CONTEXT = QuotaResolutionContext.builder()
            .agentRuntimeId(AgentRuntimeId.of("agent:ops")).sessionId(SessionId.of("s-1")).build();

    @Test
    @DisplayName("the disabled factory hands every turn the disabled guard and never asks a resolver")
    void disabled() {
        assertThat(QuotaGuardFactory.disabled().isEnabled()).isFalse();
        assertThat(QuotaGuardFactory.disabled().create(CONTEXT)).isSameAs(QuotaGuard.disabled());
    }

    @Test
    @DisplayName("an enabled factory resolves the turn's scopes and fixes their limits")
    void createsAGuardForTheResolvedScopes() {
        final InMemoryUsageLedger ledger = new InMemoryUsageLedger();
        final QuotaGuardFactory factory = QuotaGuardFactory.builder().ledger(ledger)
                .policy(StaticQuotaPolicy.builder()
                        .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(10).build()).build())
                .resolver(DefaultQuotaScopeResolver.create()).build();

        final QuotaGuard guard = factory.create(CONTEXT);
        guard.charge("m", TokenUsage.of(10, 0, 10), null);

        assertThat(guard.isEnabled()).isTrue();
        assertThat(guard.check()).hasValueSatisfying(violation -> assertThat(violation.getKey().getScope())
                .isEqualTo(QuotaScope.session(SessionId.of("s-1"))));
    }

    @Test
    @DisplayName("ledger, policy and resolver are all required")
    void requiresAllThree() {
        assertThatThrownBy(() -> QuotaGuardFactory.builder().policy(scope -> List.of())
                .resolver(DefaultQuotaScopeResolver.create()).build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QuotaGuardFactory.builder().ledger(new InMemoryUsageLedger())
                .resolver(DefaultQuotaScopeResolver.create()).build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> QuotaGuardFactory.builder().ledger(new InMemoryUsageLedger()).policy(scope -> List.of()).build())
                .isInstanceOf(NullPointerException.class);
    }
}
