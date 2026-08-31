/**
 * Command providers for the Orca agent system.
 *
 * <p>
 * This package contains command providers that register commands to the Orca agent. Commands are user-facing operations
 * that can be invoked by typing a slash command (e.g., {@code /help}, {@code /version}, {@code /clear}).
 *
 * <h2>Command System Overview</h2>
 *
 * <p>
 * Aimon supports two types of commands:
 *
 * <ul>
 * <li><b>System Commands</b> - Built-in Java classes that provide core functionality (help, version, clear)
 * <li><b>Custom Commands</b> - User-defined Markdown files that enable LLM-powered command execution with tool calling
 * </ul>
 *
 * <h2>Provider Pattern</h2>
 *
 * <p>
 * Command providers follow the Provider pattern to enable modular command registration. This allows:
 *
 * <ul>
 * <li>Flexible composition of command sets
 * <li>Easy testing through dependency injection
 * <li>Custom command configurations per agent
 * <li>Clear separation of concerns
 * </ul>
 *
 * <h2>Core Components</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.impl.orca.command.OrcaCommandProvider} - Interface for command providers
 * <li>{@link at.aimon.core.agent.impl.orca.command.OrcaCommandProviderContext} - Context with dependencies for command
 * registration
 * <li>{@link at.aimon.core.agent.impl.orca.command.OrcaSystemCommandProvider} - Provider for built-in system commands
 * </ul>
 *
 * <h2>Default System Commands</h2>
 *
 * <p>
 * The {@link at.aimon.core.agent.impl.orca.command.OrcaSystemCommandProvider} registers:
 *
 * <ul>
 * <li>{@code /help} - Display help information and list available commands
 * <li>{@code /version} - Display version information
 * <li>{@code /clear} - Clear the console
 * </ul>
 *
 * <h2>Creating Custom Command Providers</h2>
 *
 * <p>
 * To create a custom command provider:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class MyCommandProvider implements OrcaCommandProvider {
 *         &#64;Override
 *         public void registerCommands(CommandRegistry registry, OrcaCommandProviderContext context) {
 *             Objects.requireNonNull(registry, "registry must not be null");
 *             Objects.requireNonNull(context, "context must not be null");
 *
 *             // Register system commands (Java classes)
 *             registry.registerSystemCommand(new MyCustomCommand());
 *
 *             // Custom commands (Markdown files) are loaded automatically from .aimon/commands
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Using Custom Providers</h2>
 *
 * <p>
 * Custom command providers can be used when creating the agent runtime:
 *
 * <pre>
 * {
 *     &#64;code
 *     List<OrcaCommandProvider> providers = List.of(new OrcaSystemCommandProvider(), // Built-in commands
 *             new MyCommandProvider() // Custom commands
 *     );
 *
 *     OrcaAgentRuntime runtime = agentRuntimeFactory.create(executor, agent, fileSystem,
 *             OrcaAgentRuntimeFactory.defaultToolProviders(), providers);
 * }
 * </pre>
 *
 * <h2>Custom Command Files</h2>
 *
 * <p>
 * Custom commands can be defined as Markdown files in the {@code .aimon/commands} directory. These files are loaded
 * automatically by the {@link at.aimon.core.command.CommandRegistry} and executed using the LLM with tool calling.
 *
 * <p>
 * Example custom command file ({@code .aimon/commands/review.md}):
 *
 * <pre>
 * ---
 * name: review
 * description: Review code files for quality and best practices
 * allowed-tools:
 *   - Read
 *   - Grep
 * ---
 *
 * Please review the following code files for:
 * - Code quality and readability
 * - SOLID principles adherence
 * - Potential bugs or issues
 * - Best practices
 *
 * Files to review: $ARGUMENTS
 * </pre>
 *
 * @see at.aimon.core.command.CommandRegistry
 * @see at.aimon.core.command.SystemCommand
 * @see at.aimon.core.command.skill.SkillBackedCommand
 */
package at.aimon.core.agent.impl.orca.command;
