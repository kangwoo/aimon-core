package at.aimon.core.command.system;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.Constants;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * Built-in command that displays all registered subagents.
 *
 * <p>
 * Lists subagents with their descriptions and metadata (model, max iterations).
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /agents             - List all registered subagents
 * </pre>
 *
 * <p>
 * This command executes directly without LLM interaction via {@link DirectExecutable}.
 */
public final class AgentListCommand extends SystemCommand implements DirectExecutable {
    private final SubagentRegistry subagentRegistry;

    /**
     * Creates a new AgentListCommand.
     *
     * @param subagentRegistry
     *            The subagent registry to list agents from (must not be null)
     * @throws NullPointerException
     *             if subagentRegistry is null
     */
    public AgentListCommand(SubagentRegistry subagentRegistry) {
        super("agents", "Display all registered subagents");
        this.subagentRegistry = Objects.requireNonNull(subagentRegistry, "Subagent registry cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final List<Subagent> subagents = subagentRegistry.getAllSubagents();

        final StringBuilder output = new StringBuilder();
        output.append("Registered subagents:").append(Constants.DOUBLE_NEWLINE);

        if (subagents.isEmpty()) {
            output.append("No subagents registered.");
        } else {
            for (Subagent subagent : subagents) {
                final SubagentMetadata metadata = subagent.getMetadata();
                output.append(String.format("  %s", subagent.getName()));
                if (metadata.getDescription() != null && !metadata.getDescription().isEmpty()) {
                    output.append(String.format(" - %s", metadata.getDescription()));
                }
                output.append(Constants.NEWLINE);

                if (metadata.getModel() != null && !metadata.getModel().isEmpty()) {
                    output.append(String.format("    model: %s", metadata.getModel()));
                    output.append(Constants.NEWLINE);
                }
            }
            output.append(Constants.NEWLINE);
            output.append(String.format("Total: %d subagent(s)", subagents.size()));
        }

        return CommandExecutionResult.success(output.toString());
    }
}
