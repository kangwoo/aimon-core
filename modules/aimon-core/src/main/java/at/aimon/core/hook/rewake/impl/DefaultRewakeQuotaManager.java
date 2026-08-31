package at.aimon.core.hook.rewake.impl;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.hook.rewake.RewakeQuotaManager;

/**
 * In-memory {@link RewakeQuotaManager} keyed by {@link AgentRuntimeId}.
 *
 * <p>
 * Each context shares a single counter; {@link #tryAcquire(AgentRuntimeId)} compares it against
 * {@link #getMaxQuota(AgentRuntimeId) the cap} (default or per-context override) and atomically reserves
 * a slot when there is room. Counters are initialized lazily on first acquire.
 *
 * <p>
 * Persistence: <b>none</b>. Counters reset on JVM restart. Production deployments wanting clustered enforcement should
 * back this with the same store the {@link at.aimon.core.hook.rewake.RewakeService RewakeService} uses (out of scope
 * for the in-memory MVP).
 *
 * <p>
 * Thread-safety: every operation is concurrent-safe. The decrement floor is guarded so paired
 * {@code release} calls never push usage below zero, even under stale calls.
 */
public final class DefaultRewakeQuotaManager implements RewakeQuotaManager {

    /** Default cap when no per-context override is configured. */
    public static final int DEFAULT_MAX_QUOTA = 64;

    private final int defaultMaxQuota;
    private final Map<AgentRuntimeId, AtomicInteger> usage = new ConcurrentHashMap<>();
    private final Map<AgentRuntimeId, Integer> customQuotas = new ConcurrentHashMap<>();

    /**
     * Creates a manager with {@link #DEFAULT_MAX_QUOTA} as the per-context cap.
     */
    public DefaultRewakeQuotaManager() {
        this(DEFAULT_MAX_QUOTA);
    }

    /**
     * Creates a manager with a custom default cap.
     *
     * @param defaultMaxQuota
     *            cap applied to contexts with no custom quota (must be &gt;= 1)
     * @throws IllegalArgumentException
     *             if {@code defaultMaxQuota} is not strictly positive
     */
    public DefaultRewakeQuotaManager(int defaultMaxQuota) {
        if (defaultMaxQuota < 1) {
            throw new IllegalArgumentException("defaultMaxQuota must be >= 1, got: " + defaultMaxQuota);
        }
        this.defaultMaxQuota = defaultMaxQuota;
    }

    @Override
    public boolean tryAcquire(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        final int max = getMaxQuota(agentRuntimeId);
        final AtomicInteger counter = usage.computeIfAbsent(agentRuntimeId, k -> new AtomicInteger());
        while (true) {
            final int current = counter.get();
            if (current >= max) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    @Override
    public void release(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        final AtomicInteger counter = usage.get(agentRuntimeId);
        if (counter == null) {
            return;
        }
        counter.updateAndGet(v -> Math.max(0, v - 1));
    }

    @Override
    public int getCurrentUsage(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        final AtomicInteger counter = usage.get(agentRuntimeId);
        return counter == null ? 0 : counter.get();
    }

    @Override
    public int getMaxQuota(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        return customQuotas.getOrDefault(agentRuntimeId, defaultMaxQuota);
    }

    /**
     * Sets a per-context override.
     *
     * @param agentRuntimeId
     *            target context (must not be null)
     * @param maxQuota
     *            cap for this context (must be &gt;= 1)
     * @throws IllegalArgumentException
     *             if {@code maxQuota} is not strictly positive
     */
    public void setCustomQuota(AgentRuntimeId agentRuntimeId, int maxQuota) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        if (maxQuota < 1) {
            throw new IllegalArgumentException("maxQuota must be >= 1, got: " + maxQuota);
        }
        customQuotas.put(agentRuntimeId, maxQuota);
    }

    /**
     * Removes a per-context override, reverting to the default cap.
     *
     * @param agentRuntimeId
     *            target context (must not be null)
     */
    public void removeCustomQuota(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        customQuotas.remove(agentRuntimeId);
    }

    /** Resets every counter to zero. Mainly useful for tests. */
    public void resetAllUsage() {
        usage.clear();
    }
}
