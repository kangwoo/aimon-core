package at.aimon.core.skill.policy.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.skill.policy.SkillInvocationDecision;

/**
 * In-memory {@link AgentApprovalStore} backed by a per-context map with LRU bounding on the number of tracked
 * contexts.
 *
 * <p>
 * Default for single-instance deployments — entries live in process memory only. The outer map is bounded so a
 * long-running service that cycles through many short-lived contexts does not accumulate state indefinitely; eldest
 * contexts are dropped in LRU order once {@link #DEFAULT_MAX_TRACKED_CONTEXTS} is exceeded. Eviction merely "forgets"
 * the cached approvals, which downgrades the experience to re-prompting the user — it does not weaken security since
 * the underlying policy is still consulted.
 *
 * <p>
 * Thread-safe.
 */
public final class InMemoryAgentApprovalStore implements AgentApprovalStore {

    public static final int DEFAULT_MAX_TRACKED_CONTEXTS = 1024;

    private final Map<AgentRuntimeId, Map<String, SkillInvocationDecision>> perContext;

    public InMemoryAgentApprovalStore() {
        this(DEFAULT_MAX_TRACKED_CONTEXTS);
    }

    /**
     * Creates a store with a custom upper bound on the number of tracked contexts.
     *
     * @param maxTrackedContexts
     *            maximum number of distinct contexts to retain in LRU order; must be {@code >= 1}
     * @throws IllegalArgumentException
     *             if {@code maxTrackedContexts < 1}
     */
    public InMemoryAgentApprovalStore(int maxTrackedContexts) {
        if (maxTrackedContexts < 1) {
            throw new IllegalArgumentException("maxTrackedContexts must be >= 1, got: " + maxTrackedContexts);
        }
        this.perContext = Collections.synchronizedMap(new BoundedLruMap<>(maxTrackedContexts));
    }

    @Override
    public Optional<SkillInvocationDecision> get(AgentRuntimeId agentRuntimeId, String skillName) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        Objects.requireNonNull(skillName, "skillName cannot be null");
        final Map<String, SkillInvocationDecision> entries;
        synchronized (perContext) {
            entries = perContext.get(agentRuntimeId);
        }
        if (entries == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(skillName));
    }

    @Override
    public void put(AgentRuntimeId agentRuntimeId, String skillName, SkillInvocationDecision decision) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        Objects.requireNonNull(skillName, "skillName cannot be null");
        Objects.requireNonNull(decision, "decision cannot be null");
        if (decision == SkillInvocationDecision.ASK) {
            throw new IllegalArgumentException("Cannot store ASK; only ALLOW or DENY may be cached");
        }
        final Map<String, SkillInvocationDecision> entries;
        synchronized (perContext) {
            entries = perContext.computeIfAbsent(agentRuntimeId, id -> new ConcurrentHashMap<>());
        }
        entries.put(skillName, decision);
    }

    @Override
    public void invalidate(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        perContext.remove(agentRuntimeId);
    }

    private static final class BoundedLruMap<K, V> extends LinkedHashMap<K, V> {

        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        BoundedLruMap(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }
}
