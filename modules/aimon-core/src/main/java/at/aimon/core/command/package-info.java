/**
 * Command system providing both system and custom commands.
 *
 * <p>
 * This package implements a dual command system supporting two types of commands:
 *
 * <ul>
 * <li><b>System Commands</b> - Built-in Java classes for core functionality
 * <li><b>Custom Commands</b> - User-defined Markdown files for extensibility
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <p>
 * The command system uses the Strategy pattern with two execution strategies:
 *
 * <ul>
 * <li>{@link at.aimon.core.command.execution.direct.DirectExecutable} - Direct Java execution
 * <li>{@link at.aimon.core.command.execution.llm.LlmExecutable} - LLM-powered execution
 * </ul>
 *
 * <p>
 * Key components:
 *
 * <ul>
 * <li>{@link at.aimon.core.command.Command} - Base interface for all commands
 * <li>{@link at.aimon.core.command.CommandRegistry} - Unified registry for lookup
 * <li>{@link at.aimon.core.command.CommandExecutionManager} - Orchestrates execution
 * <li>{@link at.aimon.core.command.execution.CommandExecutor} - Strategy interface
 * <li>{@link at.aimon.core.command.execution.CompositeCommandExecutor} - Combines executors
 * </ul>
 *
 * <h2>Custom Command Format</h2>
 *
 * <p>
 * Custom commands are defined in Markdown files with YAML frontmatter:
 *
 * <pre>
 * ---
 * description: Human-readable description
 * allowed-tools: Tool1, Tool2(arg:*), Tool3
 * max-iterations: 100
 * ---
 *
 * Command instructions in Markdown format.
 * Use !command for command expansion.
 * Use @file for file reference.
 * Use $1, $2, etc. for arguments.
 * </pre>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create registry
 *     CommandRegistry registry = new DefaultCommandRegistry(systemCommands, skillRegistry, fileSystem,
 *             ".aimon/commands");
 *
 *     // Get and execute command
 *     Command command = registry.getCommandOrThrow("commit");
 *     CommandExecutionManager manager = new DefaultCommandExecutionManager(llmClient);
 *     CommandExecutionResult result = manager.execute(request, transcriptBuffer, registry, toolRegistry, model);
 * }
 * </pre>
 *
 * @see at.aimon.core.command.Command
 * @see at.aimon.core.command.CommandRegistry
 * @see at.aimon.core.command.CommandExecutionManager
 */
package at.aimon.core.command;
