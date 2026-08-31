package at.aimon.core.agent;

import java.util.List;
import java.util.Optional;

/**
 * Registry interface for managing {@link Agent} instances by name.
 *
 * <p>
 * This interface provides a centralized registry contract for agents. Implementations manage {@link Agent} instances
 * and provide name-based lookup and lifecycle management.
 *
 * <p>
 * The registry supports:
 *
 * <ul>
 * <li>Agent registration and unregistration by name
 * <li>Retrieving agent instances by name
 * <li>Querying all registered agents
 * <li>Registry lifecycle management (size, isEmpty, clear)
 * </ul>
 *
 * <p>
 * <b>Implementation Notes:</b>
 *
 * <ul>
 * <li>Thread-safety is implementation-dependent; see specific implementations for guarantees
 * <li>All query methods should return immutable collections to prevent external modification
 * <li>Agent names are determined by {@link Agent#getName()} and must be unique
 * <li>If an agent with the same name is registered, it replaces the existing one
 * </ul>
 *
 * <p>
 * <b>Example usage:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentRegistry registry = new DefaultAgentRegistry();
 *     registry.register(DefaultAgent.builder().name("agent1").systemPrompt("You are agent1").build());
 *     registry.register(DefaultAgent.builder().name("agent2").systemPrompt("You are agent2").build());
 *
 *     Optional<Agent> agent = registry.findByName("agent1");
 *     List<Agent> allAgents = registry.findAll();
 *     registry.unregister("agent1");
 *     registry.clear();
 * }
 * </pre>
 *
 * @see Agent
 * @see DefaultAgentRegistry
 */
public interface AgentRegistry {

    /**
     * Registers an agent in the registry.
     *
     * <p>
     * If an agent with the same name already exists, it will be replaced. The agent name is determined by
     * {@link Agent#getName()}.
     *
     * @param agent
     *            the agent to register (must not be null)
     * @throws NullPointerException
     *             if agent is null
     */
    void register(Agent agent);

    /**
     * Unregisters an agent from the registry.
     *
     * <p>
     * If no agent with the specified name exists, this method does nothing.
     *
     * @param agentName
     *            the name of the agent to unregister (must not be null)
     * @throws NullPointerException
     *             if agentName is null
     */
    void unregister(String agentName);

    /**
     * Finds an agent by name.
     *
     * @param agentName
     *            the name of the agent to find (must not be null)
     * @return an Optional containing the agent if registered, empty otherwise
     * @throws NullPointerException
     *             if agentName is null
     */
    Optional<Agent> findByName(String agentName);

    /**
     * Gets all registered agents.
     *
     * <p>
     * The returned list is immutable. The order of agents is not guaranteed.
     *
     * @return an immutable list of agents (never null, may be empty)
     */
    List<Agent> findAll();

    /**
     * Gets the number of registered agents.
     *
     * @return the agent count (never negative)
     */
    int size();

    /**
     * Checks if the registry is empty.
     *
     * @return true if no agents are registered, false otherwise
     */
    boolean isEmpty();

    /**
     * Removes all agents from the registry.
     *
     * <p>
     * After calling this method, the registry will be empty ({@link #isEmpty()} returns {@code true}) and
     * {@link #size()} returns 0.
     */
    void clear();

}
