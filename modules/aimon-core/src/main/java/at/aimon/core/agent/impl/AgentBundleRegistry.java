package at.aimon.core.agent.impl;

import java.util.List;
import java.util.Optional;

/**
 * Registry interface for managing {@link AgentBundle} instances by name.
 *
 * <p>
 * This interface provides a centralized registry contract for agent bundles. Implementations manage {@link AgentBundle}
 * instances and provide name-based lookup and lifecycle management.
 *
 * <p>
 * The registry supports:
 *
 * <ul>
 * <li>Bundle registration and unregistration by agent name
 * <li>Retrieving bundle instances by agent name
 * <li>Querying all registered bundles
 * <li>Registry lifecycle management (size, isEmpty, clear)
 * </ul>
 *
 * <p>
 * <b>Implementation Notes:</b>
 *
 * <ul>
 * <li>Thread-safety is implementation-dependent; see specific implementations for guarantees
 * <li>All query methods should return immutable collections to prevent external modification
 * <li>Bundle names are determined by {@code bundle.getAgent().getName()} and must be unique
 * <li>If a bundle with the same name is registered, it replaces the existing one
 * </ul>
 *
 * <p>
 * <b>Example usage:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentBundleRegistry registry = new DefaultAgentBundleRegistry();
 *     registry.register(AgentBundle.builder().agent(agent1).build());
 *     registry.register(AgentBundle.builder().agent(agent2).build());
 *
 *     Optional<AgentBundle> bundle = registry.findByName("agent1");
 *     List<AgentBundle> allBundles = registry.findAll();
 *     registry.unregister("agent1");
 *     registry.clear();
 * }
 * </pre>
 *
 * @see AgentBundle
 * @see DefaultAgentBundleRegistry
 */
public interface AgentBundleRegistry {

    /**
     * Registers an agent bundle in the registry.
     *
     * <p>
     * If a bundle with the same agent name already exists, it will be replaced. The bundle name is determined by
     * {@code bundle.getAgent().getName()}.
     *
     * @param bundle
     *            the agent bundle to register (must not be null)
     * @throws NullPointerException
     *             if bundle is null
     */
    void register(AgentBundle bundle);

    /**
     * Unregisters an agent bundle from the registry.
     *
     * <p>
     * If no bundle with the specified agent name exists, this method does nothing.
     *
     * @param agentName
     *            the agent name of the bundle to unregister (must not be null)
     * @throws NullPointerException
     *             if agentName is null
     */
    void unregister(String agentName);

    /**
     * Finds an agent bundle by agent name.
     *
     * @param agentName
     *            the agent name of the bundle to find (must not be null)
     * @return an Optional containing the bundle if registered, empty otherwise
     * @throws NullPointerException
     *             if agentName is null
     */
    Optional<AgentBundle> findByName(String agentName);

    /**
     * Gets all registered agent bundles.
     *
     * <p>
     * The returned list is immutable. The order of bundles is not guaranteed.
     *
     * @return an immutable list of agent bundles (never null, may be empty)
     */
    List<AgentBundle> findAll();

    /**
     * Gets the number of registered agent bundles.
     *
     * @return the bundle count (never negative)
     */
    int size();

    /**
     * Checks if the registry is empty.
     *
     * @return true if no bundles are registered, false otherwise
     */
    boolean isEmpty();

    /**
     * Removes all agent bundles from the registry.
     *
     * <p>
     * After calling this method, the registry will be empty ({@link #isEmpty()} returns {@code true}) and
     * {@link #size()} returns 0.
     */
    void clear();

}
