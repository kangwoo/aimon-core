package at.aimon.core.tools.code;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;

/**
 * Registers {@link ExecuteCodeTool} in an agent runtime's registry
 * ({@code docs/design/tool/programmatic-tool-calling.md}
 * §9). The engines are not tool providers: this provider receives them, application-scoped, from the assembly (the CLI,
 * {@code aimon-bootstrap}) and hands them to the tool.
 *
 * <p>
 * An agent that opens tools to code ({@code programmatic-tools}) in a runtime with no engine gets no
 * {@code ExecuteCode}, and a warning says why (§12.2).
 */
public final class ExecuteCodeToolProvider implements OrcaToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ExecuteCodeToolProvider.class);

    /** Agents already warned about, so the warning is logged once per agent. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final List<CodeExecutionEngine> engines;
    private final CodeExecutionLimits limits;

    /**
     * @param engines
     *            the assembled engines (must not be null); owned by the caller, closed at shutdown
     * @param limits
     *            the limits every program is held to (must not be null)
     */
    public ExecuteCodeToolProvider(List<CodeExecutionEngine> engines, CodeExecutionLimits limits) {
        this.engines = List.copyOf(Objects.requireNonNull(engines, "engines cannot be null"));
        this.limits = Objects.requireNonNull(limits, "limits cannot be null");
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        final Agent agent = context.getAgent();
        if (engines.isEmpty()) {
            // Once per agent: registerTools runs on every runtime creation, evictions and rebuilds included.
            if (agent != null && agent.getMetadata().getProgrammatic().isEnabled() && WARNED.add(agent.getName())) {
                log.warn("Agent '{}' sets programmatic-tools, but no code execution engine is assembled "
                        + "(CLI programmatic.enableJs, ToolSpec.programmaticEnabled, starter "
                        + "aimon.programmatic.enabled); " + "ExecuteCode is not registered", agent.getName());
            }
            return;
        }
        registry.register(new ExecuteCodeTool(engines, limits));
    }
}
