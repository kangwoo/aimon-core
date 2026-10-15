package at.aimon.core.agent.quota;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.quota.exception.QuotaLedgerUnavailableException;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.Money;

/**
 * The quota check and charge one execution tree runs against: the scopes its turn resolved, the limits on each, and
 * the ledger holding the counters.
 *
 * <p>
 * <b>One per turn, shared by its forks.</b> The turn's executor builds it; every fork the turn starts — a {@code Task}
 * subagent, a workflow step, a forked skill, a fork inside a fork, a background fork that outlives the turn — receives
 * the same instance and charges the same keys. A fork never builds its own: it has no session, and a guard resolved
 * for it would lose the session scope, which is the bypass this type exists to close.
 *
 * <p>
 * The scopes and their limits are fixed when the guard is built. The window bucket is not: {@link #check()} and
 * {@link #charge} compute it from the injected {@link Clock} and {@link ZoneId} every time, so an execution that runs
 * past midnight charges the new day.
 *
 * <p>
 * <b>A soft limit.</b> The loop checks before an LLM call and charges after it, so a key can overshoot its limit by
 * the calls in flight on it when it was reached. The guard holds no counters — those live in the ledger — so sharing it
 * across threads is safe as long as the ledger is.
 *
 * <p>
 * <b>Ledger failure.</b> By default the guard <em>fails open</em>: a ledger exception is logged at WARN and the check
 * finds no violation, the charge is dropped. With {@link Builder#failClosed(boolean) failClosed(true)} both throw
 * {@link QuotaLedgerUnavailableException} instead, and the execution ends with
 * {@link at.aimon.core.agent.budget.CompletionReason#ERROR}.
 *
 * <p>
 * Immutable. Use {@link #builder()} or {@link #disabled()}.
 */
