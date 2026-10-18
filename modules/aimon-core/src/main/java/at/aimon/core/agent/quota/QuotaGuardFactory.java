package at.aimon.core.agent.quota;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Builds each turn's, and each scheduled routine run's, {@link QuotaGuard} from the application's quota configuration:
 * the ledger, the policy, the resolver, the clock and zone, and the failure mode.
 *
 * <p>
 * <b>Application-scoped</b>, like the {@link UsageLedger} it holds: one factory is handed to the executor, which is
 * shared by every agent runtime, and the same one to the {@code RoutineExecutor}. The executor calls
 * {@link #create(QuotaResolutionContext)} once per turn, the routine executor once per run; a fork does not call it at
 * all, it inherits the guard of the execution that spawned it.
 *
 * <p>
 * Immutable. Use {@link #builder()} or {@link #disabled()}.
 */
public final class QuotaGuardFactory {

    private static final QuotaGuardFactory DISABLED = new QuotaGuardFactory();

    private final UsageLedger ledger;
    private final QuotaPolicy policy;
    private final QuotaScopeResolver resolver;
    private final Clock clock;
    private final ZoneId zone;
    private final boolean failClosed;

    private QuotaGuardFactory() {
        this.ledger = null;
        this.policy = null;
        this.resolver = null;
        this.clock = Clock.systemUTC();
        this.zone = ZoneOffset.UTC;
        this.failClosed = false;
    }

    private QuotaGuardFactory(Builder builder) {
        this.ledger = Objects.requireNonNull(builder.ledger, "Ledger cannot be null");
        this.policy = Objects.requireNonNull(builder.policy, "Policy cannot be null");
        this.resolver = Objects.requireNonNull(builder.resolver, "Resolver cannot be null");
        this.clock = builder.clock;
        this.zone = builder.zone;
        this.failClosed = builder.failClosed;
    }

    /**
     * Gets the factory that hands every turn {@link QuotaGuard#disabled()} — the executor's default, under which
     * nothing is metered and nothing is different from an executor that has never heard of quotas.
     *
     * @return the disabled factory (never null)
     */
    public static QuotaGuardFactory disabled() {
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
     * Tells whether this factory meters anything.
     *
     * @return {@code false} for {@link #disabled()}
     */
    public boolean isEnabled() {
        return ledger != null;
    }

    /**
     * Builds the guard of one turn: resolves its scopes, then fixes their limits.
     *
     * @param context
     *            what is known about the turn (must not be null)
     * @return the turn's guard (never null; {@link QuotaGuard#disabled()} when this factory is disabled)
     */
    public QuotaGuard create(QuotaResolutionContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        if (!isEnabled()) {
            return QuotaGuard.disabled();
        }
        return QuotaGuard.builder().ledger(ledger).policy(policy)
                .scopes(Objects.requireNonNull(resolver.resolve(context), "Resolver returned null scopes")).clock(clock)
                .zone(zone).failClosed(failClosed).build();
    }

    @Override
    public String toString() {
        return isEnabled()
                ? "QuotaGuardFactory{ledger=" + ledger + ", policy=" + policy + ", resolver=" + resolver + ", zone="
                        + zone + ", failClosed=" + failClosed + '}'
                : "QuotaGuardFactory{disabled}";
    }

    /** Builder for {@link QuotaGuardFactory}. */
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
         * Sets the ledger. Application-scoped: the factory borrows it and never closes it.
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
         * Sets the policy.
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
         * Sets the resolver.
         *
         * @param resolver
         *            the resolver (required)
         * @return this builder
         */
        public Builder resolver(QuotaScopeResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        /**
         * Sets the clock. Defaults to {@link Clock#systemUTC()}.
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
         * Sets the zone of {@link QuotaWindow#DAILY} buckets. Defaults to UTC.
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
         *            {@code true} to end the execution with {@code ERROR} on a ledger failure
         * @return this builder
         */
        public Builder failClosed(boolean failClosed) {
            this.failClosed = failClosed;
            return this;
        }

        /**
         * Builds the factory.
         *
         * @return a new factory (never null)
         * @throws NullPointerException
         *             if the ledger, the policy or the resolver was not set
         */
        public QuotaGuardFactory build() {
            return new QuotaGuardFactory(this);
        }
    }
}
