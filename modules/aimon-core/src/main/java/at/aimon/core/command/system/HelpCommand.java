package at.aimon.core.command.system;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import at.aimon.core.agent.Constants;
import at.aimon.core.command.Command;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.command.execution.llm.LlmExecutable;

/**
 * Built-in help command that displays available commands and usage information.
 *
 * <p>
 * The help command provides:
 *
 * <ul>
 * <li>List of all available commands (system and skill-backed)
 * <li>Command descriptions and usage instructions
 * <li>Detailed help for specific commands when requested
 * </ul>
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /help              - List all commands
 * /help [command]    - Get detailed help for a specific command
 * </pre>
 *
 * <p>
 * This command executes directly without LLM interaction via {@link DirectExecutable}. It's a read-only operation with
 * no tools restrictions.
 */
public final class HelpCommand extends SystemCommand implements DirectExecutable {
    private final Supplier<CommandRegistry> registrySupplier;

    /**
     * Creates a new HelpCommand.
     *
     * @param registrySupplier
     *            Supplier that provides the command registry (must not be null)
     * @throws NullPointerException
     *             if registrySupplier is null
     */
    public HelpCommand(Supplier<CommandRegistry> registrySupplier) {
        super("help", "Display help information about available commands");
        this.registrySupplier = Objects.requireNonNull(registrySupplier, "Registry supplier cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final CommandRegistry registry = registrySupplier.get();
        if (registry == null) {
            return CommandExecutionResult.failure("Command registry not initialized",
                    new IllegalStateException("Command registry not set"));
        }

        final String arguments = request.getArguments().orElse("");
        final String trimmedArgs = arguments.trim();

        if (trimmedArgs.isEmpty()) {
            // Show all commands
            return CommandExecutionResult.success(generateAllCommandsHelp());
        } else {
            // Show help for specific command
            final String commandName = trimmedArgs.split("\\s+")[0];
            return showCommandHelp(commandName);
        }
    }

    private String generateAllCommandsHelp() {
        final CommandRegistry registry = registrySupplier.get();
        final StringBuilder help = new StringBuilder();
        help.append("Available commands:").append(Constants.DOUBLE_NEWLINE);

        // System commands
        final List<Command> systemCommands = registry.getSystemCommands();
        if (!systemCommands.isEmpty()) {
            help.append("System commands:").append(Constants.NEWLINE);
            for (Command cmd : systemCommands) {
                help.append(String.format("  /%s - %s%s", cmd.getName(), cmd.getMetadata().getDescription().orElse(""),
                        Constants.NEWLINE));
            }
            help.append(Constants.NEWLINE);
        }

        // Skill-backed commands
        final List<Command> skillCommands = registry.getSkillBackedCommands();
        if (!skillCommands.isEmpty()) {
            help.append("Skill commands:").append(Constants.NEWLINE);
            for (Command cmd : skillCommands) {
                help.append(String.format("  /%s - %s%s", cmd.getName(), cmd.getMetadata().getDescription().orElse(""),
                        Constants.NEWLINE));
            }
            help.append(Constants.NEWLINE);
        }

        help.append("Use '/help [command]' for detailed information about a specific command.");

        return help.toString();
    }

    private CommandExecutionResult showCommandHelp(String commandName) {
        final CommandRegistry registry = registrySupplier.get();
        final Optional<Command> commandOpt = registry.getCommand(commandName);

        if (commandOpt.isEmpty()) {
            return CommandExecutionResult.failure("Command not found: " + commandName,
                    new IllegalArgumentException("Command not found: " + commandName));
        }

        final Command command = commandOpt.get();
        final StringBuilder help = new StringBuilder();

        help.append(String.format("Command: /%s%s", command.getName(), Constants.DOUBLE_NEWLINE));
        help.append(String.format("Description: %s%s", command.getMetadata().getDescription().orElse(""),
                Constants.DOUBLE_NEWLINE));
        help.append(String.format("Type: %s%s", command.getType(), Constants.NEWLINE));

        // Show LlmExecutable-specific details
        if (command instanceof LlmExecutable llmExecutable) {
            if (llmExecutable.hasPermissionRestrictions()) {
                help.append(Constants.NEWLINE).append("Allowed tools:").append(Constants.NEWLINE);
                llmExecutable.getAllowedTools()
                        .forEach(tool -> help.append(String.format("  - %s%s", tool.getPattern(), Constants.NEWLINE)));
            }

            final String content = llmExecutable.getContent().getRawContent();
            if (!content.isEmpty()) {
                help.append(Constants.NEWLINE).append("Details:").append(Constants.NEWLINE);
                help.append(content);
            }
        }

        return CommandExecutionResult.success(help.toString());
    }
}
