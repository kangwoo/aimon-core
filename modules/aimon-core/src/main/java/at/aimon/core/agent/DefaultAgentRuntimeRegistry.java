package at.aimon.core.agent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of {@link AgentRuntimeRegistry}.
 *
 * <p>
 * Uses a {@link ConcurrentHashMap} for safe concurrent access from multiple threads.
 */
public class DefaultAgentRuntimeRegistry implements AgentRuntimeRegistry {

    private final Map<AgentRuntimeId, AgentRuntime> contexts = new ConcurrentHashMap<>();

    @Override
    public void register(AgentRuntime context) {
        Objects.requireNonNull(context, "Agent runtime cannot be null");
        contexts.put(context.getId(), context);
    }

    @Override
    public void unregister(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "Agent runtime ID cannot be null");
        contexts.remove(agentRuntimeId);
    }

    @Override
    public Optional<AgentRuntime> get(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "Agent runtime ID cannot be null");
        return Optional.ofNullable(contexts.get(agentRuntimeId));
    }
}
