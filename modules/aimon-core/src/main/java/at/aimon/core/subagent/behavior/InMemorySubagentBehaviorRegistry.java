package at.aimon.core.subagent.behavior;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link MutableSubagentBehaviorRegistry} for code-behavior subagents.
 *
 * <p>
 * Holds {@link SubagentBehavior} instances keyed by subagent name, registered programmatically at bootstrap. Mirrors
 * {@link at.aimon.core.subagent.InMemorySubagentRegistry}'s posture (in-memory default, interface-swappable for
 * multi-instance deployments). Thread-safe: backed by a {@link ConcurrentHashMap}.
 *
 * @see MutableSubagentBehaviorRegistry
 */
public final class InMemorySubagentBehaviorRegistry implements MutableSubagentBehaviorRegistry {

    private final Map<String, SubagentBehavior> behaviors = new ConcurrentHashMap<>();

    @Override
    public void register(String subagentName, SubagentBehavior behavior) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");
        Objects.requireNonNull(behavior, "Subagent behavior cannot be null");
        behaviors.put(subagentName, behavior);
    }

    @Override
    public Optional<SubagentBehavior> unregister(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");
        return Optional.ofNullable(behaviors.remove(subagentName));
    }

    @Override
    public Optional<SubagentBehavior> getBehavior(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");
        return Optional.ofNullable(behaviors.get(subagentName));
    }

    @Override
    public Set<String> behaviorNames() {
        return Set.copyOf(behaviors.keySet());
    }
}
