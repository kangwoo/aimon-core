package at.aimon.core.hook.rewake;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Application-scoped cap on concurrent rewake registrations per
 * {@link AgentRuntimeId agent runtime}.
 *
 * <p>
 * The quota manager exists to prevent runaway scheduling — a misbehaving hook that returns {@code asyncRewake} on every
 * fire, or a tenant that emits unbounded {@code asyncRewake} specs from a config reload, can otherwise saturate the
 * scheduler. The {@link RewakeService} consults the manager at {@link RewakeService#schedule(RewakeEnvelope) schedule}
 * time and refuses to register an envelope when the cap is hit.
 *
 * <p>
 * Lifetime semantics (see {@code .claude/rules/scheduling.md}):
 * <ul>
 * <li>{@link #tryAcquire(AgentRuntimeId)} — call before adding a new envelope to {@code pending}. Returns
 * {@code true} when a slot was reserved (usage is incremented).
 * <li>{@link #release(AgentRuntimeId)} — call when the envelope leaves {@code pending} (cancelled, fired,
 * resolved, or service closed). Decrements usage; never goes below zero.
 * </ul>
 *
 * <p>
 * Implementations must be thread-safe; concurrent {@code tryAcquire / release} calls for the same context id must not
 * miscount.
 *
 * <p>
 * The interface intentionally mirrors the shape of {@code at.aimon.core.scheduling.quota.TaskQuotaManager} but is keyed
 * by {@link AgentRuntimeId} rather than {@code Principal} — the rewake pipeline never has a principal in
 * hand at fire time, only the agent context id captured on the envelope.
 */
public interface RewakeQuotaManager {

    /**
     * No-op manager that always grants. Use as the default when no quota policy is configured.
     */
    RewakeQuotaManager NOOP = new RewakeQuotaManager() {
        @Override
        public boolean tryAcquire(AgentRuntimeId agentRuntimeId) {
            return true;
        }

        @Override
        public void release(AgentRuntimeId agentRuntimeId) {
            // No-op.
        }

        @Override
        public int getCurrentUsage(AgentRuntimeId agentRuntimeId) {
            return 0;
        }

        @Override
        public int getMaxQuota(AgentRuntimeId agentRuntimeId) {
            return Integer.MAX_VALUE;
        }
    };

    /**
     * Reserves one slot for {@code agentRuntimeId}.
     *
     * @param agentRuntimeId
     *            the agent runtime that owns the new envelope (must not be null)
     * @return {@code true} when a slot was reserved (usage incremented), {@code false} when the cap is hit (usage
     *         unchanged)
     * @throws NullPointerException
     *             if {@code agentRuntimeId} is null
     */
    boolean tryAcquire(AgentRuntimeId agentRuntimeId);

    /**
     * Releases one previously-acquired slot. Safe to call when usage is already zero (no-op).
     *
     * @param agentRuntimeId
     *            the agent runtime whose envelope just left the pending set (must not be null)
     * @throws NullPointerException
     *             if {@code agentRuntimeId} is null
     */
    void release(AgentRuntimeId agentRuntimeId);

    /**
     * Returns the live usage count for {@code agentRuntimeId}.
     *
     * @param agentRuntimeId
     *            the agent runtime (must not be null)
     * @return current usage (always &gt;= 0)
     */
    int getCurrentUsage(AgentRuntimeId agentRuntimeId);

    /**
     * Returns the cap for {@code agentRuntimeId}.
     *
     * @param agentRuntimeId
     *            the agent runtime (must not be null)
     * @return cap (always &gt;= 1)
     */
    int getMaxQuota(AgentRuntimeId agentRuntimeId);
}
