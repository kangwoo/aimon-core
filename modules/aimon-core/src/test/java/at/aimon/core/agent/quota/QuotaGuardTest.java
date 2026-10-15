package at.aimon.core.agent.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.quota.exception.QuotaLedgerUnavailableException;
import at.aimon.core.agent.quota.impl.InMemoryUsageLedger;
import at.aimon.core.agent.quota.impl.StaticQuotaPolicy;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.Money;

@DisplayName("QuotaGuard")
class QuotaGuardTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final QuotaScope SESSION = QuotaScope.session(SessionId.of("s-1"));
    private static final QuotaScope ALICE = QuotaScope.principal(Principal.user("alice"));

    private static TokenUsage tokens(int total) {
        return TokenUsage.of(total, 0, total);
    }

    private static QuotaGuard guard(UsageLedger ledger, QuotaPolicy policy, Clock clock, QuotaScope... scopes) {
        return QuotaGuard.builder().ledger(ledger).policy(policy).scopes(List.of(scopes)).clock(clock).zone(SEOUL)
                .build();
    }

    @Nested
    @DisplayName("disabled")
    class Disabled {

        @Test
        @DisplayName("checks nothing and charges nothing")
        void inert() {
            final QuotaGuard guard = QuotaGuard.disabled();

            guard.charge("m", tokens(1_000_000), Money.usd(1_000));

            assertThat(guard.isEnabled()).isFalse();
            assertThat(guard.check()).isEmpty();
        }

        @Test
        @DisplayName("a guard whose scopes the policy leaves unlimited is not enabled and never touches the ledger")
        void unlimitedScopesNeverTouchTheLedger() {
            final RecordingLedger ledger = new RecordingLedger(new InMemoryUsageLedger());
            final QuotaGuard guard = guard(ledger, StaticQuotaPolicy.builder().build(), Clock.systemUTC(), SESSION);

            guard.charge("m", tokens(10), null);

            assertThat(guard.isEnabled()).isFalse();
            assertThat(guard.check()).isEmpty();
            assertThat(ledger.calls).isEmpty();
        }
    }

    @Nested
    @DisplayName("check and charge")
    class CheckAndCharge {

        @Test
        @DisplayName("reports the violation once usage reaches the limit (>=), naming key, limit and usage")
        void reachesAtTheBoundary() {
            final QuotaLimit limit = QuotaLimit.total().maxTokens(30).build();
            final QuotaGuard guard = guard(new InMemoryUsageLedger(),
                    StaticQuotaPolicy.builder().limit(QuotaScope.Kind.SESSION, limit).build(), Clock.systemUTC(),
                    SESSION);

            guard.charge("m", tokens(29), null);
            assertThat(guard.check()).isEmpty();

            guard.charge("m", tokens(1), null);
            assertThat(guard.check()).hasValueSatisfying(violation -> {
                assertThat(violation.getKey()).isEqualTo(QuotaKey.of(SESSION, QuotaWindow.TOTAL, ""));
                assertThat(violation.getLimit()).isEqualTo(limit);
                assertThat(violation.getUsed().getTokens()).isEqualTo(30);
                assertThat(violation.describe()).isEqualTo("quota session:s-1:total reached: 30 tokens / 30 tokens");
            });
        }

        @Test
        @DisplayName("charges every scope's key with the same call")
        void chargesEveryScope() {
            final InMemoryUsageLedger ledger = new InMemoryUsageLedger();
            final QuotaGuard guard = guard(ledger,
                    StaticQuotaPolicy.builder()
                            .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1_000).build())
                            .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.total().maxCost(Money.usd(1)).build()).build(),
                    Clock.systemUTC(), SESSION, ALICE);

            guard.charge("m", tokens(10), Money.usd(0.25));

            final QuotaKey sessionKey = QuotaKey.of(SESSION, QuotaWindow.TOTAL, "");
            final QuotaKey aliceKey = QuotaKey.of(ALICE, QuotaWindow.TOTAL, "");
            final Map<QuotaKey, QuotaUsage> usage = ledger.get(List.of(sessionKey, aliceKey));
            assertThat(usage.get(sessionKey)).isEqualTo(QuotaUsage.of(10, Money.usd(0.25)));
            assertThat(usage.get(aliceKey)).isEqualTo(QuotaUsage.of(10, Money.usd(0.25)));
        }

        @Test
        @DisplayName("two limits of one window on one scope share a key, charged once")
        void sameWindowLimitsShareOneKey() {
            final RecordingLedger ledger = new RecordingLedger(new InMemoryUsageLedger());
            final QuotaGuard guard = guard(ledger, StaticQuotaPolicy.builder()
                    .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1_000).build())
                    .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxCost(Money.usd(0.10)).build()).build(),
                    Clock.systemUTC(), SESSION);

            guard.charge("m", tokens(10), Money.usd(0.10));

            assertThat(ledger.calls).containsExactly("charge:[session:s-1:total]");
            // The token limit is far off; the cost limit is reached. The second binding is the one reported.
            assertThat(guard.check()).hasValueSatisfying(
                    violation -> assertThat(violation.getLimit().getMaxCost()).contains(Money.usd(0.10)));
        }

        @Test
        @DisplayName("a null or non-USD cost is charged as zero, tokens still count")
        void unpricedCallChargesTokensOnly() {
            final InMemoryUsageLedger ledger = new InMemoryUsageLedger();
            final QuotaGuard guard = guard(ledger, StaticQuotaPolicy.builder()
                    .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxCost(Money.usd(0.01)).build()).build(),
                    Clock.systemUTC(), SESSION);

            guard.charge("m", tokens(10), null);
            guard.charge("m", tokens(5), Money.of(java.math.BigDecimal.ONE, "EUR"));

            final QuotaKey key = QuotaKey.of(SESSION, QuotaWindow.TOTAL, "");
            assertThat(ledger.get(List.of(key)).get(key)).isEqualTo(QuotaUsage.of(15, Money.zeroUsd()));
            assertThat(guard.check()).isEmpty();
        }

        @Test
        @DisplayName("the limits are fixed when the guard is built; a policy answer that changes later does not apply")
        void limitsAreFixedAtBuild() {
            final List<QuotaLimit> answer = new ArrayList<>(List.of(QuotaLimit.total().maxTokens(1_000).build()));
            final QuotaGuard guard = guard(new InMemoryUsageLedger(), scope -> List.copyOf(answer), Clock.systemUTC(),
                    SESSION);
            answer.set(0, QuotaLimit.total().maxTokens(1).build());

            guard.charge("m", tokens(10), null);

            assertThat(guard.check()).isEmpty();
        }
    }

    @Nested
    @DisplayName("DAILY window")
    class Daily {

        @Test
        @DisplayName("resets at midnight in the configured zone, not UTC")
        void resetsAtLocalMidnight() {
            // 23:50 in Seoul on 10-15 is 14:50 UTC — still the 15th in both zones; 00:10 in Seoul on the 16th is
            // 15:10 UTC on the 15th, so a UTC bucket would not have moved.
            final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T14:50:00Z"));
            final InMemoryUsageLedger ledger = new InMemoryUsageLedger(clock,
                    InMemoryUsageLedger.DEFAULT_DAILY_RETENTION);
            final QuotaGuard guard = guard(ledger,
                    StaticQuotaPolicy.builder()
                            .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.daily().maxTokens(20).build()).build(),
                    clock, ALICE);

            guard.charge("m", tokens(25), null);
            assertThat(guard.check()).hasValueSatisfying(
                    violation -> assertThat(violation.getKey().getBucket()).isEqualTo("2026-10-15"));

            clock.advance(Duration.ofMinutes(20));

            assertThat(guard.check()).as("a new day in Seoul starts a new bucket").isEmpty();
            guard.charge("m", tokens(5), null);
            final QuotaKey today = QuotaKey.of(ALICE, QuotaWindow.DAILY, "2026-10-16");
            final QuotaKey yesterday = QuotaKey.of(ALICE, QuotaWindow.DAILY, "2026-10-15");
            final Map<QuotaKey, QuotaUsage> usage = ledger.get(List.of(today, yesterday));
            assertThat(usage.get(today).getTokens()).isEqualTo(5);
            assertThat(usage.get(yesterday).getTokens()).as("yesterday's counter is kept, not reset").isEqualTo(25);
        }

        @Test
        @DisplayName("a TOTAL limit on the same scope does not reset at midnight")
        void totalDoesNotReset() {
            final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T14:50:00Z"));
            final QuotaGuard guard = guard(new InMemoryUsageLedger(clock, InMemoryUsageLedger.DEFAULT_DAILY_RETENTION),
                    StaticQuotaPolicy.builder()
                            .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.total().maxTokens(20).build()).build(),
                    clock, ALICE);

            guard.charge("m", tokens(25), null);
            clock.advance(Duration.ofDays(3));

            assertThat(guard.check()).isPresent();
        }
    }

    @Nested
    @DisplayName("ledger failure")
    class LedgerFailure {

        private final QuotaPolicy policy = StaticQuotaPolicy.builder()
                .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1).build()).build();

        @Test
        @DisplayName("fails open by default: no violation, the charge is dropped, nothing is thrown")
        void failsOpen() {
            final QuotaGuard guard = guard(new FailingLedger(), policy, Clock.systemUTC(), SESSION);

            guard.charge("m", tokens(10), null);

            assertThat(guard.check()).isEmpty();
        }

        @Test
        @DisplayName("failClosed(true) throws QuotaLedgerUnavailableException from check and charge")
        void failsClosed() {
            final QuotaGuard guard = QuotaGuard.builder().ledger(new FailingLedger()).policy(policy)
                    .scopes(List.of(SESSION)).failClosed(true).build();

            assertThatThrownBy(guard::check).isInstanceOf(QuotaLedgerUnavailableException.class)
                    .hasMessageContaining("check").hasRootCauseMessage("ledger down");
            assertThatThrownBy(() -> guard.charge("m", tokens(10), null))
                    .isInstanceOf(QuotaLedgerUnavailableException.class).hasMessageContaining("charge");
        }
    }

    @Test
    @DisplayName("building requires a ledger and a policy")
    void requiresLedgerAndPolicy() {
        assertThatThrownBy(() -> QuotaGuard.builder().policy(scope -> List.of()).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Ledger");
        assertThatThrownBy(() -> QuotaGuard.builder().ledger(new InMemoryUsageLedger()).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Policy");
    }

    /** A ledger whose backend is down. */
    static final class FailingLedger implements UsageLedger {
        @Override
        public void charge(Collection<QuotaKey> keys, QuotaUsage usage) {
            throw new IllegalStateException("ledger down");
        }

        @Override
        public Map<QuotaKey, QuotaUsage> get(Collection<QuotaKey> keys) {
            throw new IllegalStateException("ledger down");
        }
    }

    /** Records which keys each call touched, then delegates. */
    private static final class RecordingLedger implements UsageLedger {
        private final UsageLedger delegate;
        private final List<String> calls = new ArrayList<>();

        private RecordingLedger(UsageLedger delegate) {
            this.delegate = delegate;
        }

        @Override
        public void charge(Collection<QuotaKey> keys, QuotaUsage usage) {
            calls.add("charge:" + keys);
            delegate.charge(keys, usage);
        }

        @Override
        public Map<QuotaKey, QuotaUsage> get(Collection<QuotaKey> keys) {
            calls.add("get:" + keys);
            return delegate.get(keys);
        }
    }

    /** A clock a test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
