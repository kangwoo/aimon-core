package at.aimon.core.llm.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary amount tagged with an ISO-4217 currency code.
 *
 * <p>
 * Backed by {@link BigDecimal} so cost arithmetic (price-per-token multiplications and cross-iteration sums) stays
 * exact
 * — no binary floating-point drift. All arithmetic operations require both operands to share the same
 * {@link #getCurrency() currency}; mixing currencies throws an {@link IllegalArgumentException} rather than silently
 * producing a nonsensical amount.
 *
 * <p>
 * Amounts are never negative: the constructor rejects negative values. Division uses a fixed
 * {@link #COST_SCALE sub-cent scale} with {@link RoundingMode#HALF_UP} so token-price divisions always terminate.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     Money price = Money.usd(3.00); // USD 3.00 per million tokens
 *     Money cost = price.multiply(BigDecimal.valueOf(1500)).divide(Money.MILLION); // cost of 1500 tokens
 *     Money total = cost.add(Money.usd(0.02));
 * }
 * </pre>
 */
public final class Money implements Comparable<Money> {

    /** ISO-4217 code for US dollars — the framework default currency. */
    public static final String USD = "USD";

    /** Convenience divisor for per-million-token pricing. */
    public static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    /**
     * Scale (decimal places) retained by {@link #divide(BigDecimal)} — sub-cent precision for fractional token cost.
     */
    public static final int COST_SCALE = 10;

    private final BigDecimal amount;
    private final String currency;

    private Money(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount cannot be null");
        this.currency = Objects.requireNonNull(currency, "currency cannot be null");
        if (currency.isEmpty()) {
            throw new IllegalArgumentException("currency cannot be empty");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money amount cannot be negative: " + amount + " " + currency);
        }
        this.amount = amount;
    }

    /**
     * Creates a money amount in the given currency.
     *
     * @param amount
     *            the non-negative amount (must not be null)
     * @param currency
     *            the ISO-4217 currency code (must not be null or empty)
     * @return a new {@link Money}
     * @throws IllegalArgumentException
     *             if {@code amount} is negative or {@code currency} is empty
     */
    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    /**
     * Creates a US-dollar amount.
     *
     * @param amount
     *            the non-negative amount (must not be null)
     * @return a new USD {@link Money}
     */
    public static Money usd(BigDecimal amount) {
        return new Money(amount, USD);
    }

    /**
     * Creates a US-dollar amount from a {@code double}. Convenience for literals such as list prices; the value is
     * converted via {@link BigDecimal#valueOf(double)} to avoid binary-expansion artefacts.
     *
     * @param amount
     *            the non-negative amount
     * @return a new USD {@link Money}
     */
    public static Money usd(double amount) {
        return new Money(BigDecimal.valueOf(amount), USD);
    }

    /**
     * @param currency
     *            the ISO-4217 currency code (must not be null or empty)
     * @return a zero amount in the given currency
     */
    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * @return a zero US-dollar amount — the neutral element used by {@link CostEstimator#NOOP} and empty
     *         {@link CostSummary} accumulators
     */
    public static Money zeroUsd() {
        return new Money(BigDecimal.ZERO, USD);
    }

    /**
     * Adds another amount of the same currency.
     *
     * @param other
     *            the addend (must not be null, must share this currency)
     * @return a new {@link Money} holding the sum
     * @throws IllegalArgumentException
     *             if the currencies differ
     */
    public Money add(Money other) {
        Objects.requireNonNull(other, "other cannot be null");
        requireSameCurrency(other.currency);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * Multiplies this amount by a scalar factor (e.g., a token count).
     *
     * @param factor
     *            the non-negative factor (must not be null)
     * @return a new {@link Money} holding the product in this currency
     * @throws IllegalArgumentException
     *             if the resulting amount would be negative
     */
    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor cannot be null");
        return new Money(amount.multiply(factor), currency);
    }

    /**
     * Divides this amount by a scalar divisor, rounding to {@link #COST_SCALE} decimal places with
     * {@link RoundingMode#HALF_UP}.
     *
     * @param divisor
     *            the positive divisor (must not be null or zero)
     * @return a new {@link Money} holding the quotient in this currency
     * @throws IllegalArgumentException
     *             if {@code divisor} is zero
     */
    public Money divide(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "divisor cannot be null");
        if (divisor.signum() == 0) {
            throw new IllegalArgumentException("divisor cannot be zero");
        }
        return new Money(amount.divide(divisor, COST_SCALE, RoundingMode.HALF_UP), currency);
    }

    /**
     * @return the raw amount (never null, never negative)
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * @return the ISO-4217 currency code (never null or empty)
     */
    public String getCurrency() {
        return currency;
    }

    /**
     * @return true if this amount is exactly zero
     */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    /**
     * Compares by amount; both operands must share a currency.
     *
     * @param other
     *            the amount to compare against (must not be null, must share this currency)
     * @return the signed comparison of the two amounts
     * @throws IllegalArgumentException
     *             if the currencies differ
     */
    @Override
    public int compareTo(Money other) {
        Objects.requireNonNull(other, "other cannot be null");
        requireSameCurrency(other.currency);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(String otherCurrency) {
        if (!currency.equals(otherCurrency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " vs " + otherCurrency + " — cannot combine amounts");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Money money = (Money) o;
        // compareTo semantics so 1.0 and 1.00 are equal — scale must not affect monetary identity.
        return currency.equals(money.currency) && amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        // stripTrailingZeros yields a canonical form for numerically-equal amounts, keeping hashCode consistent with
        // the compareTo-based equals above (1.0 and 1.00 hash identically).
        return Objects.hash(currency, amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return currency + " " + amount.toPlainString();
    }
}
