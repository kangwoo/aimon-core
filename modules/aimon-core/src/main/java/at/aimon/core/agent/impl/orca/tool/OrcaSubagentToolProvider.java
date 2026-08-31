package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.task.SessionSnapshotStore;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.subagent.task.TaskResultStore;
import at.aimon.core.tools.task.AgentOutputTool;
import at.aimon.core.tools.task.TaskListTool;
import at.aimon.core.tools.task.TaskStopTool;
import at.aimon.core.tools.task.TaskTool;
import at.aimon.core.tools.workflow.WorkflowTool;

/**
 * Provides subagent and task management tools to the Orca agent system.
 *
 * <p>
 * This provider registers tools for hierarchical agent systems including:
 *
 * <ul>
 * <li>{@link TaskTool} - Launch and manage subagent execution
 * <li>{@link AgentOutputTool} - Retrieve output from background subagent tasks
 * <li>{@link TaskListTool} - List background subagent tasks and their state
 * <li>{@link TaskStopTool} - Stop (kill) a running background subagent task
 * <li>{@link WorkflowTool} - Multi-perspective workflow tool (opt-in; registered only when constructed
 * with {@code workflowToolEnabled = true})
 * </ul>
 *
 * @see OrcaToolProvider
 */
public class OrcaSubagentToolProvider implements OrcaToolProvider {

    /**
     * Whether to also register the experimental {@link WorkflowTool}. Opt-in (default false), since it adds a tool
     * to every agent's system prompt and each invocation fans out to several LLM sub-agents.
     */
    private final boolean workflowToolEnabled;

    /** Creates the provider with the {@link WorkflowTool} disabled (opt-in). */
    public OrcaSubagentToolProvider() {
        this(false);
    }

    /**
     * @param workflowToolEnabled
     *            whether to also register the experimental {@link WorkflowTool}
     */
    public OrcaSubagentToolProvider(boolean workflowToolEnabled) {
        this.workflowToolEnabled = workflowToolEnabled;
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final Agent agent = context.getAgent();
        final SubagentRegistry subagentRegistry = context.getSubagentRegistry();
        final ToolRegistry toolRegistry = context.getToolRegistry();
        final HookRegistry hookRegistry = context.getHookRegistry();
        final Environment environment = context.getEnvironment();
        final SubagentExecutionManager subagentExecutionManager = context.getSubagentExecutionManager();
        // Optional live-output store. When configured, background subagents record their progress log to it so the
        // AgentOutput tool can tail incrementally; both TaskTool and AgentOutputTool degrade gracefully when it's null.
        final TaskOutputStore taskOutputStore = context.getTaskOutputStore();
        // Optional session snapshot store. When configured, a finished subagent's transcript is persisted by
        // taskId so Task(resume=<taskId>) can continue it; TaskTool rejects resume gracefully when it's null.
        final SessionSnapshotStore sessionSnapshotStore = context.getSessionSnapshotStore();
        // Optional task result store. When configured, a background subagent's final result is persisted by taskId
        // before its terminal state transition, which is what lets AgentOutput serve it from the store — across
        // nodes and across restarts — instead of from a node-local future.
        final TaskResultStore taskResultStore = context.getTaskResultStore();

        Objects.requireNonNull(agent, "agent must not be null in context");
        Objects.requireNonNull(subagentRegistry, "subagentRegistry must not be null in context");
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null in context");
        Objects.requireNonNull(hookRegistry, "hookRegistry must not be null in context");
        Objects.requireNonNull(environment, "environment must not be null in context");
        Objects.requireNonNull(subagentExecutionManager, "subagentExecutionManager must not be null in context");

        // Forward the agent runtime's tool-context enrichers so subagent tools receive the same
        // module-supplied context keys as the main-agent tools.
        final TaskTool taskTool = new TaskTool(agent.getMetadata().getModel(), subagentRegistry, toolRegistry,
                hookRegistry, environment, subagentExecutionManager, context.getToolContextEnrichers(), taskOutputStore,
                sessionSnapshotStore, taskResultStore);
        registry.register(taskTool);

        // The execution manager is itself the SubagentTaskController: it supplies both the lifecycle state the tool
        // reads and the cross-agent isolation that confines retrieval to the calling agent's own tasks. Everything
        // the tool serves comes from a store keyed by taskId, so it never needs a node-local handle on the task.
        final AgentOutputTool agentOutputTool = new AgentOutputTool(subagentExecutionManager, taskOutputStore,
                taskResultStore);
        registry.register(agentOutputTool);

        // Control-plane tools: the execution manager is itself the SubagentTaskController, so listing/stopping
        // is governed by the same component that spawned the tasks.
        registry.register(new TaskListTool(subagentExecutionManager));
        registry.register(new TaskStopTool(subagentExecutionManager));

        // Workflow consumer (opt-in): a built-in multi-perspective workflow that fans the prompt out to
        // perspective sub-agents in parallel and synthesizes them, driven by the WorkflowRunner over the same
        // execution manager. Same collaborators as TaskTool (model, registries, environment, manager, enrichers).
        // Disabled by default because it adds a tool to every agent and each call spends several sub-agent LLM calls.
        if (workflowToolEnabled) {
            registry.register(new WorkflowTool(agent.getMetadata().getModel(), subagentRegistry, toolRegistry,
                    hookRegistry, environment, subagentExecutionManager, context.getToolContextEnrichers(),
                    context.getWorkflowRunner()));
        }
    }
}
