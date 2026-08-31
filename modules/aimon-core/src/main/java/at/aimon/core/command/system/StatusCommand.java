package at.aimon.core.command.system;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.Constants;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.status.StatusEntry;
import at.aimon.core.status.StatusSection;
import at.aimon.core.status.SystemStatus;
import at.aimon.core.status.SystemStatusProvider;

/**
 * Built-in command that displays the overall system status.
 *
 * <p>
 * Delegates status collection to a {@link SystemStatusProvider}, keeping the rendering logic separate from the data
 * gathering logic. This allows the same provider to be used outside of the command system (e.g. health checks, APIs).
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /status             - Display system status
 * </pre>
 *
 * <p>
 * This command executes directly without LLM interaction via {@link DirectExecutable}.
 */
public final class StatusCommand extends SystemCommand implements DirectExecutable {
    private final SystemStatusProvider statusProvider;

    /**
     * Creates a new StatusCommand.
     *
     * @param statusProvider
     *            The provider for system status information (must not be null)
     * @throws NullPointerException
     *             if statusProvider is null
     */
    public StatusCommand(SystemStatusProvider statusProvider) {
        super("status", "Display system status information");
        this.statusProvider = Objects.requireNonNull(statusProvider, "Status provider cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final SystemStatus status;
        try {
            status = Objects.requireNonNull(statusProvider.getStatus(), "StatusProvider returned null");
        } catch (Exception e) {
            return CommandExecutionResult.success(
                    "System Status:" + Constants.DOUBLE_NEWLINE + "Failed to retrieve status: " + e.getMessage());
        }

        final List<StatusSection> sections = status.getSections();

        final StringBuilder output = new StringBuilder();
        output.append("System Status:").append(Constants.DOUBLE_NEWLINE);

        if (sections.isEmpty()) {
            output.append("No status information available.");
        } else {
            for (int i = 0; i < sections.size(); i++) {
                final StatusSection section = sections.get(i);
                output.append(section.getTitle()).append(':').append(Constants.NEWLINE);

                for (StatusEntry entry : section.getEntries()) {
                    output.append(String.format("  %s: %s", entry.getName(), entry.getValue()));
                    output.append(Constants.NEWLINE);
                }

                if (i < sections.size() - 1) {
                    output.append(Constants.NEWLINE);
                }
            }
        }

        return CommandExecutionResult.success(output.toString());
    }
}
