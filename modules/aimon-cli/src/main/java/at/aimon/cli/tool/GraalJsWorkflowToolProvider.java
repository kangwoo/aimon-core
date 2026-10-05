package at.aimon.cli.tool;

import java.util.Objects;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.workflow.graaljs.GraalJsEngineHolder;
import at.aimon.workflow.graaljs.GraalJsWorkflowTool;

/**
 * CLI-side {@link OrcaToolProvider} that registers the {@link GraalJsWorkflowTool} ({@code WorkflowJs}).
 *
 * <p>
 * This provider lives in the CLI assembly layer (not {@code aimon-core}) because {@code aimon-core} must never depend
 * on the {@code aimon-workflow-graaljs} implementation module (dependency-direction rule). It is the scripted mirror of
 * {@code OrcaSubagentToolProvider}'s opt-in {@code WorkflowTool} registration: it pulls the same collaborators (default
 * model, registries, execution manager, context enrichers) from the {@link OrcaToolProviderContext} and
 * adds the app-scoped {@link GraalJsEngineHolder} plus the per-context {@code WorkflowRunner} (for background mode).
 *
 * <p>
 * As an {@code OrcaToolProvider} SPI implementor, this class depends only on the neutral SPI surface
 * ({@code at.aimon.core.agent.orca..}) and core interfaces — never on {@code at.aimon.core.agent.impl..}.
 * {@code isolation:'worktree'} descriptors need no wiring here: an isolated step derives its branch from the calling
 * execution's environment ({@code ExecutionEnvironment.isolate}), and a run whose environment cannot isolate is
 * refused.
 *
 * <p>
 * The provider is only added to the tool-provider list when {@code cli.enableWorkflowJs} is set (see
 * {@code AgentSetupFactory}); the shared engine holder it carries is owned and closed by {@code AgentSetup}, never per
 * run.
 *
 * @see GraalJsWorkflowTool
 * @see OrcaToolProvider
 */
public final class GraalJsWorkflowToolProvider implements OrcaToolProvider {

    private final GraalJsEngineHolder engines;

    /**
     * @param engines
     *            the application-scoped shared GraalJS engine holder (must not be null); owned by the caller
     *            ({@code AgentSetup}) and closed at app shutdown
     * @throws NullPointerException
     *             if {@code engines} is null
     */
    public GraalJsWorkflowToolProvider(GraalJsEngineHolder engines) {
        this.engines = Objects.requireNonNull(engines, "engines must not be null");
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final Agent agent = context.getAgent();
        final SubagentRegistry subagentRegistry = context.getSubagentRegistry();
        final ToolRegistry toolRegistry = context.getToolRegistry();
        final HookRegistry hookRegistry = context.getHookRegistry();
        final SubagentExecutionManager subagentExecutionManager = context.getSubagentExecutionManager();

        Objects.requireNonNull(agent, "agent must not be null in context");
        Objects.requireNonNull(subagentRegistry, "subagentRegistry must not be null in context");
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null in context");
        Objects.requireNonNull(hookRegistry, "hookRegistry must not be null in context");
        Objects.requireNonNull(subagentExecutionManager, "subagentExecutionManager must not be null in context");

        final GraalJsWorkflowTool.Builder builder = GraalJsWorkflowTool.builder()
                .defaultModel(agent.getMetadata().getModel()).subagentRegistry(subagentRegistry)
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry)
                .subagentExecutionManager(subagentExecutionManager)
                .toolContextEnrichers(context.getToolContextEnrichers()).engines(engines)
                // Background mode reuses the same per-context runner as the Java WorkflowTool (null when neither
                // enableWorkflow nor enableWorkflowJs is set, in which case background mode returns a graceful error).
                .backgroundRunner(context.getWorkflowRunner());

        registry.register(builder.build());
    }
}
