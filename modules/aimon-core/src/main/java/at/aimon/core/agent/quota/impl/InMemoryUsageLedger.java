package at.aimon.core.agent.quota.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.agent.quota.QuotaKey;
import at.aimon.core.agent.quota.QuotaUsage;
import at.aimon.core.agent.quota.QuotaWindow;
import at.aimon.core.agent.quota.UsageLedger;
import at.aimon.core.llm.cost.Money;

/**
 * The node-local {@link UsageLedger}: counters in a {@link ConcurrentHashMap}, incremented with {@code merge}.
 *
 * <p>
 * Shares nothing across nodes. On one node it is exact per key; on several, each node meters only its own executions,
 * so a principal or tenant limit holds per node rather than in total. A shared ledger replaces this one for that.
 *
 * <p>
 * Cost is kept in whole micro-dollars, as the remote ledgers keep it — one rounding per charge, no floating-point
 * drift across millions of them.
 *
 * <p>
 * {@link QuotaWindow#DAILY} keys are dropped once their day is older than the retention period (seven days unless
 * configured), checked at most once per UTC day on a charge. {@link QuotaWindow#TOTAL} keys are never dropped.
 *
 * <p>
 * Application-scoped and thread-safe. Holds no resources, so there is nothing to close.
 */
public final class InMemoryUsageLedger implements UsageLedger {

    /** Retention of {@link QuotaWindow#DAILY} keys when none is configured. */
    public static final Period DEFAULT_DAILY_RETENTION = Period.ofDays(7);

    private static final BigDecimal MICROS_PER_DOLLAR = BigDecimal.valueOf(1_000_000L);
    private static final int MICRO_SCALE = 6;

    private final ConcurrentHashMap<QuotaKey, Counter> counters = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Period dailyRetention;
    private volatile LocalDate lastPurgeDay;

    /** Creates a ledger with the system UTC clock and {@link #DEFAULT_DAILY_RETENTION}. */
    public InMemoryUsageLedger() {
        this(Clock.systemUTC(), DEFAULT_DAILY_RETENTION);
    }

    /**
     * Creates a ledger with the given clock and retention.
     *
     * @param clock
     *            the clock the retention is measured against (must not be null)
     * @param dailyRetention
     *            how long a {@link QuotaWindow#DAILY} key is kept after its day (must not be null or negative)
     */
    public InMemoryUsageLedger(Clock clock, Period dailyRetention) {
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        this.dailyRetention = Objects.requireNonNull(dailyRetention, "Daily retention cannot be null");
        if (dailyRetention.isNegative()) {
            throw new IllegalArgumentException("Daily retention cannot be negative: " + dailyRetention);
        }
    }

    @Override
    public void charge(Collection<QuotaKey> keys, QuotaUsage usage) {
        Objects.requireNonNull(keys, "Keys cannot be null");
        Objects.requireNonNull(usage, "Usage cannot be null");
        final Counter delta = new Counter(usage.getTokens(), toMicros(usage.getCost()));
        for (QuotaKey key : keys) {
            counters.merge(Objects.requireNonNull(key, "Key cannot be null"), delta, Counter::plus);
        }
        purgeExpiredDailyKeys();
    }

    @Override
    public Map<QuotaKey, QuotaUsage> get(Collection<QuotaKey> keys) {
        Objects.requireNonNull(keys, "Keys cannot be null");
        final Map<QuotaKey, QuotaUsage> result = new LinkedHashMap<>();
        for (QuotaKey key : keys) {
            final Counter counter = counters.get(Objects.requireNonNull(key, "Key cannot be null"));
            result.put(key, counter == null ? QuotaUsage.zero() : counter.toUsage());
        }
        return result;
    }

    private void purgeExpiredDailyKeys() {
        final LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (today.equals(lastPurgeDay)) {
            return;
        }
        lastPurgeDay = today;
        final LocalDate oldestKept = today.minus(dailyRetention);
        counters.keySet().removeIf(key -> key.getWindow() == QuotaWindow.DAILY && isBefore(key, oldestKept));
    }

    private static boolean isBefore(QuotaKey key, LocalDate oldestKept) {
        try {
            return LocalDate.parse(key.getBucket()).isBefore(oldestKept);
        } catch (DateTimeParseException e) {
            // Not a bucket this package wrote; leave it alone rather than guess.
            return false;
        }
    }

    private static long toMicros(Money cost) {
        return cost.getAmount().multiply(MICROS_PER_DOLLAR).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    @Override
    public String toString() {
        return "InMemoryUsageLedger{keys=" + counters.size() + ", dailyRetention=" + dailyRetention + '}';
    }

    /** One key's counters: total tokens and whole micro-dollars. */
    private static final class Counter {
        private final long tokens;
        private final long micros;

        private Counter(long tokens, long micros) {
            this.tokens = tokens;
            this.micros = micros;
        }

        private Counter plus(Counter other) {
            return new Counter(Math.addExact(tokens, other.tokens), Math.addExact(micros, other.micros));
        }

        private QuotaUsage toUsage() {
            return QuotaUsage.of(tokens, Money.usd(BigDecimal.valueOf(micros, MICRO_SCALE)));
        }
    }
}
