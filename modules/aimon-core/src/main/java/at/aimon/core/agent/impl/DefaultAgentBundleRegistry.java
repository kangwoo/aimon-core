package at.aimon.core.agent.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of {@link AgentBundleRegistry}.
 *
 * <p>
 * Uses a {@link ConcurrentHashMap} for safe concurrent access from multiple threads. All query methods return immutable
 * collections.
 *
 * <p>
 * If a bundle with the same agent name is registered, it replaces the existing one (replace semantics).
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentBundleRegistry registry = new DefaultAgentBundleRegistry();
 *     registry.register(AgentBundle.builder().agent(myAgent).build());
 *
 *     Optional<AgentBundle> bundle = registry.findByName("myAgent");
 *     List<AgentBundle> allBundles = registry.findAll();
 *     int count = registry.size();
 *     registry.clear();
 * }
 * </pre>
 *
 * @see AgentBundleRegistry
 * @see AgentBundle
 */
public final class DefaultAgentBundleRegistry implements AgentBundleRegistry {

    private final Map<String, AgentBundle> bundles = new ConcurrentHashMap<>();

    @Override
    public void register(AgentBundle bundle) {
        Objects.requireNonNull(bundle, "AgentBundle cannot be null");
        String name = Objects.requireNonNull(bundle.getAgent().getName(), "Agent name cannot be null");
        bundles.put(name, bundle);
    }

    @Override
    public void unregister(String agentName) {
        Objects.requireNonNull(agentName, "Agent name cannot be null");
        bundles.remove(agentName);
    }

    @Override
    public Optional<AgentBundle> findByName(String agentName) {
        Objects.requireNonNull(agentName, "Agent name cannot be null");
        return Optional.ofNullable(bundles.get(agentName));
    }

    @Override
    public List<AgentBundle> findAll() {
        return List.copyOf(bundles.values());
    }

    @Override
    public int size() {
        return bundles.size();
    }

    @Override
    public boolean isEmpty() {
        return bundles.isEmpty();
    }

    @Override
    public void clear() {
        bundles.clear();
    }

}
