package at.aimon.core.command.system;

import java.util.Objects;

import at.aimon.core.agent.Constants;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;

/**
 * Built-in version command that displays application version information.
 *
 * <p>
 * The version command provides:
 *
 * <ul>
 * <li>Application version number
 * <li>Build date and time
 * <li>Runtime environment information (Java version)
 * </ul>
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /version           - Display version information
 * </pre>
 *
 * <p>
 * This command executes directly without LLM interaction via {@link DirectExecutable}. It's a read-only operation with
 * no tools restrictions.
 */
public final class VersionCommand extends SystemCommand implements DirectExecutable {
    private final String version;

    /**
     * Creates a new VersionCommand.
     *
     * @param version
     *            The application version string (must not be null)
     * @throws NullPointerException
     *             if version is null
     */
    public VersionCommand(String version) {
        super("version", "Display application version information");
        this.version = Objects.requireNonNull(version, "Version cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final String javaVersion = System.getProperty("java.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");

        final StringBuilder info = new StringBuilder();
        info.append("AIMon version: ").append(version).append(Constants.NEWLINE);
        info.append("Java version: ").append(javaVersion).append(Constants.NEWLINE);
        info.append("OS: ").append(osName).append(' ').append(osVersion);

        return CommandExecutionResult.success(info.toString());
    }
}
