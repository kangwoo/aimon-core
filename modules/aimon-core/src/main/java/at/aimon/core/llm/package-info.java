/**
 * Core abstractions for Large Language Model (LLM) integration.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides a provider-agnostic abstraction layer for interacting with LLM APIs. It enables seamless
 * integration with multiple LLM providers (OpenAI, Anthropic, etc.) through a unified interface while supporting
 * advanced features like tool calling (function calling) and multimodal interactions.
 *
 * <h2>Core Components</h2>
 *
 * <h3>LLM Client Interface</h3>
 * <ul>
 * <li>{@link at.aimon.core.llm.LlmClient} - Main interface for LLM communication
 * <li>{@link at.aimon.core.llm.LlmModel} - Model configuration (temperature, max tokens, etc.)
 * </ul>
 *
 * <h3>Message System</h3>
 * <ul>
 * <li>{@link at.aimon.core.llm.Message} - Conversation message with role and content
 * <li>{@link at.aimon.core.llm.Role} - Message roles (USER, ASSISTANT, TOOL)
 * <li>{@link at.aimon.core.llm.LlmResponse} - LLM response with text and/or tool uses
 * </ul>
 *
 * <h3>Tool System</h3>
 * <ul>
 * <li>{@link at.aimon.core.llm.ToolDefinition} - JSON Schema-based tool definition
 * <li>{@link at.aimon.core.llm.ToolDefinitionProvider} - Strategy for providing tool definitions
 * <li>{@link at.aimon.core.llm.StaticToolDefinitionProvider} - Immutable tool definitions
 * <li>{@link at.aimon.core.llm.DynamicToolDefinitionProvider} - Runtime-generated tool definitions
 * <li>{@link at.aimon.core.llm.ToolUse} - LLM's request to use a tool
 * <li>{@link at.aimon.core.llm.ToolUseResult} - Result of tool execution
 * </ul>
 *
 * <h3>Metadata</h3>
 * <ul>
 * <li>{@link at.aimon.core.llm.TokenUsage} - Token consumption tracking
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <p>
 * The package follows a layered architecture with clear separation of concerns:
 *
 * <pre>
 * ┌─────────────────────────────────────────┐
 * │   Application Layer (Agent System)      │
 * └─────────────────┬───────────────────────┘
 *                   │
 * ┌─────────────────▼───────────────────────┐
 * │   Abstraction Layer (LlmClient)         │
 * │   - Unified interface                   │
 * │   - Message/Tool abstractions           │
 * │   - Provider-agnostic types             │
 * └─────────────────┬───────────────────────┘
 *                   │
 * ┌─────────────────▼───────────────────────┐
 * │   Implementation Layer                  │
 * │   - OpenAILlmClient (aimon-llm-openai)  │
 * │   - AnthropicLlmClient (aimon-llm-anthropic) │
 * └─────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Design Principles</h2>
 *
 * <h3>Immutability</h3>
 * <p>
 * All value objects are immutable and thread-safe. Collections are defensively copied using
 * {@link java.util.List#copyOf} and {@link java.util.Map#copyOf}.
 *
 * <h3>Null Safety</h3>
 * <p>
 * All public APIs use {@link java.util.Objects#requireNonNull} for null checks and clearly document null handling in
 * Javadoc. Optional parameters are represented using {@link java.util.Optional}.
 *
 * <h3>Provider Abstraction</h3>
 * <p>
 * The {@link at.aimon.core.llm.LlmClient} interface abstracts away provider-specific details. For example, the
 * {@link at.aimon.core.llm.Role#TOOL} role is converted to the appropriate provider format:
 * <ul>
 * <li>OpenAI: Native "tool" role
 * <li>Anthropic: USER message with tool_result content blocks
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Basic Conversation</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     OpenAIConfig config = OpenAIConfig.builder().apiKey(apiKey).model("gpt-4").build();
 *     LlmClient client = new OpenAILlmClient(config);
 *
 *     List<Message> messages = List.of(Message.user("What is the capital of France?"));
 *
 *     LlmResponse response = client.sendMessage("You are a helpful assistant", messages, List.of() // No tools
 *     );
 *
 *     System.out.println(response.getTextContent());
 *     // Output: "The capital of France is Paris."
 * }
 * </pre>
 *
 * <h3>Tool Calling (Function Calling)</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Define a tool
 *     ToolDefinition weatherTool = ToolDefinition.of("get_weather", "Get current weather for a location",
 *             Map.of("type", "object", "properties",
 *                     Map.of("location", Map.of("type", "string", "description", "City name")), "required",
 *                     List.of("location")));
 *
 *     // Send message with tool
 *     List<Message> messages = List.of(Message.user("What's the weather in Tokyo?"));
 *
 *     LlmResponse response = client.sendMessage("You are a helpful assistant", messages, List.of(weatherTool));
 *
 *     // Check if LLM wants to use a tool
 *     if (response.hasToolUses()) {
 *         for (ToolUse toolUse : response.getToolUses()) {
 *             // Execute tool
 *             String location = (String) toolUse.getInput().get("location");
 *             String result = executeWeatherApi(location);
 *
 *             // Send result back to LLM
 *             messages.add(Message.assistant(response.getTextContent(), response.getToolUses()));
 *             messages.add(Message.toolUseResults(List.of(ToolUseResult.success(toolUse.getId(), result))));
 *
 *             // Continue conversation
 *             LlmResponse finalResponse = client.sendMessage("You are a helpful assistant", messages,
 *                     List.of(weatherTool));
 *             System.out.println(finalResponse.getTextContent());
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Dynamic Model Configuration</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     LlmModel modelConfig = LlmModel.builder().name("gpt-4").temperature(0.7).maxTokens(2000).topP(0.9).build();
 *
 *     LlmResponse response = client.sendMessage(systemPrompt, messages, tools, modelConfig);
 * }
 * </pre>
 *
 * <h3>Token Usage Tracking</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     LlmResponse response = client.sendMessage(systemPrompt, messages, tools);
 *
 *     TokenUsage usage = response.getTokenUsage();
 *     System.out.printf("Prompt tokens: %d%n", usage.getPromptTokens());
 *     System.out.printf("Completion tokens: %d%n", usage.getCompletionTokens());
 *     System.out.printf("Total tokens: %d%n", usage.getTotalTokens());
 *
 *     // Accumulate usage across multiple calls
 *     TokenUsage totalUsage = usage1.add(usage2).add(usage3);
 * }
 * </pre>
 *
 * <h3>Dynamic Tool Definitions</h3>
 *
 * <pre>
 * {@code
 * SubagentRegistry registry = ...;
 *
 * // Tool description changes based on available subagents
 * ToolDefinitionProvider provider = new DynamicToolDefinitionProvider(
 *     "task",
 *     () -> {
 *         StringBuilder desc = new StringBuilder("Launch subagents. Available: ");
 *         registry.getAllSubagents()
 *             .forEach(s -> desc.append(s.getName()).append(", "));
 *         return desc.toString();
 *     },
 *     schema
 * );
 *
 * // Each call may return different description
 * ToolDefinition definition = provider.getDefinition();
 * }
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * All value objects in this package are immutable and thread-safe. {@link at.aimon.core.llm.LlmClient} implementations
 * should be thread-safe and document their thread-safety guarantees.
 *
 * <h2>Exception Handling</h2>
 *
 * <p>
 * LLM API errors are reported through {@link at.aimon.core.llm.exception.LlmClientException}, which includes:
 * <ul>
 * <li>Network connectivity issues
 * <li>API authentication failures
 * <li>Invalid API responses
 * <li>Rate limiting or quota exceeded
 * <li>LLM API errors (4xx, 5xx status codes)
 * </ul>
 *
 * <h2>Related Packages</h2>
 * <ul>
 * <li>{@code at.aimon.core.llms.openai} - OpenAI implementation
 * <li>{@code at.aimon.core.llms.anthropic} - Anthropic implementation
 * <li>{@code at.aimon.core.agent} - Agent system that uses this abstraction
 * <li>{@code at.aimon.core.agent.tool} - Tool execution framework
 * </ul>
 *
 * @see at.aimon.core.llm.LlmClient
 * @see at.aimon.core.llm.Message
 * @see at.aimon.core.llm.ToolDefinition
 * @since 1.0
 */
package at.aimon.core.llm;
