package at.aimon.bootstrap.spec;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import at.aimon.bootstrap.runtime.AgentRuntimeEviction;

/**
 * Declares how the stack treats agent runtimes it creates after startup.
 *
 * <p>
 * This is only about the second axis of a runtime id. The agents named in configuration are finite, enumerated
 * and stood up at startup, and nothing here applies to them. A discriminator — a tenant, a customer, a business
 * unit — is unbounded and unknown in advance, so those runtimes are created on first use and have to be given
 * back afterwards. The three settings below are the terms on which that happens.
 *
 * <h2>Why a cap and a TTL rather than either alone</h2>
 *
 * <p>
 * A TTL alone does not bound anything: a thousand tenants active within the same window is a thousand live
 * runtimes, each with a file system, a tool registry and possibly an MCP connection. A cap alone never releases:
 * once a thousand tenants have been seen the stack is full of runtimes nobody has touched since Tuesday. Together
 * the TTL decides what is reclaimable and the cap decides when reclaiming is not enough and the honest answer is
 * to refuse.
 *
 * <p>
 * Neither ever closes a runtime that is in use — see {@code AgentRuntimeLease}.
 */
public final class AgentRuntimeSpec {

    /** Idle time after which an unused tenant runtime is closed, when the caller sets none. */
    public static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(30);

    /** Number of tenant runtimes held at once, when the caller sets none. */
    public static final int DEFAULT_MAX_ENTRIES = 500;

    private final AgentRuntimeEviction eviction;
    private final Duration idleTtl;
    private final Duration sweepInterval;
    private final int maxEntries;

    private AgentRuntimeSpec(Builder builder) {
        this.eviction = Objects.requireNonNullElse(builder.eviction, AgentRuntimeEviction.IDLE);
        this.idleTtl = Objects.requireNonNullElse(builder.idleTtl, DEFAULT_IDLE_TTL);
        this.sweepInterval = builder.sweepInterval;
        this.maxEntries = builder.maxEntries;
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1, got: " + maxEntries);
        }
        if (idleTtl.isNegative()) {
            throw new IllegalArgumentException("idleTtl must not be negative, got: " + idleTtl);
        }
    }

    /**
     * The defaults: reclaim tenant runtimes after 30 minutes idle, hold at most 500 at once.
     *
     * @return the spec
     */
    public static AgentRuntimeSpec defaults() {
        return builder().build();
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether idle tenant runtimes are reclaimed.
     *
     * @return the eviction policy, never null
     */
    public AgentRuntimeEviction getEviction() {
        return eviction;
    }

    /**
     * Returns how long a tenant runtime may sit unused before it is closed.
     *
     * @return the idle TTL, never null
     */
    public Duration getIdleTtl() {
        return idleTtl;
    }

    /**
     * Returns how often the background sweep runs, when the caller set it explicitly.
     *
     * @return the interval, or empty to sweep once per idle TTL
     */
    public Optional<Duration> getSweepInterval() {
        return Optional.ofNullable(sweepInterval);
    }

    /**
     * Returns the cap on tenant runtimes held at once.
     *
     * @return the maximum
     */
    public int getMaxEntries() {
        return maxEntries;
    }

    @Override
    public String toString() {
        return "AgentRuntimeSpec[eviction=" + eviction + ", idleTtl=" + idleTtl + ", maxEntries=" + maxEntries + "]";
    }

    /** Builder for {@link AgentRuntimeSpec}. */
    public static final class Builder {

        private AgentRuntimeEviction eviction;
        private Duration idleTtl;
        private Duration sweepInterval;
        private int maxEntries = DEFAULT_MAX_ENTRIES;

        private Builder() {
        }

        /**
         * Sets whether idle tenant runtimes are reclaimed. Defaults to {@link AgentRuntimeEviction#IDLE}.
         *
         * @param eviction
         *            the policy (must not be null)
         * @return this builder
         */
        public Builder eviction(AgentRuntimeEviction eviction) {
            this.eviction = Objects.requireNonNull(eviction, "eviction must not be null");
            return this;
        }

        /**
         * Sets how long a tenant runtime may sit with no active work before it is closed. Defaults to
         * {@link #DEFAULT_IDLE_TTL}.
         *
         * <p>
         * The clock starts when the last piece of work finishes, not at creation, so a runtime in continuous use
         * is never reclaimed however old it is.
         *
         * @param idleTtl
         *            the TTL (must not be null or negative)
         * @return this builder
         */
        public Builder idleTtl(Duration idleTtl) {
            this.idleTtl = Objects.requireNonNull(idleTtl, "idleTtl must not be null");
            return this;
        }

        /**
         * Sets how often idle runtimes are looked for. Defaults to the idle TTL.
         *
         * <p>
         * Worth shortening only when the TTL is long and reclaiming promptly matters; the sweep also runs before
         * each new tenant is admitted, so a busy stack reclaims without waiting for it either way.
         *
         * @param sweepInterval
         *            the interval (must not be null)
         * @return this builder
         */
        public Builder sweepInterval(Duration sweepInterval) {
            this.sweepInterval = Objects.requireNonNull(sweepInterval, "sweepInterval must not be null");
            return this;
        }

        /**
         * Sets how many tenant runtimes may be held at once. Defaults to {@link #DEFAULT_MAX_ENTRIES}.
         *
         * <p>
         * Reaching it is refused rather than exceeded, so this is a real limit on concurrent tenants and not a
         * hint. Size it by what one process can hold — a runtime is a file system, a tool registry, and whatever
         * MCP connections its agent declares.
         *
         * @param maxEntries
         *            the cap (must be at least 1)
         * @return this builder
         */
        public Builder maxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
            return this;
        }

        /**
         * Builds the spec.
         *
         * @return an immutable spec
         */
        public AgentRuntimeSpec build() {
            return new AgentRuntimeSpec(this);
        }
    }
}
