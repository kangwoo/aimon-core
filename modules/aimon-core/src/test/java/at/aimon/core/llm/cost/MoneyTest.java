package at.aimon.core.llm.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Money Tests")
class MoneyTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("of() tags the amount with the given currency")
        void ofTagsCurrency() {
            Money money = Money.of(new BigDecimal("2.50"), "EUR");

            assertThat(money.getAmount()).isEqualByComparingTo("2.50");
            assertThat(money.getCurrency()).isEqualTo("EUR");
        }

        @Test
        @DisplayName("usd(BigDecimal) uses USD")
        void usdBigDecimal() {
            assertThat(Money.usd(new BigDecimal("1.00")).getCurrency()).isEqualTo(Money.USD);
        }

        @Test
        @DisplayName("usd(double) converts via BigDecimal.valueOf without binary drift")
        void usdDouble() {
            assertThat(Money.usd(0.1).getAmount()).isEqualByComparingTo("0.1");
        }

        @Test
        @DisplayName("zeroUsd() is a zero USD amount")
        void zeroUsd() {
            Money zero = Money.zeroUsd();

            assertThat(zero.isZero()).isTrue();
            assertThat(zero.getCurrency()).isEqualTo(Money.USD);
        }

        @Test
        @DisplayName("zero(currency) is zero in that currency")
        void zeroCurrency() {
            Money zero = Money.zero("JPY");

            assertThat(zero.isZero()).isTrue();
            assertThat(zero.getCurrency()).isEqualTo("JPY");
        }

        @Test
        @DisplayName("rejects null amount")
        void rejectsNullAmount() {
            assertThatThrownBy(() -> Money.of(null, "USD")).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null currency")
        void rejectsNullCurrency() {
            assertThatThrownBy(() -> Money.of(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects empty currency")
        void rejectsEmptyCurrency() {
            assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currency");
        }

        @Test
        @DisplayName("rejects negative amount")
        void rejectsNegativeAmount() {
            assertThatThrownBy(() -> Money.usd(new BigDecimal("-0.01"))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }
    }

    @Nested
    @DisplayName("Arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("add sums same-currency amounts")
        void addSameCurrency() {
            Money sum = Money.usd(new BigDecimal("1.25")).add(Money.usd(new BigDecimal("0.75")));

            assertThat(sum.getAmount()).isEqualByComparingTo("2.00");
            assertThat(sum.getCurrency()).isEqualTo(Money.USD);
        }

        @Test
        @DisplayName("add rejects cross-currency operands")
        void addRejectsCrossCurrency() {
            assertThatThrownBy(() -> Money.usd(BigDecimal.ONE).add(Money.of(BigDecimal.ONE, "EUR")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Currency mismatch");
        }

        @Test
        @DisplayName("add rejects null operand")
        void addRejectsNull() {
            assertThatThrownBy(() -> Money.usd(BigDecimal.ONE).add(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("multiply scales the amount exactly")
        void multiply() {
            Money product = Money.usd(new BigDecimal("3.00")).multiply(BigDecimal.valueOf(1500));

            assertThat(product.getAmount()).isEqualByComparingTo("4500.00");
        }

        @Test
        @DisplayName("divide rounds to COST_SCALE with HALF_UP")
        void divideRounds() {
            Money quotient = Money.usd(BigDecimal.ONE).divide(BigDecimal.valueOf(3));

            // 1/3 rounded HALF_UP at 10 decimal places
            assertThat(quotient.getAmount()).isEqualByComparingTo("0.3333333333");
            assertThat(quotient.getAmount().scale()).isEqualTo(Money.COST_SCALE);
        }

        @Test
        @DisplayName("divide rejects zero divisor")
        void divideRejectsZero() {
            assertThatThrownBy(() -> Money.usd(BigDecimal.ONE).divide(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zero");
        }

        @Test
        @DisplayName("per-million pricing composes multiply then divide")
        void perMillionPricing() {
            // 3 USD / 1M tokens * 1500 tokens = 0.0045 USD
            Money cost = Money.usd(new BigDecimal("3.00")).multiply(BigDecimal.valueOf(1500)).divide(Money.MILLION);

            assertThat(cost.getAmount()).isEqualByComparingTo("0.0045");
        }
    }

    @Nested
    @DisplayName("Comparison")
    class Comparison {

        @Test
        @DisplayName("compareTo orders same-currency amounts")
        void compareToOrders() {
            assertThat(Money.usd(new BigDecimal("1.00"))).isLessThan(Money.usd(new BigDecimal("2.00")));
            assertThat(Money.usd(new BigDecimal("2.00"))).isGreaterThan(Money.usd(new BigDecimal("1.00")));
        }

        @Test
        @DisplayName("compareTo treats scale-different equal amounts as equal")
        void compareToIgnoresScale() {
            assertThat(Money.usd(new BigDecimal("1.0"))).isEqualByComparingTo(Money.usd(new BigDecimal("1.00")));
        }

        @Test
        @DisplayName("compareTo rejects cross-currency operands")
        void compareToRejectsCrossCurrency() {
            assertThatThrownBy(() -> Money.usd(BigDecimal.ONE).compareTo(Money.of(BigDecimal.ONE, "EUR")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Currency mismatch");
        }
    }

    @Nested
    @DisplayName("Equality and hashCode")
    class Equality {

        @Test
        @DisplayName("equal amounts with different scale are equal and hash identically")
        void scaleInsensitiveEquality() {
            Money oneDp = Money.usd(new BigDecimal("1.0"));
            Money twoDp = Money.usd(new BigDecimal("1.00"));

            assertThat(oneDp).isEqualTo(twoDp);
            assertThat(oneDp.hashCode()).isEqualTo(twoDp.hashCode());
        }

        @Test
        @DisplayName("zero amounts of different scale hash identically")
        void zeroScaleInsensitiveHash() {
            Money zeroPlain = Money.usd(BigDecimal.ZERO);
            Money zeroScaled = Money.usd(BigDecimal.ONE).multiply(BigDecimal.ZERO).divide(Money.MILLION);

            assertThat(zeroScaled.isZero()).isTrue();
            assertThat(zeroPlain).isEqualTo(zeroScaled);
            assertThat(zeroPlain.hashCode()).isEqualTo(zeroScaled.hashCode());
        }

        @Test
        @DisplayName("different currencies are not equal")
        void differentCurrenciesNotEqual() {
            assertThat(Money.usd(BigDecimal.ONE)).isNotEqualTo(Money.of(BigDecimal.ONE, "EUR"));
        }

        @Test
        @DisplayName("toString renders currency and plain amount")
        void toStringRenders() {
            assertThat(Money.usd(new BigDecimal("1.50")).toString()).isEqualTo("USD 1.50");
        }
    }
}
