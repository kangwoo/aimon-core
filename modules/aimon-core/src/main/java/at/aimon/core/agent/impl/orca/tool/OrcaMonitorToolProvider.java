package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.tools.monitor.MonitorManager;
import at.aimon.core.tools.monitor.MonitorStopTool;
import at.aimon.core.tools.monitor.MonitorTool;

/**
 * Registers {@link MonitorTool} and {@link MonitorStopTool} over one caller-owned {@link MonitorManager}.
 *
 * <p>
 * Unlike {@link OrcaBashToolProvider} there is no per-registry fallback: a monitor delivers to the stack's message
 * queue, which only the assembly holds, and it must outlive the runtime that started it. The manager is
 * application-scoped and the caller closes it.
 *
 * @see OrcaToolProvider
 */
public class OrcaMonitorToolProvider implements OrcaToolProvider {

    private final MonitorManager manager;

    /**
     * Creates the provider.
     *
     * @param manager
     *            the application's monitor manager; the caller closes it (must not be null)
     */
    public OrcaMonitorToolProvider(MonitorManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");
        registry.register(new MonitorTool(manager));
        registry.register(new MonitorStopTool(manager));
    }
}
