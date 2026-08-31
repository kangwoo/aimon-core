package at.aimon.core.skill.policy.pending;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * In-memory {@link PendingTurnRegistry}.
 *
 * <p>
 * Default for single-instance deployments — pending turns live in process memory only. There is no upper bound on the
 * number of registered turns; the SK-11.5 timeout reaper is expected to keep the registry trimmed via
 * {@link #removeExpired(Instant)}, and any per-context listing already iterates lazily.
 *
 * <p>
 * Thread-safe.
 */
public final class InMemoryPendingTurnRegistry implements PendingTurnRegistry {

    private final ConcurrentMap<PendingTurnId, PendingTurn> turns = new ConcurrentHashMap<>();

    @Override
    public void register(PendingTurn turn) {
        Objects.requireNonNull(turn, "turn cannot be null");
        final PendingTurn existing = turns.putIfAbsent(turn.getId(), turn);
        if (existing != null) {
            throw new IllegalStateException("PendingTurn already registered: " + turn.getId());
        }
    }

    @Override
    public Optional<PendingTurn> get(PendingTurnId id) {
        Objects.requireNonNull(id, "id cannot be null");
        return Optional.ofNullable(turns.get(id));
    }

    @Override
    public List<PendingTurn> listByAgentRuntime(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        final List<PendingTurn> matches = new ArrayList<>();
        for (PendingTurn turn : turns.values()) {
            if (turn.getAgentRuntimeId().equals(agentRuntimeId)) {
                matches.add(turn);
            }
        }
        matches.sort(Comparator.comparing(PendingTurn::getCreatedAt));
        return List.copyOf(matches);
    }

    @Override
    public List<PendingTurn> listAll() {
        final List<PendingTurn> all = new ArrayList<>(turns.values());
        all.sort(Comparator.comparing(PendingTurn::getCreatedAt));
        return List.copyOf(all);
    }

    @Override
    public Optional<PendingTurn> remove(PendingTurnId id) {
        Objects.requireNonNull(id, "id cannot be null");
        return Optional.ofNullable(turns.remove(id));
    }

    @Override
    public List<PendingTurn> removeExpired(Instant now) {
        Objects.requireNonNull(now, "now cannot be null");
        final List<PendingTurn> removed = new ArrayList<>();
        for (PendingTurn turn : turns.values()) {
            if (turn.isExpired(now) && turns.remove(turn.getId(), turn)) {
                removed.add(turn);
            }
        }
        return List.copyOf(removed);
    }
}
