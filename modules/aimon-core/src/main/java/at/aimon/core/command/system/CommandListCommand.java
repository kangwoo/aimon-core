package at.aimon.core.command.system;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.Constants;
import at.aimon.core.command.Command;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;

/**
 * Built-in command that displays all registered commands.
 *
 * <p>
 * Lists system commands and user-invocable skill commands with their descriptions. Legacy
 * {@code .aimon/commands/*.md} entries are no longer supported (removed in SK-08-F); the registry rejects them at
 * startup.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /commands           - List all registered commands
 * </pre>
 *
 * <p>
 * This command executes directly without LLM interaction via {@link DirectExecutable}.
 */
public final class CommandListCommand extends SystemCommand implements DirectExecutable {

    private final CommandRegistry commandRegistry;

    /**
     * Creates a new CommandListCommand.
     *
     * @param commandRegistry
     *            The command registry to list commands from (must not be null)
     * @throws NullPointerException
     *             if commandRegistry is null
     */
    public CommandListCommand(CommandRegistry commandRegistry) {
        super("commands", "Display all registered commands");
        this.commandRegistry = Objects.requireNonNull(commandRegistry, "Command registry cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final List<Command> systemCommands = commandRegistry.getSystemCommands();
        final List<Command> skillCommands = commandRegistry.getSkillBackedCommands();

        final StringBuilder output = new StringBuilder();
        output.append("Registered commands:").append(Constants.DOUBLE_NEWLINE);

        if (systemCommands.isEmpty() && skillCommands.isEmpty()) {
            output.append("No commands registered.");
        } else {
            appendCommands(output, "System commands:", systemCommands);
            appendCommands(output, "Skill commands:", skillCommands);
            final int total = systemCommands.size() + skillCommands.size();
            output.append(String.format("Total: %d command(s)", total));
        }

        return CommandExecutionResult.success(output.toString());
    }

    private static void appendCommands(StringBuilder output, String header, List<Command> commands) {
        if (commands.isEmpty()) {
            return;
        }
        output.append(header).append(Constants.NEWLINE);
        for (Command cmd : commands) {
            output.append(String.format("  /%s - %s%s", cmd.getName(),
                    cmd.getMetadata().getDescription().orElse("No description"), Constants.NEWLINE));
        }
        output.append(Constants.NEWLINE);
    }
}
