package at.aimon.core.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of {@link AgentRegistry}.
 *
 * <p>
 * Uses a {@link ConcurrentHashMap} for safe concurrent access from multiple threads. All query methods return immutable
 * collections.
 *
 * <p>
 * If an agent with the same name is registered, it replaces the existing one (replace semantics).
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentRegistry registry = new DefaultAgentRegistry();
 *     registry.register(DefaultAgent.builder().name("agent1").systemPrompt("You are agent1").build());
 *
 *     Optional<Agent> agent = registry.findByName("agent1");
 *     List<Agent> allAgents = registry.findAll();
 *     int count = registry.size();
 *     registry.clear();
 * }
 * </pre>
 *
 * @see AgentRegistry
 * @see Agent
 */
public final class DefaultAgentRegistry implements AgentRegistry {

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    @Override
    public void register(Agent agent) {
        Objects.requireNonNull(agent, "Agent cannot be null");
        String name = Objects.requireNonNull(agent.getName(), "Agent name cannot be null");
        agents.put(name, agent);
    }

    @Override
    public void unregister(String agentName) {
        Objects.requireNonNull(agentName, "Agent name cannot be null");
        agents.remove(agentName);
    }

    @Override
    public Optional<Agent> findByName(String agentName) {
        Objects.requireNonNull(agentName, "Agent name cannot be null");
        return Optional.ofNullable(agents.get(agentName));
    }

    @Override
    public List<Agent> findAll() {
        return List.copyOf(agents.values());
    }

    @Override
    public int size() {
        return agents.size();
    }

    @Override
    public boolean isEmpty() {
        return agents.isEmpty();
    }

    @Override
    public void clear() {
        agents.clear();
    }

}
