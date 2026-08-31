package at.aimon.core.agent;

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

}
