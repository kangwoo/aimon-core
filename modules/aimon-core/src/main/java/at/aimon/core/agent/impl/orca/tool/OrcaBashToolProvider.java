package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.tools.bash.BackgroundBashManager;
import at.aimon.core.tools.bash.BashOutputTool;
import at.aimon.core.tools.bash.BashTool;
import at.aimon.core.tools.bash.KillShellTool;

/**
 * Provides bash execution tools to the Orca agent system.
 *
 * <p>
 * This provider registers bash-related tools including:
 *
 * <ul>
 * <li>{@link BashTool} - Execute bash commands with optional background execution
 * <li>{@link BashOutputTool} - Monitor and retrieve output from background bash processes
 * <li>{@link KillShellTool} - Stop a background bash process
 * </ul>
 *
 * <p>
 * The three tools share a common {@link BackgroundBashManager} instance to coordinate background process execution.
 * Which one decides how long a background task is remembered: {@link #OrcaBashToolProvider(BackgroundBashManager)}
 * registers the tools over a manager the caller owns — the bootstrap stack passes its application-scoped one, so a
 * task outlives the runtime that started it — while {@link #OrcaBashToolProvider()} gives each tool registry a manager
 * of its own, which nobody closes and which is forgotten with the registry.
 *
 * <p>
 * No shell is involved here. {@code Bash} runs each command in the shell of the execution's environment
 * ({@code ToolContextKeys.EXECUTION_ENVIRONMENT}), read on every call, and a background command keeps the shell it
 * started in (execution-environment design §5.3). So the tools are always registered: an execution whose environment
 * has no usable shell gets an error from the call, not a missing tool.
 *
 * @see OrcaToolProvider
 */
public class OrcaBashToolProvider implements OrcaToolProvider {

    private final BackgroundBashManager sharedManager;

    /** Creates a provider that gives every tool registry its own {@link BackgroundBashManager}. */
    public OrcaBashToolProvider() {
        this.sharedManager = null;
    }

    /**
     * Creates a provider whose tools all use one caller-owned manager, in every registry it registers into.
     *
     * @param backgroundManager
     *            the manager; the caller closes it (must not be null)
     */
    public OrcaBashToolProvider(BackgroundBashManager backgroundManager) {
        this.sharedManager = Objects.requireNonNull(backgroundManager, "backgroundManager must not be null");
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final BackgroundBashManager backgroundManager = sharedManager != null
                ? sharedManager
                : new BackgroundBashManager();
        registry.register(new BashTool(backgroundManager));
        registry.register(new BashOutputTool(backgroundManager));
        registry.register(new KillShellTool(backgroundManager));
    }
}
