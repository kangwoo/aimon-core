package at.aimon.core.skill.policy.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.SkillInvocationDecision;

/**
 * In-memory {@link SessionApprovalStore} backed by a per-session map with LRU bounding on the number of
 * tracked sessions.
 *
 * <p>
 * Default for single-instance deployments — entries live in process memory only, so a session that moves to
 * another node re-prompts. The outer map is bounded so a long-running service that cycles through many sessions
 * does not accumulate state indefinitely; eldest sessions are dropped in LRU order once
 * {@link #DEFAULT_MAX_TRACKED_SESSIONS} is exceeded. Eviction merely "forgets" the cached approvals, which
 * downgrades the experience to re-prompting the user — it does not weaken security since the underlying policy is
 * still consulted.
 *
 * <p>
 * The bound is deliberately higher than the agent-scoped store's: sessions are far more numerous than agent
 * runtimes, and an under-sized bound here would show up as a user being re-asked mid-session.
 *
 * <p>
 * Thread-safe.
 */
public final class InMemorySessionApprovalStore implements SessionApprovalStore {

    public static final int DEFAULT_MAX_TRACKED_SESSIONS = 4096;

    private final Map<SessionId, Map<String, SkillInvocationDecision>> perSession;

    public InMemorySessionApprovalStore() {
        this(DEFAULT_MAX_TRACKED_SESSIONS);
    }

    /**
     * Creates a store with a custom upper bound on the number of tracked sessions.
     *
     * @param maxTrackedSessions
     *            maximum number of distinct sessions to retain in LRU order; must be {@code >= 1}
     * @throws IllegalArgumentException
     *             if {@code maxTrackedSessions < 1}
     */
    public InMemorySessionApprovalStore(int maxTrackedSessions) {
        if (maxTrackedSessions < 1) {
            throw new IllegalArgumentException("maxTrackedSessions must be >= 1, got: " + maxTrackedSessions);
        }
        this.perSession = Collections.synchronizedMap(new BoundedLruMap<>(maxTrackedSessions));
    }

    @Override
    public Optional<SkillInvocationDecision> get(SessionId sessionId, String skillName) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(skillName, "skillName cannot be null");
        final Map<String, SkillInvocationDecision> entries;
        synchronized (perSession) {
            entries = perSession.get(sessionId);
        }
        if (entries == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(skillName));
    }

    @Override
    public void put(SessionId sessionId, String skillName, SkillInvocationDecision decision) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(skillName, "skillName cannot be null");
        Objects.requireNonNull(decision, "decision cannot be null");
        if (decision == SkillInvocationDecision.ASK) {
            throw new IllegalArgumentException("Cannot store ASK; only ALLOW or DENY may be cached");
        }
        final Map<String, SkillInvocationDecision> entries;
        synchronized (perSession) {
            entries = perSession.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>());
        }
        entries.put(skillName, decision);
    }

    @Override
    public void invalidate(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        perSession.remove(sessionId);
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
