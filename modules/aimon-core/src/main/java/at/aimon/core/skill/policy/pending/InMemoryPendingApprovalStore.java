package at.aimon.core.skill.policy.pending;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import at.aimon.core.skill.policy.SkillInvocationDecision;

/**
 * In-memory {@link PendingApprovalStore}.
 *
 * <p>
 * Default for single-instance deployments. Thread-safe.
 */
public final class InMemoryPendingApprovalStore implements PendingApprovalStore {

    // @formatter:off
    private final ConcurrentMap<PendingTurnId, ConcurrentMap<String, SkillInvocationDecision>> perTurn
            = new ConcurrentHashMap<>();
    // @formatter:on

    @Override
    public void record(PendingTurnId turnId, String skillName, SkillInvocationDecision decision) {
        Objects.requireNonNull(turnId, "turnId cannot be null");
        Objects.requireNonNull(skillName, "skillName cannot be null");
        Objects.requireNonNull(decision, "decision cannot be null");
        if (decision == SkillInvocationDecision.ASK) {
            throw new IllegalArgumentException("Cannot store ASK; only ALLOW or DENY may be cached");
        }
        perTurn.computeIfAbsent(turnId, id -> new ConcurrentHashMap<>()).put(skillName, decision);
    }

    @Override
    public Optional<SkillInvocationDecision> get(PendingTurnId turnId, String skillName) {
        Objects.requireNonNull(turnId, "turnId cannot be null");
        Objects.requireNonNull(skillName, "skillName cannot be null");
        final ConcurrentMap<String, SkillInvocationDecision> entries = perTurn.get(turnId);
        if (entries == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(skillName));
    }

    @Override
    public Map<String, SkillInvocationDecision> getAll(PendingTurnId turnId) {
        Objects.requireNonNull(turnId, "turnId cannot be null");
        final ConcurrentMap<String, SkillInvocationDecision> entries = perTurn.get(turnId);
        if (entries == null) {
            return Map.of();
        }
        return Map.copyOf(entries);
    }

    @Override
    public void clear(PendingTurnId turnId) {
        Objects.requireNonNull(turnId, "turnId cannot be null");
        perTurn.remove(turnId);
    }
}
