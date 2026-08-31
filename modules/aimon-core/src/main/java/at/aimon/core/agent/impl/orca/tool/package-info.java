/**
 * Tool providers for the Orca agent system.
 *
 * <p>
 * This package contains tool providers that register tools to the Orca agent. Tools are functions that the agent can
 * call to perform operations like reading files, executing commands, or managing subagents.
 *
 * <h2>Tool System Overview</h2>
 *
 * <p>
 * Tools enable agents to interact with the external world. The Orca agent uses tool calling (function calling) to allow
 * the LLM to request tool execution based on the current task.
 *
 * <h2>Provider Pattern</h2>
 *
 * <p>
 * Tool providers follow the Provider pattern to enable modular tool registration. This allows:
 *
 * <ul>
 * <li>Flexible composition of tool sets
 * <li>Easy testing through dependency injection
 * <li>Custom tool configurations per agent
 * <li>Clear separation of concerns
 * </ul>
 *
 * <h2>Core Components</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.orca.tool.OrcaToolProvider} - Interface for tool providers
 * <li>{@link at.aimon.core.agent.orca.tool.OrcaToolProviderContext} - Context with dependencies for tool
 * registration
 * </ul>
 *
 * <h2>Default Tool Providers</h2>
 *
 * <p>
 * The Orca agent comes with several default tool providers:
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.impl.orca.tool.OrcaTodoToolProvider} - Todo management tools
 * <ul>
 * <li>{@link at.aimon.core.tools.todo.TodoWriteTool} - Manage task lists and track progress
 * </ul>
 * <li>{@link at.aimon.core.agent.impl.orca.tool.OrcaFileToolProvider} - File operation tools
 * <ul>
 * <li>{@link at.aimon.core.tools.file.ReadTool} - Read file contents
 * <li>{@link at.aimon.core.tools.file.WriteTool} - Write files
 * <li>{@link at.aimon.core.tools.file.EditTool} - Edit existing files with find-and-replace
 * <li>{@link at.aimon.core.tools.file.GrepTool} - Search file contents using patterns
 * </ul>
 * <li>{@link at.aimon.core.agent.impl.orca.tool.OrcaBashToolProvider} - Bash execution tools
 * <ul>
 * <li>{@link at.aimon.core.tools.bash.BashTool} - Execute bash commands (foreground or background)
 * <li>{@link at.aimon.core.tools.bash.BashOutputTool} - Monitor and retrieve output from background bash processes
 * </ul>
 * <li>{@link at.aimon.core.agent.impl.orca.tool.OrcaSubagentToolProvider} - Subagent management tools
 * <ul>
 * <li>{@link at.aimon.core.tools.task.TaskTool} - Launch and manage subagent execution
 * <li>{@link at.aimon.core.tools.task.AgentOutputTool} - Retrieve output from background subagent tasks
 * </ul>
 * <li>{@link at.aimon.core.agent.impl.orca.tool.OrcaSkillToolProvider} - Skill management tools
 * <ul>
 * <li>{@link at.aimon.core.tools.skill.SkillTool} - Activate and execute specialized skills
 * </ul>
 * </ul>
 *
 * <h2>Creating Custom Tool Providers</h2>
 *
 * <p>
 * To create a custom tool provider:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class MyToolProvider implements OrcaToolProvider {
 *         &#64;Override
 *         public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
 *             Objects.requireNonNull(registry, "registry must not be null");
 *             Objects.requireNonNull(context, "context must not be null");
 *
 *             // Get dependencies from context
 *             VirtualFileSystem fileSystem = context.getFileSystem();
 *             Environment environment = context.getEnvironment();
 *
 *             // Validate dependencies
 *             Objects.requireNonNull(fileSystem, "fileSystem must not be null in context");
 *             Objects.requireNonNull(environment, "environment must not be null in context");
 *
 *             // Register custom tools
 *             registry.register(new MyCustomTool(fileSystem, environment));
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Using Custom Providers</h2>
 *
 * <p>
 * Custom tool providers can be used when creating the agent runtime:
 *
 * <pre>
 * {
 *     &#64;code
 *     List<OrcaToolProvider> providers = List.of(new OrcaTodoToolProvider(), new OrcaFileToolProvider(),
 *             new OrcaBashToolProvider(), new OrcaSubagentToolProvider(), new OrcaSkillToolProvider(),
 *             new MyToolProvider() // Custom tools
 *     );
 *
 *     OrcaAgentRuntime runtime = agentRuntimeFactory.create(executor, agent, fileSystem, providers,
 *             OrcaAgentRuntimeFactory.defaultCommandProviders());
 * }
 * </pre>
 *
 * <h2>Tool Context</h2>
 *
 * <p>
 * The {@link at.aimon.core.agent.orca.tool.OrcaToolProviderContext} provides dependencies needed for tool
 * creation:
 *
 * <ul>
 * <li>{@link at.aimon.core.filesystem.VirtualFileSystem} - Abstract file system operations
 * <li>{@link at.aimon.core.agent.Environment} - Runtime environment information
 * <li>{@link at.aimon.core.agent.Agent} - The agent using the tools
 * <li>{@link at.aimon.core.subagent.SubagentRegistry} - Registry of available subagents
 * <li>{@link at.aimon.core.subagent.SubagentExecutionManager} - Manager for subagent execution
 * <li>{@link at.aimon.core.skill.SkillRegistry} - Registry of available skills
 * <li>{@link at.aimon.core.agent.tool.ToolRegistry} - Registry for tool registration
 * </ul>
 *
 * @see at.aimon.core.agent.tool.Tool
 * @see at.aimon.core.agent.tool.ToolRegistry
 * @see at.aimon.core.agent.tool.ToolContext
 */
package at.aimon.core.agent.impl.orca.tool;
