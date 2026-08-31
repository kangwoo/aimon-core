package at.aimon.core.agent.impl;

import at.aimon.core.agent.definition.exception.AgentDefinitionLoadException;
import at.aimon.core.agent.definition.exception.AgentDefinitionNotFoundException;

/**
 * Interface for loading agent bundles from various sources.
 *
 * <p>
 * An agent bundle contains the agent definition along with its bundled subagent and skill registries. This enables
 * per-agent builtin configuration where each agent can ship with its own subagents and skills.
 *
 * <p>
 * Implementations should be thread-safe and reusable.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentBundleLoader loader = new ClasspathAgentBundleLoader();
 *     AgentBundle bundle = loader.load("default");
 *     Agent agent = bundle.getAgent();
 * }
 * </pre>
 *
 * @see AgentBundle
 * @see ClasspathAgentBundleLoader
 */
public interface AgentBundleLoader {

    /**
     * Loads an agent bundle by name.
     *
     * <p>
     * The bundle includes the agent and any bundled subagent/skill registries associated with the agent.
     *
     * @param name
     *            the agent bundle name (must not be null)
     * @return the loaded agent bundle (never null)
     * @throws AgentDefinitionNotFoundException
     *             if the agent definition is not found
     * @throws AgentDefinitionLoadException
     *             if loading or parsing fails
     * @throws NullPointerException
     *             if name is null
     */
    AgentBundle load(String name);

}
