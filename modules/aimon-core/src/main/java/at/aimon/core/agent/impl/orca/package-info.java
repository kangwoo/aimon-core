/**
 * Orca agent implementation using the ReAct (Reasoning and Acting) pattern.
 *
 * <p>
 * The Orca agent is the primary agent implementation in Aimon. It implements the ReAct pattern, which combines
 * reasoning and acting in an iterative loop to solve complex tasks.
 *
 * <h2>ReAct Pattern Overview</h2>
 *
 * <p>
 * The ReAct pattern consists of three phases repeated iteratively:
 *
 * <ol>
 * <li><b>Thought</b> - The agent analyzes the current situation and reasons about the next action
 * <li><b>Action</b> - The agent uses tools to gather information or perform operations
 * <li><b>Observation</b> - The agent observes the results of the action and updates its understanding
 * </ol>
 *
 * <p>
 * This cycle continues until the agent achieves its goal or reaches the maximum iteration limit.
 *
 * <h2>Core Components</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.impl.orca.OrcaAgentExecutor} - Main ReAct loop implementation
 * <li>{@link at.aimon.core.agent.impl.orca.OrcaAgentExecutorFactory} - Factory for creating executors
 * <li>{@link at.aimon.core.agent.impl.orca.OrcaAgentRuntime} - Runtime configuration and dependencies
 * <li>{@link at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory} - Factory for creating agent runtimes
 * <li>{@link at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest} - Execution request with user input
 * <li>{@link at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult} - Execution result with metadata
 * </ul>
 *
 * <h2>Sub-packages</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.impl.orca.command} - Command providers for registering system and custom commands
 * <li>{@link at.aimon.core.agent.impl.orca.tool} - Tool providers for registering various tool implementations
 * <li>{@link at.aimon.core.agent.impl.orca.environment} - Execution environment abstractions and implementations
 * </ul>
 *
 * <h2>Provider Pattern</h2>
 *
 * <p>
 * The Orca agent uses a provider pattern for modular registration of tools and commands. This enables:
 *
 * <ul>
 * <li>Flexible composition of tool and command sets
 * <li>Easy testing through dependency injection
 * <li>Custom tool and command configurations per agent
 * <li>Clear separation of concerns
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // 1. Create LLM client
 *     LlmClient llmClient = new OpenAILlmClient(apiKey);
 *
 *     // 2. Create agent executor
 *     TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecordStore);
 *     OrcaAgentExecutorFactory executorFactory = new OrcaAgentExecutorFactory();
 *     OrcaAgentExecutor executor = executorFactory.create(llmClient, transcriptManager);
 *
 *     // 3. Create agent
 *     Agent agent = Agent.builder().name("MyAgent").content(AgentContent.of(systemPrompt)).build();
 *
 *     // 4. Create file system
 *     VirtualFileSystem fileSystem = new LocalFileSystem(config);
 *
 *     // 5. Create the agent runtime with default providers
 *     OrcaAgentRuntimeFactory agentRuntimeFactory = new OrcaAgentRuntimeFactory();
 *     OrcaAgentRuntime runtime = agentRuntimeFactory.create(executor, agent, fileSystem,
 *             OrcaAgentRuntimeFactory.defaultToolProviders(),
 *             OrcaAgentRuntimeFactory.defaultCommandProviders());
 *
 *     // 6. Create execution request
 *     OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder()
 *             .userInput(TextInput.of("What files are in the current directory?")).build();
 *
 *     // 7. Execute
 *     OrcaAgentExecutionResult result = executor.execute(runtime, request);
 *     System.out.println(result.getFinalAnswer());
 * }
 * </pre>
 *
 * <h2>Extensibility</h2>
 *
 * <p>
 * The Orca agent can be extended by:
 *
 * <ul>
 * <li>Creating custom tool providers that implement {@link at.aimon.core.agent.orca.tool.OrcaToolProvider}
 * <li>Creating custom command providers that implement
 * {@link at.aimon.core.agent.impl.orca.command.OrcaCommandProvider}
 * <li>Implementing custom execution environments that implement
 * {@link at.aimon.core.agent.impl.orca.environment.VirtualExecutionEnvironment}
 * <li>Extending the factory classes to provide custom default implementations
 * </ul>
 *
 * @see at.aimon.core.agent.Agent
 * @see at.aimon.core.agent.AgentExecutor
 * @see at.aimon.core.agent.impl.orca.OrcaAgentExecutor
 */
package at.aimon.core.agent.impl.orca;
