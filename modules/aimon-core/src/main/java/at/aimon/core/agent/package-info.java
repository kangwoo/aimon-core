/**
 * Core agent abstractions and implementations for the Aimon agent framework.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides the foundational interfaces and classes for building intelligent agents that follow the ReAct
 * (Reasoning and Acting) pattern. It defines the core abstractions for agent execution, context management, and result
 * handling.
 *
 * <h2>Key Concepts</h2>
 *
 * <h3>Agent</h3>
 *
 * <p>
 * The {@link at.aimon.core.agent.Agent} interface defines the structure and behavior configuration of an agent. Agents
 * have metadata (like name, max iterations, and model configuration) and content (like system prompt and variables).
 *
 * <pre>
 * {
 *     &#64;code
 *     Agent agent = DefaultAgent.builder().name("MyAgent").maxIterations(10)
 *             .systemPrompt("You are a helpful assistant...").model(LlmModel.builder().temperature(0.7).build())
 *             .build();
 * }
 * </pre>
 *
 * <h3>Agent Executor</h3>
 *
 * <p>
 * The {@link at.aimon.core.agent.AgentExecutor} interface defines how agents are executed. Implementations coordinate
 * the execution flow, typically following the ReAct pattern:
 *
 * <ol>
 * <li><b>Thought:</b> LLM reasons about the problem
 * <li><b>Action:</b> LLM requests tool use via function calling
 * <li><b>Observation:</b> Tool results are fed back
 * <li><b>Repeat:</b> Continue until task complete or max iterations reached
 * </ol>
 *
 * <h3>Execution Context and Request</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.AgentRuntime} provides the runtime environment (agent configuration and available
 * tools), while {@link at.aimon.core.agent.AgentExecutionRequest} encapsulates the user's request.
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create the agent runtime
 *     AgentRuntime runtime = MyAgentRuntime.builder().agent(agent).tools(tools).build();
 *
 *     // Create execution request
 *     UserInput input = TextInput.of("What files are here?");
 *     AgentExecutionRequest request = MyExecutionRequest.builder().userInput(input).principal(principal).build();
 *
 *     // Execute agent
 *     AgentExecutor executor = new OrcaAgentExecutor(llmClient);
 *     AgentExecutionResult result = executor.execute(runtime, request);
 * }
 * </pre>
 *
 * <h3>Execution Result</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.AgentExecutionResult} provides a structured way to handle both success and failure cases
 * without throwing exceptions:
 *
 * <pre>
 * {
 *     &#64;code
 *     if (result.isSuccess()) {
 *         String answer = result.getFinalAnswer();
 *         System.out.println("Agent response: " + answer);
 *     } else {
 *         String error = result.getErrorMessage();
 *         System.err.println("Execution failed: " + error);
 *     }
 * }
 * </pre>
 *
 * <h2>Supporting Classes</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.AgentMetadata} - Agent configuration metadata (max iterations, model config)
 * <li>{@link at.aimon.core.agent.AgentContent} - Agent content (system prompt, variables)
 * <li>{@link at.aimon.core.agent.Environment} - Runtime environment information (working directory, platform, OS)
 * <li>{@link at.aimon.core.agent.AgentEnvironmentSnapshot} - Collect-once snapshot of the ambient environment, keyed
 * by {@link at.aimon.core.agent.AgentRuntimeId} and served by
 * {@link at.aimon.core.agent.AgentEnvironmentSnapshotProvider}
 * <li>{@link at.aimon.core.base.Principal} - Identity representation (user, group, system, service)
 * <li>{@link at.aimon.core.agent.Version} - Semantic version representation
 * <li>{@link at.aimon.core.agent.Constants} - Core constants used throughout the system
 * </ul>
 *
 * <h2>Design Principles</h2>
 *
 * <p>
 * This package strictly adheres to SOLID principles:
 *
 * <ul>
 * <li><b>Single Responsibility Principle (SRP):</b> Each class has a single, well-defined responsibility
 * <li><b>Open/Closed Principle (OCP):</b> Extensible via interfaces without modifying existing code
 * <li><b>Liskov Substitution Principle (LSP):</b> All implementations fully substitute their interfaces
 * <li><b>Interface Segregation Principle (ISP):</b> Focused interfaces with only essential methods
 * <li><b>Dependency Inversion Principle (DIP):</b> Depends on abstractions, not concrete implementations
 * </ul>
 *
 * <h2>Related Packages</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.tool} - Tool system for agent capabilities
 * <li>{@link at.aimon.core.agent.session.store} - The durable session record and its store (the durable side)
 * <li>{@link at.aimon.core.agent.session} - Node-local live-session handles that run turns against a durable
 * session record (0..N {@code LiveSession} per {@code SessionRecord})
 * <li>{@link at.aimon.core.agent.input} - User input abstractions (text, image, audio, multimodal)
 * <li>{@link at.aimon.core.agent.template} - Template rendering for dynamic content
 * <li>{@link at.aimon.core.agent.exception} - Core exceptions
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // 1. Create agent
 *     Agent agent = DefaultAgent.builder().name("FileAssistant").maxIterations(10)
 *             .systemPrompt("You are a helpful file management assistant.").model(LlmModel.builder().build()).build();
 *
 *     // 2. Setup tools
 *     List<Tool> tools = List.of(new BashTool(shell), new ReadTool(fileSystem), new WriteTool(fileSystem));
 *
 *     // 3. Create the agent runtime
 *     AgentRuntime runtime = MyAgentRuntime.builder().agent(agent).tools(tools).build();
 *
 *     // 4. Create execution request
 *     UserInput input = TextInput.of("List all Java files in the src directory");
 *     Principal principal = Principal.user("user123", "john.doe");
 *     AgentExecutionRequest request = MyExecutionRequest.builder().userInput(input).principal(principal).build();
 *
 *     // 5. Execute agent
 *     AgentExecutor executor = new OrcaAgentExecutor(llmClient);
 *     AgentExecutionResult result = executor.execute(runtime, request);
 *
 *     // 6. Handle result
 *     if (result.isSuccess()) {
 *         System.out.println("Answer: " + result.getFinalAnswer());
 *     } else {
 *         System.err.println("Error: " + result.getErrorMessage());
 *     }
 * }
 * </pre>
 *
 * @see at.aimon.core.agent.Agent
 * @see at.aimon.core.agent.AgentExecutor
 * @see at.aimon.core.agent.AgentRuntime
 * @see at.aimon.core.agent.AgentExecutionRequest
 * @see at.aimon.core.agent.AgentExecutionResult
 */
package at.aimon.core.agent;
