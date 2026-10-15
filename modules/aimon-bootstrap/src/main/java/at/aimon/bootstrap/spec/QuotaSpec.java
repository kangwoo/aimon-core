package at.aimon.bootstrap.spec;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.quota.QuotaGuardFactory;
import at.aimon.core.agent.quota.QuotaPolicy;
import at.aimon.core.agent.quota.QuotaScopeResolver;
import at.aimon.core.agent.quota.UsageLedger;
import at.aimon.core.agent.quota.impl.DefaultQuotaScopeResolver;
import at.aimon.core.agent.quota.impl.StaticQuotaPolicy;
import at.aimon.core.base.ExternallyManaged;

/**
 * Declares the stack's accumulated usage quotas: which ledger counts, which limits apply, and whose usage it is.
 *
 * <p>
 * <b>Off unless a ledger is set.</b> A spec without one — or no spec at all — leaves the executor with
 * {@link QuotaGuardFactory#disabled()}, and every turn and fork runs exactly as it would on a stack that has never
 * heard of quotas.
 *
 * <pre>
 * {
 *     &#64;code
 *     QuotaSpec quota = QuotaSpec.builder().ledger(new InMemoryUsageLedger())
 *             .policy(StaticQuotaPolicy.builder()
 *                     .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(2_000_000).build())
 *                     .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.daily().maxCost(Money.usd(20)).build()).build())
 *             .resolver(DefaultQuotaScopeResolver.withTenant(ctx -> ctx.getAgentRuntimeId().discriminator()))
 *             .zone(ZoneId.of("Asia/Seoul")).build();
 * }
 * </pre>
 *
 * <h2>Lifetime</h2>
 *
 * <p>
 * The ledger is application-scoped and <b>borrowed</b>: every runtime of the stack charges the same one, and the stack
 * does not close it — a ledger that holds a connection belongs to whoever opened that connection.
 *
 * <h2>Cost limits</h2>
 *
 * <p>
 * A cost limit is only as good as the executor's cost estimator. With {@code CostEstimator.NOOP} every call is priced
 * at zero and a cost limit never fires; the stack reports that as its {@code quota-cost} degradation when it can see
 * the limit (a {@link StaticQuotaPolicy}).
 */
public final class QuotaSpec {

    @ExternallyManaged
    private final UsageLedger ledger;
    private final QuotaPolicy policy;
    private final QuotaScopeResolver resolver;
    private final Clock clock;
    private final ZoneId zone;
    private final boolean failClosed;

    private QuotaSpec(Builder builder) {
        this.ledger = builder.ledger;
        this.policy = builder.policy != null ? builder.policy : StaticQuotaPolicy.builder().build();
        this.resolver = builder.resolver != null ? builder.resolver : DefaultQuotaScopeResolver.create();
        this.clock = builder.clock;
        this.zone = builder.zone;
        this.failClosed = builder.failClosed;
    }

    /**
     * Quotas off — the default.
     *
     * @return a spec with no ledger (never null)
     */
    public static QuotaSpec disabled() {
        return builder().build();
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
     * Returns the ledger.
     *
     * @return the ledger, or empty when quotas are off
     */
    public Optional<UsageLedger> getLedger() {
        return Optional.ofNullable(ledger);
    }

    /**
     * Returns the policy.
     *
     * @return the policy; a {@link StaticQuotaPolicy} with no limits when none was set (never null)
     */
    public QuotaPolicy getPolicy() {
        return policy;
    }

    /**
     * Returns the resolver.
     *
     * @return the resolver; {@link DefaultQuotaScopeResolver#create()} when none was set (never null)
     */
    public QuotaScopeResolver getResolver() {
        return resolver;
    }

    /**
     * Returns the zone whose midnight separates daily buckets.
     *
     * @return the zone; UTC when none was set (never null)
     */
    public ZoneId getZone() {
        return zone;
    }

    /**
     * Returns whether a ledger failure stops the execution.
     *
     * @return {@code true} for fail-closed; {@code false} (the default) for fail-open
     */
    public boolean isFailClosed() {
        return failClosed;
    }

    /**
     * Builds the executor's guard factory from this spec.
     *
     * @return the factory; {@link QuotaGuardFactory#disabled()} when no ledger is set (never null)
     */
    public QuotaGuardFactory toGuardFactory() {
        if (ledger == null) {
            return QuotaGuardFactory.disabled();
        }
        return QuotaGuardFactory.builder().ledger(ledger).policy(policy).resolver(resolver).clock(clock).zone(zone)
                .failClosed(failClosed).build();
    }

    @Override
    public String toString() {
        return "QuotaSpec{ledger=" + ledger + ", policy=" + policy + ", resolver=" + resolver + ", zone=" + zone
                + ", failClosed=" + failClosed + '}';
    }

    /** Builder for {@link QuotaSpec}. */
    public static final class Builder {
        private UsageLedger ledger;
        private QuotaPolicy policy;
        private QuotaScopeResolver resolver;
        private Clock clock = Clock.systemUTC();
        private ZoneId zone = ZoneOffset.UTC;
        private boolean failClosed;

        private Builder() {
        }

        /**
         * Sets the ledger. Leaving it unset keeps quotas off.
         *
         * @param ledger
         *            the ledger, or {@code null} for quotas off; borrowed, never closed by the stack
         * @return this builder
         */
        public Builder ledger(@ExternallyManaged UsageLedger ledger) {
            this.ledger = ledger;
            return this;
        }

        /**
         * Sets the policy.
         *
         * @param policy
         *            the policy, or {@code null} for no limits
         * @return this builder
         */
        public Builder policy(QuotaPolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * Sets the resolver.
         *
         * @param resolver
         *            the resolver, or {@code null} for {@link DefaultQuotaScopeResolver#create()} (session and
         *            principal, no tenant)
         * @return this builder
         */
        public Builder resolver(QuotaScopeResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        /**
         * Sets the clock daily buckets are computed from. Defaults to {@link Clock#systemUTC()}; a test injects a
         * fixed one.
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
         * Sets the zone whose midnight separates daily buckets. Defaults to UTC.
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
         * Sets whether a ledger failure ends the execution with {@code ERROR}. Defaults to {@code false}: a ledger
         * failure is logged and the execution continues unmetered.
         *
         * @param failClosed
         *            {@code true} for fail-closed
         * @return this builder
         */
        public Builder failClosed(boolean failClosed) {
            this.failClosed = failClosed;
            return this;
        }

        /**
         * Builds the spec.
         *
         * @return a new spec (never null)
         */
        public QuotaSpec build() {
            return new QuotaSpec(this);
        }
    }
}