public final class QuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(QuotaGuard.class);

    private static final QuotaGuard DISABLED = new QuotaGuard();

    private final UsageLedger ledger;
    private final List<Binding> bindings;
    private final Clock clock;
    private final ZoneId zone;
    private final boolean failClosed;

    private QuotaGuard() {
        this.ledger = null;
        this.bindings = List.of();
        this.clock = Clock.systemUTC();
        this.zone = ZoneOffset.UTC;
        this.failClosed = false;
    }

    private QuotaGuard(Builder builder) {
        this.ledger = Objects.requireNonNull(builder.ledger, "Ledger cannot be null");
        final QuotaPolicy policy = Objects.requireNonNull(builder.policy, "Policy cannot be null");
        this.clock = builder.clock;
        this.zone = builder.zone;
        this.failClosed = builder.failClosed;
        final List<Binding> resolved = new ArrayList<>();
        for (QuotaScope scope : builder.scopes) {
            final List<QuotaLimit> limits = Objects.requireNonNull(policy.limitsFor(scope),
                    "Policy returned null limits for " + scope);
            for (QuotaLimit limit : limits) {
                resolved.add(new Binding(scope, Objects.requireNonNull(limit, "Policy returned a null limit")));
            }
        }
        this.bindings = List.copyOf(resolved);
    }

    /**
     * Gets the guard that checks and charges nothing — the guard of an execution no ledger was wired for.
     *
     * @return the disabled guard (never null)
     */
    public static QuotaGuard disabled() {
        return DISABLED;
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Tells whether this guard meters anything — whether any scope it was built with has a limit.
     *
     * @return {@code false} for {@link #disabled()} and for a guard whose scopes the policy left unlimited
     */
    public boolean isEnabled() {
        return !bindings.isEmpty();
    }

    /**
     * Checks every key against its limit.
     *
     * @return the first violation found, or empty when every key is below its limit (or the ledger failed and the
     *         guard fails open)
     * @throws QuotaLedgerUnavailableException
     *             if the ledger failed and the guard fails closed
     */
    public Optional<QuotaViolation> check() {
        if (bindings.isEmpty()) {
            return Optional.empty();
        }
        final Instant now = clock.instant();
        final Map<QuotaKey, QuotaUsage> usage;
        try {
            usage = ledger.get(keysAt(now));
        } catch (RuntimeException e) {
            onLedgerFailure("check", e);
            return Optional.empty();
        }
        for (Binding binding : bindings) {
            final QuotaKey key = binding.keyAt(now, zone);
            final QuotaUsage used = usage.getOrDefault(key, QuotaUsage.zero());
            if (binding.limit.isReachedBy(used)) {
                return Optional.of(QuotaViolation.of(key, binding.limit, used));
            }
        }
        return Optional.empty();
    }

    /**
     * Records one LLM call's usage on every key.
     *
     * @param modelName
     *            the model the call went to, for the debug log (may be null)
     * @param usage
     *            the call's token usage (must not be null); its total tokens are charged
     * @param cost
     *            the call's estimated cost (may be null; a null or non-USD cost is charged as zero, as the cost budget
     *            axis treats it)
     * @throws QuotaLedgerUnavailableException
     *             if the ledger failed and the guard fails closed
     */
    public void charge(String modelName, TokenUsage usage, Money cost) {
        Objects.requireNonNull(usage, "Usage cannot be null");
        if (bindings.isEmpty()) {
            return;
        }
        final Money usd = cost != null && Money.USD.equals(cost.getCurrency()) ? cost : Money.zeroUsd();
        final QuotaUsage delta = QuotaUsage.of(usage.getTotalTokens(), usd);
        if (delta.getTokens() == 0 && usd.isZero()) {
            return;
        }
        final Set<QuotaKey> keys = keysAt(clock.instant());
        try {
            ledger.charge(keys, delta);
        } catch (RuntimeException e) {
            onLedgerFailure("charge", e);
            return;
        }
        log.debug("Quota charged: model={}, usage={}, keys={}", modelName, delta, keys);
    }

    private Set<QuotaKey> keysAt(Instant now) {
        // Two limits of one window on one scope share a key; the ledger is charged once for it.
        final Set<QuotaKey> keys = new LinkedHashSet<>();
        for (Binding binding : bindings) {
            keys.add(binding.keyAt(now, zone));
        }
        return keys;
    }

    private void onLedgerFailure(String operation, RuntimeException e) {
        if (failClosed) {
            throw new QuotaLedgerUnavailableException("Usage ledger failed during quota " + operation, e);
        }
        log.warn("Usage ledger failed during quota {}; failing open: {}", operation, e.toString());
    }

    @Override
    public String toString() {
        return "QuotaGuard{bindings=" + bindings + ", zone=" + zone + ", failClosed=" + failClosed + '}';
    }

    /** One limit on one scope. */
    private static final class Binding {
        private final QuotaScope scope;
        private final QuotaLimit limit;

        private Binding(QuotaScope scope, QuotaLimit limit) {
            this.scope = scope;
            this.limit = limit;
        }

        private QuotaKey keyAt(Instant now, ZoneId zone) {
            return QuotaKey.of(scope, limit.getWindow(), limit.getWindow().bucketAt(now, zone));
        }

        @Override
        public String toString() {
            return scope + "=" + limit;
        }
    }

    /** Builder for {@link QuotaGuard}. */
    public static final class Builder {
        private UsageLedger ledger;
        private QuotaPolicy policy;
        private List<QuotaScope> scopes = List.of();
        private Clock clock = Clock.systemUTC();
        private ZoneId zone = ZoneOffset.UTC;
        private boolean failClosed;

        private Builder() {
        }

        /**
         * Sets the ledger the counters live in.
         *
         * @param ledger
         *            the ledger (required)
         * @return this builder
         */
        public Builder ledger(UsageLedger ledger) {
            this.ledger = ledger;
            return this;
        }

        /**
         * Sets the policy the limits come from. Asked once per scope, in {@link #build()}.
         *
         * @param policy
         *            the policy (required)
         * @return this builder
         */
        public Builder policy(QuotaPolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * Sets the scopes the execution is charged to.
         *
         * @param scopes
         *            the scopes (must not be null; may be empty)
         * @return this builder
         */
        public Builder scopes(List<QuotaScope> scopes) {
            this.scopes = List.copyOf(Objects.requireNonNull(scopes, "Scopes cannot be null"));
            return this;
        }

        /**
         * Sets the clock the window buckets are computed from. Defaults to {@link Clock#systemUTC()}.
         *
         * @param clock
         *            the clock (must not be null)
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
            return this;
        }

        /**
         * Sets the zone whose midnight separates {@link QuotaWindow#DAILY} buckets. Defaults to UTC, so that nodes in
         * different zones agree on the bucket.
         *
         * @param zone
         *            the zone (must not be null)
         * @return this builder
         */
        public Builder zone(ZoneId zone) {
            this.zone = Objects.requireNonNull(zone, "Zone cannot be null");
            return this;
        }

        /**
         * Sets whether a ledger failure stops the execution. Defaults to {@code false} (fail open).
         *
         * @param failClosed
         *            {@code true} to throw {@link QuotaLedgerUnavailableException} on a ledger failure
         * @return this builder
         */
        public Builder failClosed(boolean failClosed) {
            this.failClosed = failClosed;
            return this;
        }

        /**
         * Builds the guard, asking the policy for each scope's limits.
         *
         * @return a new guard (never null)
         * @throws NullPointerException
         *             if the ledger or the policy was not set, or the policy returned null
         */
        public QuotaGuard build() {
            return new QuotaGuard(this);
        }
    }
}
