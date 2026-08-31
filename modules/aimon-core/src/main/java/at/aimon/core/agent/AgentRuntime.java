package at.aimon.core.agent;

import java.util.List;
import java.util.Optional;

import at.aimon.core.agent.tool.Tool;

/**
 * Provides the agent runtime for an agent.
 *
 * <p>
 * The agent runtime encapsulates the runtime environment needed for agent execution, including the agent
 * configuration and available tools.
 *
 * <p>
 * <b>Lifetime — agent-scoped.</b> A single {@code AgentRuntime} is shared across every session and every
 * {@code LiveSession} that targets the same {@code (Agent, discriminator)} pair. The runtime lives from agent
 * registration / first lookup until application shutdown (or until the agent is explicitly removed); per-session
 * resources (message history, queue, etc.) are kept on {@code LiveSession}, never here. See
 * {@code .claude/rules/scheduling.md} ("Scope Model") for the full lifetime contract.
 *
 * <p>
 * This interface follows the Context pattern, providing a clean separation between agent configuration (what the agent
 * is) and agent execution (what the agent does).
 *
 * <h2>Design Principles</h2>
 * <ul>
 * <li><b>Dependency Inversion:</b> Implementations depend on the Agent abstraction, not concrete classes
 * <li><b>Single Responsibility:</b> Only responsible for providing agent runtime, not executing agents
 * <li><b>Interface Segregation:</b> Minimal interface with only essential context information
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     Agent agent = DefaultAgent.builder().name("MyAgent").maxIterations(10).systemPrompt("You are helpful...")
 *             .model(LlmModel.builder().build()).build();
 *
 *     List<Tool> tools = List.of(new BashTool(shell), new ReadTool(fileSystem));
 *
 *     AgentRuntime context = new MyAgentRuntime(agent, tools);
 *
 *     // Pass to executor
 *     AgentExecutor executor = new OrcaAgentExecutor(llmClient);
 *     AgentExecutionResult result = executor.execute(context, request);
 * }
 * </pre>
 *
 * @see Agent
 * @see AgentExecutor
 * @see AgentExecutionRequest
 * @see Tool
 */
public interface AgentRuntime {

    /**
     * Gets the unique identifier for this agent runtime.
     *
     * @return The agent runtime ID (never null)
     */
    AgentRuntimeId getId();

    /**
     * Gets the agent to be executed.
     *
     * @return The agent (never null)
     */
    Agent getAgent();

    /**
     * Gets the list of tools available for the agent to use.
     *
     * @return An immutable list of tools (never null, may be empty)
     */
    List<Tool> getAvailableTools();

    /**
     * Finds a tool by its name.
     *
     * @param toolName
     *            the name of the tool to find
     * @return the tool if found, or empty if no tool with the given name exists
     */
    default Optional<Tool> findToolByName(String toolName) {
        return getAvailableTools().stream().filter(t -> t.getDefinition().getName().equals(toolName)).findFirst();
    }

}
