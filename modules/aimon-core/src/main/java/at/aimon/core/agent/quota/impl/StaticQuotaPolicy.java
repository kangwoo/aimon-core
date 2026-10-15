package at.aimon.core.agent.quota.impl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.quota.QuotaLimit;
import at.aimon.core.agent.quota.QuotaPolicy;
import at.aimon.core.agent.quota.QuotaScope;

/**
 * A {@link QuotaPolicy} that holds every scope of a kind to the same limits: every session to the session limits,
 * every principal to the principal limits, every tenant to the tenant limits.
 *
 * <p>
 * Immutable. Use builder to create instances:
 *
 * <pre>
 * {
 *     &#64;code
 *     QuotaPolicy policy = StaticQuotaPolicy.builder()
 *             .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(2_000_000).build())
 *             .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.daily().maxCost(Money.usd(20)).build()).build();
 * }
 * </pre>
 */
public final class StaticQuotaPolicy implements QuotaPolicy {

    private final Map<QuotaScope.Kind, List<QuotaLimit>> limits;

    private StaticQuotaPolicy(Builder builder) {
        final Map<QuotaScope.Kind, List<QuotaLimit>> copy = new EnumMap<>(QuotaScope.Kind.class);
        builder.limits.forEach((kind, list) -> copy.put(kind, List.copyOf(list)));
        this.limits = copy;
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<QuotaLimit> limitsFor(QuotaScope scope) {
        Objects.requireNonNull(scope, "Scope cannot be null");
        return limits.getOrDefault(scope.getKind(), List.of());
    }

    /**
     * Tells whether any limit of this policy has a cost ceiling — one that can only fire if calls are priced.
     *
     * @return {@code true} when some limit sets {@link QuotaLimit#getMaxCost()}
     */
    public boolean hasCostLimit() {
        return limits.values().stream().flatMap(List::stream).anyMatch(limit -> limit.getMaxCost().isPresent());
    }

    @Override
    public String toString() {
        return "StaticQuotaPolicy" + limits;
    }

    /** Builder for {@link StaticQuotaPolicy}. */
    public static final class Builder {
        private final Map<QuotaScope.Kind, List<QuotaLimit>> limits = new EnumMap<>(QuotaScope.Kind.class);

        private Builder() {
        }

        /**
         * Adds a limit every scope of {@code kind} is held to. A kind may have one limit per window; a second limit
         * of the same window is kept too, and whichever is reached first stops the execution.
         *
         * @param kind
         *            the kind of scope (must not be null)
         * @param limit
         *            the limit (must not be null)
         * @return this builder
         */
        public Builder limit(QuotaScope.Kind kind, QuotaLimit limit) {
            Objects.requireNonNull(kind, "Kind cannot be null");
            Objects.requireNonNull(limit, "Limit cannot be null");
            limits.computeIfAbsent(kind, k -> new ArrayList<>()).add(limit);
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return a new policy (never null)
         */
        public StaticQuotaPolicy build() {
            return new StaticQuotaPolicy(this);
        }
    }
}
