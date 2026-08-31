package at.aimon.core.tools.task;

import static at.aimon.core.tools.ToolContextKeys.AGENT_RUNTIME_ID;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Constants;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.interrupt.Terminator;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.llm.DynamicToolDefinitionProvider;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.exception.SubagentNotFoundException;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentResultFormatter;
import at.aimon.core.subagent.task.ResumableSession;
import at.aimon.core.subagent.task.ScopedSessionSnapshotStore;
import at.aimon.core.subagent.task.SessionSnapshotStore;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.subagent.task.TaskResultStore;
import at.aimon.core.tools.InvokingSessionAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tool for launching specialized subagents to handle complex, multi-step tasks.
 *
 * <p>
 * The Task tools enables parallel execution of independent tasks by spawning specialized subagents. Each subagent type
 * has specific capabilities and tools available to it.
 *
 * <p>
 * Features:
 *
 * <ul>
 * <li>Multiple subagent types (general-purpose, Explore, Plan, etc.)
 * <li>Autonomous execution with clear prompts
 * <li>Model selection (sonnet, haiku, opus)
 * <li>Background execution support
 * <li>Resume capability for continuing a previous run
 * </ul>
 *
 * <p>
 * Thread-safe as long as SubagentRegistry and SubagentExecutor are thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SubagentRegistry registry = new DefaultSubagentRegistry(repository, parser);
 *     SubagentExecutionManager executionManager = new DefaultSubagentExecutionManager(llmClient, toolExecutionManager,
 *             hookExecutionManager);
 *     Tool taskTool = new TaskTool(defaultModel, registry, toolRegistry, hookRegistry, environment, executionManager);
 *
 *     ToolContext context = ToolContext.empty();
 *
 *     // Launch a subagent
 *     ToolInput input = ToolInput
 *             .of(Map.of("subagent_name", "Explore", "prompt", "Find all authentication-related files in the codebase",
 *                     "description", "Find auth files", "model", "haiku"));
 *     ToolResult result = taskTool.execute(input, context);
 * }
 * </pre>
 */
public class TaskTool extends AbstractTool {

