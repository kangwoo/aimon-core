package at.aimon.core.agent;

import java.util.List;

import at.aimon.core.agent.tool.permission.AllowedTool;

/**
 * Agent definition interface.
 *
 * <p>
 * Defines the structure and behavior configuration of an agent. Agents have a name, metadata (like max iterations), and
 * content (like system prompt and model config).
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     Agent agent = DefaultAgent.builder().name("MyAgent").metadata(AgentMetadata.of(10))
 *             .content(AgentContent.of("You are helpful...", modelConfig)).build();
 *
 *     String name = agent.getName();
 *     int maxIterations = agent.getMaxIterations(); // convenience method
 *     String systemPrompt = agent.getSystemPrompt(); // convenience method
 * }
 * </pre>
 */
public interface Agent {

    /**
     * Gets the agent name.
     *
     * @return The agent name (never null)
     */
    default String getName() {
        return getMetadata().getName();
    }

    /**
     * Gets the agent metadata.
     *
     * @return The agent metadata (never null)
     */
    AgentMetadata getMetadata();

    /**
     * Gets the agent content.
     *
     * @return The agent content (never null)
     */
    AgentContent getContent();

    /**
     * Gets the allow-list bounding every tool call this agent makes.
     *
     * <p>
     * <b>An empty list means unrestricted</b>, which is both the default and what every validator in
     * {@code at.aimon.core.agent.tool.permission} does with one.
     *
     * @return An immutable list of allowed tools (never null, may be empty)
     */
    default List<AllowedTool> getAllowedTools() {
        return getMetadata().getAllowedTools();
    }

    /**
     * Gets whether this agent declares any tool restriction at all.
     *
     * @return true when the allow-list is non-empty
     */
    default boolean hasToolRestrictions() {
        return getMetadata().hasToolRestrictions();
    }

}
