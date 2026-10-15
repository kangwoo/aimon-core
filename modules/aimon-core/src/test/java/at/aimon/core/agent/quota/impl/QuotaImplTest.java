package at.aimon.core.agent.quota.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.quota.QuotaKey;
import at.aimon.core.agent.quota.QuotaLimit;
import at.aimon.core.agent.quota.QuotaResolutionContext;
import at.aimon.core.agent.quota.QuotaScope;
import at.aimon.core.agent.quota.QuotaUsage;
import at.aimon.core.agent.quota.QuotaWindow;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.cost.Money;

@DisplayName("Quota default implementations")
class QuotaImplTest {

    private static final QuotaScope SESSION = QuotaScope.session(SessionId.of("s-1"));

    @Nested
    @DisplayName("InMemoryUsageLedger")
    class Ledger {

        @Test
        @DisplayName("a key never charged reads as zero; charges add up per key")
        void accumulates() {
            final InMemoryUsageLedger ledger = new InMemoryUsageLedger();
            final QuotaKey key = QuotaKey.of(SESSION, QuotaWindow.TOTAL, "");
            final QuotaKey other = QuotaKey.of(QuotaScope.tenant("acme"), QuotaWindow.TOTAL, "");

            assertThat(ledger.get(List.of(key)).get(key)).isEqualTo(QuotaUsage.zero());

            ledger.charge(List.of(key), QuotaUsage.of(10, Money.usd(0.5)));
            ledger.charge(List.of(key, other), QuotaUsage.of(5, Money.usd(0.25)));

            final Map<QuotaKey, QuotaUsage> usage = ledger.get(List.of(key, other));
            assertThat(usage.get(key)).isEqualTo(QuotaUsage.of(15, Money.usd(0.75)));
            assertThat(usage.get(other)).isEqualTo(QuotaUsage.of(5, Money.usd(0.25)));
        }

        @Test
        @DisplayName("cost is kept in whole micro-dollars, rounded half-up per charge")
        void microDollars() {
            final InMemoryUsageLedger ledger = new InMemoryUsageLedger();
            final QuotaKey key = QuotaKey.of(SESSION, QuotaWindow.TOTAL, "");

            ledger.charge(List.of(key), QuotaUsage.of(1, Money.usd(0.0000015)));
            ledger.charge(List.of(key), QuotaUsage.of(1, Money.usd(0.0000004)));

            assertThat(ledger.get(List.of(key)).get(key).getCost().getAmount()).isEqualByComparingTo("0.000002");
        }

        @Test
        @DisplayName("concurrent charges on one key are not lost")
        void concurrentCharges() throws InterruptedException {
            final InMemoryUsageLedger ledger = new InMemoryUsageLedger();
            final QuotaKey key = QuotaKey.of(SESSION, QuotaWindow.TOTAL, "");
            final ExecutorService pool = Executors.newFixedThreadPool(8);
            for (int i = 0; i < 1_000; i++) {
                pool.execute(() -> ledger.charge(List.of(key), QuotaUsage.of(1, Money.usd(0.001))));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(ledger.get(List.of(key)).get(key)).isEqualTo(QuotaUsage.of(1_000, Money.usd(1)));
        }

        @Test
        @DisplayName("DAILY keys older than the retention are dropped; TOTAL keys are kept")
        void dropsExpiredDailyKeys() {
            final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
            final InMemoryUsageLedger ledger = new InMemoryUsageLedger(clock, Period.ofDays(7));
            final QuotaKey old = QuotaKey.of(SESSION, QuotaWindow.DAILY, "2026-10-07");
            final QuotaKey kept = QuotaKey.of(SESSION, QuotaWindow.DAILY, "2026-10-08");
            final QuotaKey total = QuotaKey.of(SESSION, QuotaWindow.TOTAL, "");

            ledger.charge(List.of(old, kept, total), QuotaUsage.of(1, Money.zeroUsd()));

            final Map<QuotaKey, QuotaUsage> usage = ledger.get(List.of(old, kept, total));
            assertThat(usage.get(old)).isEqualTo(QuotaUsage.zero());
            assertThat(usage.get(kept).getTokens()).isEqualTo(1);
            assertThat(usage.get(total).getTokens()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("StaticQuotaPolicy")
    class Policy {

        @Test
        @DisplayName("every scope of a kind gets the kind's limits; a kind with none is unlimited")
        void perKind() {
            final QuotaLimit sessionLimit = QuotaLimit.total().maxTokens(100).build();
            final QuotaLimit tenantLimit = QuotaLimit.daily().maxCost(Money.usd(500)).build();
            final StaticQuotaPolicy policy = StaticQuotaPolicy.builder().limit(QuotaScope.Kind.SESSION, sessionLimit)
                    .limit(QuotaScope.Kind.TENANT, tenantLimit).build();

            assertThat(policy.limitsFor(QuotaScope.session(SessionId.of("any")))).containsExactly(sessionLimit);
            assertThat(policy.limitsFor(QuotaScope.tenant("acme"))).containsExactly(tenantLimit);
            assertThat(policy.limitsFor(QuotaScope.principal(Principal.user("alice")))).isEmpty();
            assertThat(policy.hasCostLimit()).isTrue();
            assertThat(StaticQuotaPolicy.builder().limit(QuotaScope.Kind.SESSION, sessionLimit).build().hasCostLimit())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("DefaultQuotaScopeResolver")
    class Resolver {

        private final AgentRuntimeId tenantRuntime = AgentRuntimeId.of("agent:ops:acme");

        @Test
        @DisplayName("picks the session and the principal when present, and no tenant without a tenant function")
        void sessionAndPrincipal() {
            final QuotaResolutionContext context = QuotaResolutionContext.builder().agentRuntimeId(tenantRuntime)
                    .sessionId(SessionId.of("s-1")).principal(Principal.user("alice")).build();

            assertThat(DefaultQuotaScopeResolver.create().resolve(context)).containsExactly(SESSION,
                    QuotaScope.principal(Principal.user("alice")));
        }

        @Test
        @DisplayName("a session-less execution with no principal is charged to nothing")
        void nothingToCharge() {
            assertThat(DefaultQuotaScopeResolver.create()
                    .resolve(QuotaResolutionContext.builder().agentRuntimeId(tenantRuntime).build())).isEmpty();
        }

        @Test
        @DisplayName("withTenant adds the tenant the function names, here the runtime discriminator")
        void tenantFromDiscriminator() {
            final DefaultQuotaScopeResolver resolver = DefaultQuotaScopeResolver
                    .withTenant(ctx -> ctx.getAgentRuntimeId().discriminator());

            assertThat(resolver.resolve(QuotaResolutionContext.builder().agentRuntimeId(tenantRuntime).build()))
                    .containsExactly(QuotaScope.tenant("acme"));
            assertThat(resolver
                    .resolve(QuotaResolutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:ops")).build()))
                    .as("no discriminator, no tenant").isEmpty();
        }

        @Test
        @DisplayName("a blank tenant id is treated as no tenant")
        void blankTenant() {
            assertThat(DefaultQuotaScopeResolver.withTenant(ctx -> Optional.of(" "))
                    .resolve(QuotaResolutionContext.builder().agentRuntimeId(tenantRuntime).build())).isEmpty();
        }
    }
}