    public static final String TOOL_NAME = "Task";

    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);

    private final LlmModel defaultModel;
    private final SubagentRegistry subagentRegistry;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final Environment environment;

    private final SubagentExecutionManager subagentExecutionManager;
    private final List<ToolContextEnricher> toolContextEnrichers;
    private final TaskOutputStore taskOutputStore;
    private final SessionSnapshotStore sessionSnapshotStore;
    private final TaskResultStore taskResultStore;

    /**
     * Creates a TaskTool without tool-context enrichers (backward-compatible overload).
     *
     * @param defaultModel
     *            the default model for spawned subagents (must not be null)
     * @param subagentRegistry
     *            the subagent registry (must not be null)
     * @param toolRegistry
     *            the tool registry available to subagents (must not be null)
     * @param hookRegistry
     *            the hook registry (must not be null)
     * @param environment
     *            the runtime environment (must not be null)
     * @param subagentExecutionManager
     *            the subagent execution manager (must not be null)
     */
    public TaskTool(LlmModel defaultModel, SubagentRegistry subagentRegistry, ToolRegistry toolRegistry,
            HookRegistry hookRegistry, Environment environment, SubagentExecutionManager subagentExecutionManager) {
        this(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment, subagentExecutionManager,
                List.of());
    }

    /**
     * Creates a TaskTool that forwards the supplied tool-context enrichers into spawned subagents so subagent tools
     * receive the same module-supplied context keys as the main-agent tools (backward-compatible overload without a
     * task output store).
     *
     * @param defaultModel
     *            the default model for spawned subagents (must not be null)
     * @param subagentRegistry
     *            the subagent registry (must not be null)
     * @param toolRegistry
     *            the tool registry available to subagents (must not be null)
     * @param hookRegistry
     *            the hook registry (must not be null)
     * @param environment
     *            the runtime environment (must not be null)
     * @param subagentExecutionManager
     *            the subagent execution manager (must not be null)
     * @param toolContextEnrichers
     *            the enrichers to forward (nullable; treated as empty when absent)
     */
    public TaskTool(LlmModel defaultModel, SubagentRegistry subagentRegistry, ToolRegistry toolRegistry,
            HookRegistry hookRegistry, Environment environment, SubagentExecutionManager subagentExecutionManager,
            List<ToolContextEnricher> toolContextEnrichers) {
        this(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment, subagentExecutionManager,
                toolContextEnrichers, null);
    }

    /**
     * Creates a TaskTool that additionally forwards a {@link TaskOutputStore} into background subagents so their live
     * progress log is recorded and can be tailed via the {@code AgentOutput} tool (backward-compatible overload without
     * a session snapshot store, so {@code resume} stays disabled).
     *
     * @param defaultModel
     *            the default model for spawned subagents (must not be null)
     * @param subagentRegistry
     *            the subagent registry (must not be null)
     * @param toolRegistry
     *            the tool registry available to subagents (must not be null)
     * @param hookRegistry
     *            the hook registry (must not be null)
     * @param environment
     *            the runtime environment (must not be null)
     * @param subagentExecutionManager
     *            the subagent execution manager (must not be null)
     * @param toolContextEnrichers
     *            the enrichers to forward (nullable; treated as empty when absent)
     * @param taskOutputStore
     *            the task output store for background live-output streaming (nullable; background tasks stream to a
     *            no-op sink when absent)
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public TaskTool(LlmModel defaultModel, SubagentRegistry subagentRegistry, ToolRegistry toolRegistry,
            HookRegistry hookRegistry, Environment environment, SubagentExecutionManager subagentExecutionManager,
            List<ToolContextEnricher> toolContextEnrichers, TaskOutputStore taskOutputStore) {
        this(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment, subagentExecutionManager,
                toolContextEnrichers, taskOutputStore, null);
    }

    /**
     * Creates a TaskTool that additionally forwards a {@link SessionSnapshotStore} so a finished subagent's
     * transcript is persisted by {@code taskId} and can be continued with {@code resume=<taskId>}.
     *
     * @param defaultModel
     *            the default model for spawned subagents (must not be null)
     * @param subagentRegistry
     *            the subagent registry (must not be null)
     * @param toolRegistry
     *            the tool registry available to subagents (must not be null)
     * @param hookRegistry
     *            the hook registry (must not be null)
     * @param environment
     *            the runtime environment (must not be null)
     * @param subagentExecutionManager
     *            the subagent execution manager (must not be null)
     * @param toolContextEnrichers
     *            the enrichers to forward (nullable; treated as empty when absent)
     * @param taskOutputStore
     *            the task output store for background live-output streaming (nullable; background tasks stream to a
     *            no-op sink when absent)
     * @param sessionSnapshotStore
     *            the session snapshot store enabling resume (nullable; when absent a {@code resume} request is
     *            rejected and no snapshot is persisted)
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public TaskTool(LlmModel defaultModel, SubagentRegistry subagentRegistry, ToolRegistry toolRegistry,
            HookRegistry hookRegistry, Environment environment, SubagentExecutionManager subagentExecutionManager,
            List<ToolContextEnricher> toolContextEnrichers, TaskOutputStore taskOutputStore,
            SessionSnapshotStore sessionSnapshotStore) {
        this(defaultModel, subagentRegistry, toolRegistry, hookRegistry, environment, subagentExecutionManager,
                toolContextEnrichers, taskOutputStore, sessionSnapshotStore, null);
    }

    /**
     * Creates a TaskTool that additionally forwards a {@link TaskResultStore}, so a background subagent's final result
     * is persisted by {@code taskId} and the {@code AgentOutput} tool can serve it from the store instead of from a
     * node-local future — which is what lets a task launched here be collected on another node, or after a restart.
     *
     * @param defaultModel
     *            the default model for spawned subagents (must not be null)
     * @param subagentRegistry
     *            the subagent registry (must not be null)
     * @param toolRegistry
     *            the tool registry available to subagents (must not be null)
     * @param hookRegistry
     *            the hook registry (must not be null)
     * @param environment
     *            the runtime environment (must not be null)
     * @param subagentExecutionManager
     *            the subagent execution manager (must not be null)
     * @param toolContextEnrichers
     *            the enrichers to forward (nullable; treated as empty when absent)
     * @param taskOutputStore
     *            the task output store for background live-output streaming (nullable; background tasks stream to a
     *            no-op sink when absent)
     * @param sessionSnapshotStore
     *            the session snapshot store enabling resume (nullable; when absent a {@code resume} request is
     *            rejected and no snapshot is persisted)
     * @param taskResultStore
     *            the task result store (nullable; when absent a finished background task keeps no retrievable result)
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public TaskTool(LlmModel defaultModel, SubagentRegistry subagentRegistry, ToolRegistry toolRegistry,
            HookRegistry hookRegistry, Environment environment, SubagentExecutionManager subagentExecutionManager,
            List<ToolContextEnricher> toolContextEnrichers, TaskOutputStore taskOutputStore,
            SessionSnapshotStore sessionSnapshotStore, TaskResultStore taskResultStore) {
        super(new DynamicToolDefinitionProvider(TOOL_NAME, ToolCategories.EXECUTION,
                () -> buildDescription(Objects.requireNonNull(subagentRegistry, "Subagent registry cannot be null")),
                createInputSchema()));
        this.defaultModel = Objects.requireNonNull(defaultModel, "Default model config cannot be null");
        this.subagentRegistry = Objects.requireNonNull(subagentRegistry, "Subagent registry cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "Tool registry bundle cannot be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "Hook registry cannot be null");
        this.environment = Objects.requireNonNull(environment, "Environment cannot be null");
        this.subagentExecutionManager = Objects.requireNonNull(subagentExecutionManager,
                "Subagent execution manager cannot be null");
        this.toolContextEnrichers = toolContextEnrichers != null ? List.copyOf(toolContextEnrichers) : List.of();
        this.taskOutputStore = taskOutputStore;
        this.sessionSnapshotStore = sessionSnapshotStore;
        this.taskResultStore = taskResultStore;
    }

    /**
     * Declares {@link InterruptBehavior#EXTERNALLY_TERMINATED} so the parent executor hands this tool a
     * {@link TerminatorRegistrar}. For the synchronous (foreground) launch path the tool registers a
     * {@code Thread.currentThread()::interrupt} terminator so a parent-initiated cancel breaks the in-flight subagent
     * call instead of waiting for it to finish. Cooperative cancellation is additionally wired by forwarding the
     * parent's {@link CancellationSignal} into the subagent (see {@link #execute(ToolInput, ToolContext)}).
     *
     * @return {@link InterruptBehavior#EXTERNALLY_TERMINATED}
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.EXTERNALLY_TERMINATED;
    }

    /**
     * Builds the tools description including available subagents.
     *
     * @param registry
     *            The subagent registry
     * @return The complete tools description with available subagents
     */
    private static String buildDescription(SubagentRegistry registry) {
        Objects.requireNonNull(registry, "Subagent registry cannot be null");

        final StringBuilder desc = new StringBuilder();
        desc.append("Launch specialized subagents that autonomously handle complex, multi-step tasks. ");
        desc.append("Each subagent has specific capabilities and tools. ");
        desc.append("The subagent will work independently and return results when complete.")
                .append(Constants.DOUBLE_NEWLINE);

        final List<Subagent> subagents = registry.getAllSubagents();
        if (!subagents.isEmpty()) {
            desc.append("<available_subagents>").append(Constants.NEWLINE);
            for (Subagent subagent : subagents) {
                desc.append("- ").append(subagent.getName()).append(": ")
                        .append(subagent.getMetadata().getDescription());
                final String whenToUse = subagent.getMetadata().getWhenToUse();
                if (whenToUse != null && !whenToUse.isBlank()) {
                    desc.append(" (Use when: ").append(whenToUse).append(")");
                }
                desc.append(Constants.NEWLINE);
            }
            desc.append("</available_subagents>").append(Constants.NEWLINE);
        } else {
            desc.append("(No subagents currently available)");
        }

        return desc.toString();
    }

    /**
     * Creates the JSON Schema for task tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("subagent_name",
                Map.of("type", "string", "description",
                        "The name of the subagent to use (e.g., 'code-reviewer', 'Explore', 'Plan')"),
                "prompt",
                Map.of("type", "string", "description", "The task for the agent to perform. Be clear and detailed."),
                "description",
                Map.of("type", "string", "description", "A short (3-5 word) description of the task for tracking"),
                "model",
                Map.of("type", "string", "description",
                        "Optional model to use (sonnet, gpt-4.1, gpt-4.1-nano). Prefer gpt-4.1-nano for simple tasks."),
                "run_in_background",
                Map.of("type", "boolean", "description",
                        "Set to true to run this agent in the background. Use AgentOutput tools to read output later."),
                "resume",
                Map.of("type", "string", "description",
                        "Optional task ID to resume from. If provided, continues from previous execution.")),
                "required", List.of("subagent_name", "prompt", "description"));
    }

    /**
     * Executes the task tools to launch a subagent.
     *
     * <p>
     * The method performs the following operations:
     *
     * <ol>
     * <li>Validates required parameters (subagent_name, prompt, description)
     * <li>Looks up the subagent in the registry
     * <li>Generates unique task ID
     * <li>Builds execution request
     * <li>Executes subagent through executor
     * <li>Formats and returns result
     * </ol>
     *
     * @param input
     *            The input parameters containing subagent parameters
     * @param context
     *            The execution context (currently unused)
     * @return A success result with subagent output if successful, or an error result if the subagent cannot be found
     *         or execution fails
     * @throws NullPointerException
     *             if input or context is null
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // Extract required parameters
            final String subagentName = input.getRequiredString("subagent_name");
            final String prompt = input.getRequiredString("prompt");
            final String description = input.getRequiredString("description");

            // Extract optional model override. When supplied it wins over the subagent's own `model` frontmatter
            // and the default model during resolution. A present-but-blank value is a caller error.
            final String model = input.getString("model", null);
            if (model != null && model.isBlank()) {
                return ToolResult.error("Invalid parameter: model must not be blank when provided");
            }

            // Extract optional run_in_background parameter
            final boolean runInBackground = input.getBoolean("run_in_background", false);

            // Resolve the calling agent's runtime id once. It scopes the resume load below and, further down,
            // stamps the spawned subagent's environment (its absence there is a hard error on the Orca path).
            final Optional<AgentRuntimeId> maybeAgentRuntimeId = context.get(AGENT_RUNTIME_ID);

            // Extract optional resume parameter. When present, load the prior run's session snapshot so the
            // subagent continues from its previous transcript instead of starting fresh. A present-but-blank value
            // is a caller error; resume requires a configured snapshot store; an unknown id has no resumable
            // transcript.
            final String resumeAgentId = input.getString("resume", null);
            SessionSnapshot previousSnapshot = null;
            if (resumeAgentId != null) {
                if (resumeAgentId.isBlank()) {
                    return ToolResult.error("Invalid parameter: resume must not be blank when provided");
                }
                if (sessionSnapshotStore == null) {
                    return ToolResult.error("Resume is not available: no session snapshot store is configured.");
                }
                // Confine the load to the caller's own runtime: background task ids are globally unique but
                // shared across agents in one snapshot store, so without scoping any agent could resume — and thereby
                // read — another agent's transcript just by knowing its id. A foreign-context or untagged transcript
                // loads as empty, indistinguishable from an unknown id. Non-Orca paths without a context id pass
                // through
                // unscoped (see ScopedSessionSnapshotStore#scopeOrPassThrough).
                final SessionSnapshotStore scopedStore = ScopedSessionSnapshotStore
                        .scopeOrPassThrough(sessionSnapshotStore, maybeAgentRuntimeId);
                final ResumableSession resumable = scopedStore.load(resumeAgentId).orElse(null);
                if (resumable == null) {
                    return ToolResult.error("No resumable task found for id: " + resumeAgentId);
                }
                // The resumed transcript is replayed under the REQUESTED subagent's system prompt and tool allowlist,
                // so resuming a different subagent's transcript would leave the history referencing tools this subagent
                // cannot use. Reject the mismatch rather than silently grafting a foreign session's transcript.
                if (!resumable.getSubagentName().equals(subagentName)) {
                    return ToolResult.error("Resume id '" + resumeAgentId + "' belongs to subagent '"
                            + resumable.getSubagentName() + "', not '" + subagentName + "'.");
                }
                previousSnapshot = resumable.getSnapshot();
            }

            // Require the agent runtime id to spawn the subagent (resolved above; scopes resume + stamps the env).
            final AgentRuntimeId agentRuntimeId = maybeAgentRuntimeId
                    .orElseThrow(() -> new IllegalStateException("Agent runtime ID not found in tool context"));

            // Build execution environment once for reuse
            final SubagentExecutionEnvironment env = buildEnvironment(context, agentRuntimeId, model, previousSnapshot);

            // Generate unique task ID
            final String taskId = UUID.randomUUID().toString();

            if (runInBackground) {
                // Launch subagent in background. The cancellation signal carried on env still cooperatively stops the
                // background subagent while the parent execution is alive; once that execution ends, cancellation of
                // the background task is owned by the execution manager's TaskStop control plane.
                final CompletableFuture<SubagentExecutionResult> future = subagentExecutionManager
                        .executeInBackground(env, taskId, subagentName, prompt, description);

                return ToolResult.success(String.format(
                        "Background task launched successfully.\n" + "Task ID: %s\n" + "Subagent: %s\n" + "Task: %s\n\n"
                                + "Use the " + AgentOutputTool.TOOL_NAME
                                + " tools with taskId='%s' to retrieve results.",
                        taskId, subagentName, description, taskId));
            }

            // Synchronous (foreground) launch: the subagent runs on this tool's thread. Register a thread-interrupt
            // terminator on the parent-issued registrar (EXTERNALLY_TERMINATED) so a parent cancel can break a blocking
            // subagent call out-of-band, in addition to the cooperative signal carried on env.
            final SubagentExecutionResult result = runForeground(context, env, taskId, subagentName, prompt,
                    description);

            // Format result
            final var formattedResult = formatSubagentResult(result, subagentName, description);

            return ToolResult.success(formattedResult);

        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (SubagentNotFoundException e) {
            return ToolResult.error("Subagent not found: " + e.getSubagentName() + ". " + "Available subagents: "
                    + String.join(", ", getAvailableSubagentNames()));
        } catch (Exception e) {
            return ToolResult.error("Task execution failed: " + e.getMessage());
        }
    }

    /**
     * Collects everything the spawned subagent inherits from the calling execution into one environment.
     *
     * <p>
     * Every value read here is optional: {@code TaskTool} is reachable from non-Orca paths that populate little or
     * nothing in the {@link ToolContext}, and the subagent must still run. Absence therefore degrades a capability
     * rather than failing the call.
     *
     * @param context
     *            the calling tool context (must not be null)
     * @param agentRuntimeId
     *            the calling agent's runtime id (must not be null)
     * @param model
     *            the caller's model override, or null to use the subagent's own frontmatter
     * @param previousSnapshot
     *            the transcript to resume from, or null for a fresh run
     * @return the environment to launch the subagent with
     */
    private SubagentExecutionEnvironment buildEnvironment(ToolContext context, AgentRuntimeId agentRuntimeId,
            String model, SessionSnapshot previousSnapshot) {

        // Extract execution attributes from tool context for subagent propagation
        final Map<String, Object> executionAttributes = context.get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY)
                .orElse(Map.of());

        // Forward the parent execution's LLM call metadata so subagent usage stays correlated with the parent
        // request (traceId, principal, tenant tags, ...). The subagent executor will override component/feature.
        // If the key is absent, fall back to empty metadata — this happens when TaskTool is invoked from a
        // non-Orca execution path that does not populate ToolContext; log at DEBUG to make the silent fallback
        // observable during integration testing without flooding normal runs.
        final LlmCallMetadata parentMetadata = context.get(ToolContextKeys.LLM_CALL_METADATA_KEY).orElseGet(() -> {
            log.debug("LLM_CALL_METADATA_KEY not present in ToolContext; subagent usage will lack parent "
                    + "traceId/principal. Caller wiring may be incomplete.");
            return LlmCallMetadata.empty();
        });

        // Forward the parent execution's cancellation signal so a parent-initiated cancel cascades into the
        // subagent's ReAct loop and its cooperative tools. The parent is a turn only on the session path: a nested
        // Task call inside a fork reads that fork's coordinator instead (DefaultSubagentExecutor). Absent in non-Orca
        // call paths (e.g. unit tests) — defaults to the never-cancelled NoopCancellationSignal.
        final CancellationSignal parentSignal = context.get(InterruptToolKeys.CANCELLATION_SIGNAL)
                .orElse(NoopCancellationSignal.INSTANCE);

        // Forward the parent's principal (caller identity) so subagent tools observe the same PRINCIPAL key as the
        // main-agent tools. Absent on non-Orca call paths — the subagent simply sees no principal.
        final Principal principal = context.get(ToolContextKeys.PRINCIPAL).orElse(null);

        // Forward the parent's knowledge store/scope (already present in this tool context when a knowledge base is
        // configured) so subagent tools can search the same knowledge base under the same scope.
        final KnowledgeStore knowledgeStore = context.get(ToolContextKeys.KNOWLEDGE_STORE).orElse(null);
        final KnowledgeScope knowledgeScope = context.get(ToolContextKeys.KNOWLEDGE_SCOPE).orElse(null);

        // Forward the parent's message queue and event sink so a BACKGROUND subagent completion can notify the
        // launching agent — a guaranteed <task-notification> pushed onto the queue plus a best-effort
        // SubagentTaskCompleted stream event. Absent on non-Orca call paths; the background completion path then
        // simply skips notification (the model still learns the result by polling with AgentOutput).
        final MessageQueueManager messageQueueManager = context.get(ToolContextKeys.MESSAGE_QUEUE_MANAGER).orElse(null);
        final Consumer<AgentExecutionEvent> parentEventSink = context.get(ToolContextKeys.AGENT_EVENT_SINK)
                .orElse(null);

        return SubagentExecutionEnvironment.builder().agentRuntimeId(agentRuntimeId).subagentRegistry(subagentRegistry)
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).environment(environment)
                .defaultModel(defaultModel).modelOverride(model).executionAttributes(executionAttributes)
                .parentLlmCallMetadata(parentMetadata).cancellationSignal(parentSignal).principal(principal)
                .knowledgeStore(knowledgeStore).knowledgeScope(knowledgeScope)
                .toolContextEnrichers(toolContextEnrichers).taskOutputStore(taskOutputStore)
                .taskResultStore(taskResultStore).sessionSnapshotStore(sessionSnapshotStore)
                .previousSnapshot(previousSnapshot).messageQueueManager(messageQueueManager)
                .parentEventSink(parentEventSink)
                .invokingSessionId(InvokingSessionAccess.idToPropagate(context).orElse(null)).build();
    }

    /**
     * Runs the subagent synchronously on the calling tool thread, registering a thread-interrupt terminator on the
     * parent-issued {@link TerminatorRegistrar} when present so a parent cancel can break a blocking subagent call.
     *
     * <p>
     * The terminator is unregistered in a finally block so it cannot leak into a later tool invocation should the
     * parent re-use the registrar. When no registrar is present (non-Orca call paths), the subagent simply runs to
     * completion and cancellation falls back to the cooperative {@code parentSignal} forwarded into {@code env}.
     *
     * @param context
     *            the tool context (used to read the optional {@link InterruptToolKeys#TERMINATOR_REGISTRAR})
     * @param env
     *            the subagent execution environment
     * @param taskId
     *            the generated task id
     * @param subagentName
     *            the subagent name
     * @param prompt
     *            the subagent goal/prompt
     * @param description
     *            the short task description
     * @return the subagent execution result (never null)
     */
    private SubagentExecutionResult runForeground(ToolContext context, SubagentExecutionEnvironment env, String taskId,
            String subagentName, String prompt, String description) {
        final TerminatorRegistrar registrar = context.get(InterruptToolKeys.TERMINATOR_REGISTRAR).orElse(null);
        if (registrar == null) {
            return subagentExecutionManager.execute(env, taskId, subagentName, prompt, description);
        }
        final Thread toolThread = Thread.currentThread();
        final Terminator terminator = toolThread::interrupt;
        registrar.register(terminator);
        try {
            return subagentExecutionManager.execute(env, taskId, subagentName, prompt, description);
        } finally {
            registrar.unregister(terminator);
        }
    }

    /**
     * Formats the subagent execution result for display.
     *
     * @param result
     *            The subagent execution result
     * @param subagentName
     *            The subagent name
     * @param description
     *            The task description
     * @return A formatted string representation
     */
    private String formatSubagentResult(SubagentExecutionResult result, String subagentName, String description) {
        final StringBuilder output = new StringBuilder();

        output.append("=== Subagent Task Result ===").append(Constants.NEWLINE);
        output.append("Subagent: ").append(subagentName).append(Constants.NEWLINE);
        output.append("Task: ").append(description).append(Constants.NEWLINE);
        output.append("Status: ").append(result.getStatus()).append(Constants.NEWLINE);
        output.append("Iterations: ").append(result.getIterationCount()).append(Constants.NEWLINE);
        output.append("Tokens: ").append(result.getMetadata().getTokenUsage().getTotalTokens())
                .append(Constants.DOUBLE_NEWLINE);

        // Bound the (potentially large) subagent answer so it does not overrun the parent context. Foreground
        // results are inlined here rather than recorded in a store, so the truncation marker carries no retrieval
        // pointer.
        output.append("Result:").append(Constants.NEWLINE);
        output.append(SubagentResultFormatter.truncateTailKeep(result.getSummary(), null)).append(Constants.NEWLINE);

        return output.toString();
    }

    /**
     * Gets the list of available subagent names.
     *
     * @return A list of subagent names
     */
    private List<String> getAvailableSubagentNames() {
        return subagentRegistry.getAllSubagents().stream().map(Subagent::getName).toList();
    }
}
