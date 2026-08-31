package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.shell.VirtualShell;
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
 * The {@link VirtualShell} that actually runs commands comes from the context, not from here. A provider cannot build
 * one: {@code at.aimon.core.shell.impl} is reachable only from the shell tree and the in-core assembler package, a
 * boundary ArchUnit enforces. That is also what lets an assembly point the agent at a sandboxed (Docker/Kubernetes)
 * shell without this class knowing which one it got.
 *
 * @see OrcaToolProvider
 */
public class OrcaBashToolProvider implements OrcaToolProvider {

    private static final Logger log = LoggerFactory.getLogger(OrcaBashToolProvider.class);

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final VirtualShell shell = context.getShell();
        if (shell == null) {
            // Register nothing rather than fail the whole assembly: an agent without a shell is a usable agent, but
            // a BashTool with no way to run commands is not. Logged at WARN because the absence is almost always an
            // assembly oversight — the runtime factory supplies a default shell unless one was explicitly withheld.
            log.warn("No VirtualShell available; Bash and BashOutput tools will not be registered");
            return;
        }

        final BackgroundBashManager backgroundManager = new BackgroundBashManager();
        registry.register(new BashTool(shell, backgroundManager));
        registry.register(new BashOutputTool(backgroundManager));
    }
}
