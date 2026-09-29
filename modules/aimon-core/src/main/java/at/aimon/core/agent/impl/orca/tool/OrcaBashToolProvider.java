package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.tools.bash.BackgroundBashManager;
import at.aimon.core.tools.bash.BashOutputTool;
import at.aimon.core.tools.bash.BashTool;

/**
 * Provides bash execution tools to the Orca agent system.
 *
 * <p>
 * This provider registers bash-related tools including:
 *
 * <ul>
 * <li>{@link BashTool} - Execute bash commands with optional background execution
 * <li>{@link BashOutputTool} - Monitor and retrieve output from background bash processes
 * </ul>
 *
 * <p>
 * Both tools share a common {@link BackgroundBashManager} instance to coordinate background process execution.
 *
 * <p>
 * No shell is involved here. {@code Bash} runs each command in the shell of the execution's environment
 * ({@code ToolContextKeys.EXECUTION_ENVIRONMENT}), read on every call, and a background command keeps the shell it
 * started in (execution-environment design §5.3). So both tools are always registered: an execution whose environment
 * has no usable shell gets an error from the call, not a missing tool.
 *
 * @see OrcaToolProvider
 */
public class OrcaBashToolProvider implements OrcaToolProvider {

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final BackgroundBashManager backgroundManager = new BackgroundBashManager();
        registry.register(new BashTool(backgroundManager));
        registry.register(new BashOutputTool(backgroundManager));
    }
}
