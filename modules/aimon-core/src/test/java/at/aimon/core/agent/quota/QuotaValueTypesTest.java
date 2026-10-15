package at.aimon.core.agent.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.cost.Money;

@DisplayName("Quota value types")
class QuotaValueTypesTest {

    @Nested
    @DisplayName("QuotaScope")
    class Scope {

        @Test
        @DisplayName("a principal's id carries its type, so a user and a service sharing an id are two scopes")
        void principalIdCarriesType() {
            final QuotaScope user = QuotaScope.principal(Principal.user("ops"));
            final QuotaScope service = QuotaScope.principal(Principal.service("ops", "Ops service"));

            assertThat(user.getId()).isEqualTo("user/ops");
            assertThat(user).isNotEqualTo(service);
        }

        @Test
        @DisplayName("a principal's display name does not change its scope")
        void displayNameIsNotIdentity() {
            assertThat(QuotaScope.principal(Principal.user("alice", "Alice")))
                    .isEqualTo(QuotaScope.principal(Principal.user("alice", "Alice Kim")));
        }

        @Test
        @DisplayName("session and tenant scopes take the id as given; a blank id is rejected")
        void sessionAndTenant() {
            assertThat(QuotaScope.session(SessionId.of("s-1")))
                    .isEqualTo(QuotaScope.of(QuotaScope.Kind.SESSION, "s-1"));
            assertThat(QuotaScope.tenant("acme").getKind()).isEqualTo(QuotaScope.Kind.TENANT);
            assertThatThrownBy(() -> QuotaScope.tenant(" ")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("QuotaWindow / QuotaKey")
    class WindowAndKey {

        @Test
        @DisplayName("TOTAL has the empty bucket; DAILY has the local date in the zone")
        void buckets() {
            final Instant at = Instant.parse("2026-10-15T15:30:00Z");

            assertThat(QuotaWindow.TOTAL.bucketAt(at, ZoneOffset.UTC)).isEmpty();
            assertThat(QuotaWindow.DAILY.bucketAt(at, ZoneOffset.UTC)).isEqualTo("2026-10-15");
            assertThat(QuotaWindow.DAILY.bucketAt(at, ZoneId.of("Asia/Seoul"))).isEqualTo("2026-10-16");
        }

        @Test
        @DisplayName("asString omits an empty bucket")
        void asString() {
            assertThat(QuotaKey.of(QuotaScope.session(SessionId.of("s-1")), QuotaWindow.TOTAL, "").asString())
                    .isEqualTo("session:s-1:total");
            assertThat(QuotaKey.of(QuotaScope.principal(Principal.user("alice")), QuotaWindow.DAILY, "2026-10-15")
                    .asString()).isEqualTo("principal:user/alice:daily:2026-10-15");
        }
    }

    @Nested
    @DisplayName("QuotaUsage / QuotaLimit")
    class UsageAndLimit {

        @Test
        @DisplayName("plus adds tokens and cost; equality ignores the cost's scale")
        void plusAndEquality() {
            final QuotaUsage sum = QuotaUsage.of(10, Money.usd(0.1)).plus(QuotaUsage.of(5, Money.usd(0.05)));

            assertThat(sum).isEqualTo(QuotaUsage.of(15, Money.usd(new BigDecimal("0.150000"))));
            assertThat(sum.hashCode()).isEqualTo(QuotaUsage.of(15, Money.usd(new BigDecimal("0.150000"))).hashCode());
            assertThat(QuotaUsage.zero().getTokens()).isZero();
        }

        @Test
        @DisplayName("usage is USD-only and non-negative")
        void usageValidation() {
            assertThatThrownBy(() -> QuotaUsage.of(-1, Money.zeroUsd())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuotaUsage.of(1, Money.zero("EUR"))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a limit is reached at either ceiling, inclusive; a limit with neither is never reached")
        void reached() {
            final QuotaLimit both = QuotaLimit.daily().maxTokens(100).maxCost(Money.usd(1)).build();

            assertThat(both.isReachedBy(QuotaUsage.of(99, Money.usd(0.99)))).isFalse();
            assertThat(both.isReachedBy(QuotaUsage.of(100, Money.zeroUsd()))).isTrue();
            assertThat(both.isReachedBy(QuotaUsage.of(0, Money.usd(1)))).isTrue();
            assertThat(QuotaLimit.total().build().isReachedBy(QuotaUsage.of(Long.MAX_VALUE, Money.usd(1e9)))).isFalse();
        }

        @Test
        @DisplayName("ceilings must be positive and USD")
        void limitValidation() {
            assertThatThrownBy(() -> QuotaLimit.total().maxTokens(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuotaLimit.total().maxCost(Money.zero("EUR")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuotaLimit.total().maxCost(Money.zeroUsd()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a violation describes only the ceilings its limit sets")
        void describe() {
            final QuotaKey key = QuotaKey.of(QuotaScope.tenant("acme"), QuotaWindow.DAILY, "2026-10-15");

            assertThat(QuotaViolation
                    .of(key, QuotaLimit.daily().maxCost(Money.usd(5)).build(), QuotaUsage.of(42, Money.usd(5)))
                    .describe()).startsWith("quota tenant:acme:daily:2026-10-15 reached: ").doesNotContain("tokens");
            assertThat(QuotaViolation.of(key, QuotaLimit.daily().maxTokens(10).maxCost(Money.usd(5)).build(),
                    QuotaUsage.of(42, Money.usd(1))).describe()).contains("42 tokens / 10 tokens,");
        }
    }
}
