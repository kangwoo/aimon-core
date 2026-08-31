package at.aimon.core.subagent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.task.SessionSnapshotStore;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.subagent.task.TaskResultStore;

/**
 * Groups the common environment parameters needed for subagent execution.
 *
 * <p>
 * This class encapsulates the shared execution environment that is passed to {@link SubagentExecutionManager} methods,
 * reducing parameter count and improving readability.
 *
 * <p>
 * Contains:
 *
 * <ul>
 * <li>Agent runtime ID for tracking the parent runtime
 * <li>Registries for subagents, tools, and hooks
 * <li>Runtime environment
 * <li>Default LLM model configuration
 * <li>Execution attributes for propagation
 * </ul>
 *
 * <p>
 * Immutable value object. Use builder to create instances.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder().agentRuntimeId(agentRuntimeId)
 *             .subagentRegistry(subagentRegistry).toolRegistry(toolRegistry).hookRegistry(hookRegistry)
 *             .environment(environment).defaultModel(defaultModel).executionAttributes(attributes).build();
 * }
 * </pre>
 */
public final class SubagentExecutionEnvironment {
    /**
     * Creates a new builder.
     *
     * @return A new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final AgentRuntimeId agentRuntimeId;
    private final SubagentRegistry subagentRegistry;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final LlmModel defaultModel;
    private final String modelOverride;
    private final Map<String, Object> executionAttributes;
    private final LlmCallMetadata parentLlmCallMetadata;
    private final CancellationSignal cancellationSignal;
    private final Principal principal;
    private final SessionId invokingSessionId;
    private final KnowledgeStore knowledgeStore;
    private final KnowledgeScope knowledgeScope;
    private final List<ToolContextEnricher> toolContextEnrichers;
    private final TaskOutputStore taskOutputStore;
    private final TaskResultStore taskResultStore;
    private final SessionSnapshotStore sessionSnapshotStore;
    private final SessionSnapshot previousSnapshot;
    private final MessageQueueManager messageQueueManager;
    private final Consumer<AgentExecutionEvent> parentEventSink;

    private SubagentExecutionEnvironment(Builder builder) {
        agentRuntimeId = Objects.requireNonNull(builder.agentRuntimeId, "Agent runtime ID cannot be null");
        subagentRegistry = Objects.requireNonNull(builder.subagentRegistry, "Subagent registry cannot be null");
        toolRegistry = Objects.requireNonNull(builder.toolRegistry, "Tool registry cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        defaultModel = Objects.requireNonNull(builder.defaultModel, "Default model cannot be null");
        modelOverride = builder.modelOverride;
        executionAttributes = builder.executionAttributes != null ? Map.copyOf(builder.executionAttributes) : Map.of();
        parentLlmCallMetadata = builder.parentLlmCallMetadata != null
                ? builder.parentLlmCallMetadata
                : LlmCallMetadata.empty();
        cancellationSignal = builder.cancellationSignal != null
                ? builder.cancellationSignal
                : NoopCancellationSignal.INSTANCE;
        principal = builder.principal;
        invokingSessionId = builder.invokingSessionId;
        knowledgeStore = builder.knowledgeStore;
        knowledgeScope = builder.knowledgeScope;
        toolContextEnrichers = builder.toolContextEnrichers != null
                ? List.copyOf(builder.toolContextEnrichers)
                : List.of();
        taskOutputStore = builder.taskOutputStore;
        taskResultStore = builder.taskResultStore;
        sessionSnapshotStore = builder.sessionSnapshotStore;
        previousSnapshot = builder.previousSnapshot;
        messageQueueManager = builder.messageQueueManager;
        parentEventSink = builder.parentEventSink;
    }

    /**
     * Gets the agent runtime ID.
     *
     * @return The context ID (never null)
     */
    public AgentRuntimeId getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /**
     * Gets the subagent registry.
     *
     * @return The subagent registry (never null)
     */
    public SubagentRegistry getSubagentRegistry() {
        return subagentRegistry;
    }

    /**
     * Gets the tool registry.
     *
     * @return The tool registry (never null)
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Gets the hook registry.
     *
     * @return The hook registry (never null)
     */
    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    /**
     * Gets the runtime environment.
     *
     * @return The environment (never null)
     */
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * Gets the default LLM model configuration.
     *
     * @return The default model (never null)
     */
    public LlmModel getDefaultModel() {
        return defaultModel;
    }

    /**
     * Gets the per-invocation model override forwarded from the {@code Task} tool's {@code model} argument, if
     * supplied.
     *
     * <p>
     * When present and non-blank it takes priority over the subagent's own {@code model} frontmatter and the
     * {@link #getDefaultModel() default model} during model resolution.
     *
     * @return an {@link Optional} holding the override alias, or empty when none was supplied
     */
    public Optional<String> getModelOverride() {
        return Optional.ofNullable(modelOverride);
    }

    /**
     * Gets the execution attributes.
     *
     * <p>
     * <b>Note:</b> The returned map is an unmodifiable shallow copy created via {@code Map.copyOf()}. Map values should
     * be effectively immutable types (e.g., {@code String}, {@code Integer}).
     *
     * @return The execution attributes (never null, may be empty)
     */
    public Map<String, Object> getExecutionAttributes() {
        return executionAttributes;
    }

    /**
     * Gets the parent execution's LLM call metadata for attribution propagation.
     *
     * <p>
     * Subagent execution inherits the parent's traceId/principal/tags so that token usage from nested agents can be
     * correlated with the original request. The subagent executor will additionally override component/feature with
     * subagent-specific values.
     *
     * @return the parent metadata (never null, may be {@link LlmCallMetadata#empty()})
     */
    public LlmCallMetadata getParentLlmCallMetadata() {
        return parentLlmCallMetadata;
    }

    /**
     * Gets the parent execution's cancellation signal, forwarded so parent-initiated cancellation cascades into the
     * subagent's ReAct loop and cooperative subagent tools.
     *
     * @return the cancellation signal (never null, defaults to {@link NoopCancellationSignal#INSTANCE})
     */
    public CancellationSignal getCancellationSignal() {
        return cancellationSignal;
    }

    /**
     * Gets the parent execution's principal (caller identity), forwarded so subagent tools observe the same
     * {@link at.aimon.core.tools.ToolContextKeys#PRINCIPAL} as the main-agent tools.
     *
     * @return an {@link Optional} holding the principal, or empty if none was forwarded
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Gets the session whose turn spawned this run, forwarded so the subagent's tools can resolve decisions the
     * user made in that session.
     *
     * <p>
     * This is the <em>invoker</em>, not the subagent's own session (which is minted per run) and not
     * {@link #getPreviousSnapshot()} (which is a resumed transcript, not an identity). Empty for runs no
     * session asked for — scheduled tasks and agent-scoped background work, which no session's turn spawned.
     * Background work a turn <em>did</em> spawn is not empty: the manager forwards the invoking id into it.
     *
     * @return an {@link Optional} holding the invoking session id, or empty if none was forwarded
     */
    public Optional<SessionId> getInvokingSessionId() {
        return Optional.ofNullable(invokingSessionId);
    }

    /**
     * Gets the knowledge store forwarded from the parent execution, if configured.
     *
     * @return an {@link Optional} holding the knowledge store, or empty if none was forwarded
     */
    public Optional<KnowledgeStore> getKnowledgeStore() {
        return Optional.ofNullable(knowledgeStore);
    }

    /**
     * Gets the knowledge scope forwarded from the parent execution, if configured.
     *
     * @return an {@link Optional} holding the knowledge scope, or empty if none was forwarded
     */
    public Optional<KnowledgeScope> getKnowledgeScope() {
        return Optional.ofNullable(knowledgeScope);
    }

    /**
     * Gets the tool-context enrichers forwarded from the parent agent runtime.
     *
     * @return an immutable list of enrichers (never null, may be empty)
     */
    public List<ToolContextEnricher> getToolContextEnrichers() {
        return toolContextEnrichers;
    }

    /**
     * Gets the task output store used to record and tail a background subagent's live progress log.
     *
     * <p>
     * When present, the background execution path binds an output sink to it so the {@code AgentOutput} tool can read
     * incremental deltas; when absent, background execution streams to a no-op sink (no regression).
     *
     * @return an {@link Optional} holding the task output store, or empty if none was configured
     */
    public Optional<TaskOutputStore> getTaskOutputStore() {
        return Optional.ofNullable(taskOutputStore);
    }

    /**
     * Gets the task result store used to persist what a background subagent finally produced.
     *
     * <p>
     * When present, the background execution path saves the {@link at.aimon.core.subagent.task.TaskResult} projection
     * keyed by {@code taskId} <em>before</em> moving the task to a terminal state, so the {@code AgentOutput} tool can
     * read it back — from any node, and after a restart. When absent, the result is not retained and
     * {@code AgentOutput} reports that no result is available for a settled task.
     *
     * @return an {@link Optional} holding the task result store, or empty if none was configured
     */
    public Optional<TaskResultStore> getTaskResultStore() {
        return Optional.ofNullable(taskResultStore);
    }

    /**
     * Gets the session snapshot store used to persist a finished subagent's transcript for later resume.
     *
     * <p>
     * When present, the execution path saves {@code SubagentExecutionResult.getSnapshot()} keyed by {@code taskId}
     * on completion so a later {@code Task} invocation with {@code resume=<taskId>} can reload it; when absent, no
     * snapshot is persisted (resume is unavailable, no regression).
     *
     * @return an {@link Optional} holding the snapshot store, or empty if none was configured
     */
    public Optional<SessionSnapshotStore> getSessionSnapshotStore() {
        return Optional.ofNullable(sessionSnapshotStore);
    }

    /**
     * Gets the previous session snapshot to resume from, loaded from the {@link SessionSnapshotStore} for a
     * {@code resume=<taskId>} request.
     *
     * <p>
     * When present it is forwarded to {@code SubagentExecutionRequest.previousSnapshot} so the executor rehydrates
     * the prior transcript via {@code TranscriptBuffer.fromSnapshot(...)} and appends the new goal; when absent the
     * subagent starts a fresh session.
     *
     * @return an {@link Optional} holding the snapshot to resume from, or empty for a fresh run
     */
    public Optional<SessionSnapshot> getPreviousSnapshot() {
        return Optional.ofNullable(previousSnapshot);
    }

    /**
     * Gets the parent session's message queue, used to push a guaranteed {@code <task-notification>} back to the
     * launching agent when a <b>background</b> subagent task settles.
     *
     * <p>
     * When present, {@code DefaultSubagentExecutionManager} enqueues a {@code NEXT}-priority {@code QueuedInput} scoped
     * to {@link #getAgentRuntimeId()} at terminal completion, so the parent's ReAct loop drains and injects it on its
     * next
     * iteration; when absent, no queued notification is pushed (the model only learns of the completion when it polls
     * with {@code AgentOutput}). Foreground execution never consults this.
     *
     * @return an {@link Optional} holding the message queue manager, or empty if none was forwarded
     */
    public Optional<MessageQueueManager> getMessageQueueManager() {
        return Optional.ofNullable(messageQueueManager);
    }

    /**
     * Gets the parent executor's event sink, used to emit a {@code SubagentTaskCompleted} event when a
     * <b>background</b>
     * subagent task settles.
     *
     * <p>
     * This is the observability half of the completion notification: the sink forwards to the parent
     * {@code OrcaAgentExecutor}'s {@code EventEmitter}, so any live {@code agent.stream} listener (CLI display, web
     * SSE)
     * sees the completion. It is best-effort — when the parent is idle at completion time no listener is attached and
     * the event is dropped; the queued notification (see {@link #getMessageQueueManager()}) remains the guaranteed,
     * model-facing path. When absent, no event is emitted.
     *
     * @return an {@link Optional} holding the event sink, or empty if none was forwarded
     */
    public Optional<Consumer<AgentExecutionEvent>> getParentEventSink() {
        return Optional.ofNullable(parentEventSink);
    }

    /**
     * Returns a builder seeded with every field of this environment, for deriving a variant that shares all borrowed
     * collaborators (registries, stores, model, ...) but overrides selected fields.
     *
     * <p>
     * The primary use is a <b>per-run</b> derivation in background workflow (design §5.1): a run derives
     * {@code baseEnv.toBuilder().cancellationSignal(runCoordinatorSignal).build()} so its fan-out subagents observe
     * that run's stop signal — sharing the borrowed collaborators unchanged, so the runner still never owns or closes
     * them.
     *
     * @return a builder pre-populated from this instance
     */
    public Builder toBuilder() {
        return new Builder().agentRuntimeId(agentRuntimeId).subagentRegistry(subagentRegistry)
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).environment(environment)
                .defaultModel(defaultModel).modelOverride(modelOverride).executionAttributes(executionAttributes)
                .parentLlmCallMetadata(parentLlmCallMetadata).cancellationSignal(cancellationSignal)
                .principal(principal).invokingSessionId(invokingSessionId).knowledgeStore(knowledgeStore)
                .knowledgeScope(knowledgeScope).toolContextEnrichers(toolContextEnrichers)
                .taskOutputStore(taskOutputStore).taskResultStore(taskResultStore)
                .sessionSnapshotStore(sessionSnapshotStore).previousSnapshot(previousSnapshot)
                .messageQueueManager(messageQueueManager).parentEventSink(parentEventSink);
    }

    @Override
    public String toString() {
        return "SubagentExecutionEnvironment{" + "agentRuntimeId=" + agentRuntimeId + ", subagentRegistry="
                + subagentRegistry + ", toolRegistry=" + toolRegistry + ", hookRegistry=" + hookRegistry
                + ", environment=" + environment + ", defaultModel=" + defaultModel + ", executionAttributes="
                + executionAttributes + '}';
    }

    /** Builder for SubagentExecutionEnvironment. */
    public static final class Builder {
        private AgentRuntimeId agentRuntimeId;
        private SubagentRegistry subagentRegistry;
        private ToolRegistry toolRegistry;
        private HookRegistry hookRegistry;
        private Environment environment;
        private LlmModel defaultModel;
        private String modelOverride;
        private Map<String, Object> executionAttributes;
        private LlmCallMetadata parentLlmCallMetadata;
        private CancellationSignal cancellationSignal;
        private Principal principal;
        private SessionId invokingSessionId;
        private KnowledgeStore knowledgeStore;
        private KnowledgeScope knowledgeScope;
        private List<ToolContextEnricher> toolContextEnrichers;
        private TaskOutputStore taskOutputStore;
        private TaskResultStore taskResultStore;
        private SessionSnapshotStore sessionSnapshotStore;
        private SessionSnapshot previousSnapshot;
        private MessageQueueManager messageQueueManager;
        private Consumer<AgentExecutionEvent> parentEventSink;

        private Builder() {
        }

        /**
         * Sets the agent runtime ID.
         *
         * @param agentRuntimeId
         *            The context ID (must not be null)
         * @return This builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * Sets the subagent registry.
         *
         * @param subagentRegistry
         *            The subagent registry (must not be null)
         * @return This builder
         */
        public Builder subagentRegistry(SubagentRegistry subagentRegistry) {
            this.subagentRegistry = subagentRegistry;
            return this;
        }

        /**
         * Sets the tool registry.
         *
         * @param toolRegistry
         *            The tool registry (must not be null)
         * @return This builder
         */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /**
         * Sets the hook registry.
         *
         * @param hookRegistry
         *            The hook registry (must not be null)
         * @return This builder
         */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /**
         * Sets the runtime environment.
         *
         * @param environment
         *            The environment (must not be null)
         * @return This builder
         */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the default LLM model configuration.
         *
         * @param defaultModel
         *            The default model (must not be null)
         * @return This builder
         */
        public Builder defaultModel(LlmModel defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        /**
         * Sets the per-invocation model override alias (the {@code Task} tool's {@code model} argument). When non-null
         * and non-blank it takes priority over the subagent frontmatter model and the default model.
         *
         * @param modelOverride
         *            the override alias (nullable; ignored when null/blank)
         * @return This builder
         */
        public Builder modelOverride(String modelOverride) {
            this.modelOverride = modelOverride;
            return this;
        }

        /**
         * Sets the execution attributes.
         *
         * <p>
         * <b>Note:</b> The map is stored using {@code Map.copyOf()}, which creates a shallow copy. Map values should be
         * effectively immutable types (e.g., {@code String}, {@code Integer}).
         *
         * @param executionAttributes
         *            The execution attributes (can be null)
         * @return This builder
         */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /**
         * Sets the parent execution's LLM call metadata.
         *
         * <p>
         * The subagent executor merges this with subagent-derived defaults (component=subagent name,
         * feature="subagent")
         * before passing the result to the LLM client.
         *
         * @param parentLlmCallMetadata
         *            the parent metadata (can be null, defaults to {@link LlmCallMetadata#empty()})
         * @return this builder
         */
        public Builder parentLlmCallMetadata(LlmCallMetadata parentLlmCallMetadata) {
            this.parentLlmCallMetadata = parentLlmCallMetadata;
            return this;
        }

        /**
         * Sets the parent execution's cancellation signal to forward into the subagent.
         *
         * @param cancellationSignal
         *            the parent signal (nullable, defaults to {@link NoopCancellationSignal#INSTANCE})
         * @return this builder
         */
        public Builder cancellationSignal(CancellationSignal cancellationSignal) {
            this.cancellationSignal = cancellationSignal;
            return this;
        }

        /**
         * Sets the parent execution's principal (caller identity) to forward into the subagent.
         *
         * @param principal
         *            the principal (nullable; subagent tools simply see no principal when absent)
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Sets the session whose turn spawned this run.
         *
         * <p>
         * Spawners must pass the id they themselves inherited when they have one, so that a fork spawned by a fork
         * still names the session the user actually granted something in —
         * {@link at.aimon.core.tools.InvokingSessionAccess#idToPropagate} implements that rule.
         *
         * @param invokingSessionId
         *            the invoking session id (nullable; a run with none inherits no decisions)
         * @return this builder
         */
        public Builder invokingSessionId(SessionId invokingSessionId) {
            this.invokingSessionId = invokingSessionId;
            return this;
        }

        /**
         * Sets the knowledge store forwarded from the parent execution.
         *
         * @param knowledgeStore
         *            the knowledge store (nullable)
         * @return this builder
         */
        public Builder knowledgeStore(KnowledgeStore knowledgeStore) {
            this.knowledgeStore = knowledgeStore;
            return this;
        }

        /**
         * Sets the knowledge scope forwarded from the parent execution.
         *
         * @param knowledgeScope
         *            the knowledge scope (nullable)
         * @return this builder
         */
        public Builder knowledgeScope(KnowledgeScope knowledgeScope) {
            this.knowledgeScope = knowledgeScope;
            return this;
        }

        /**
         * Sets the tool-context enrichers forwarded from the parent agent runtime.
         *
         * @param toolContextEnrichers
         *            the enrichers (nullable; treated as an empty list when absent)
         * @return this builder
         */
        public Builder toolContextEnrichers(List<ToolContextEnricher> toolContextEnrichers) {
            this.toolContextEnrichers = toolContextEnrichers;
            return this;
        }

        /**
         * Sets the task output store used to record and tail a background subagent's live progress log.
         *
         * @param taskOutputStore
         *            the task output store (nullable; background execution streams to a no-op sink when absent)
         * @return this builder
         */
        public Builder taskOutputStore(TaskOutputStore taskOutputStore) {
            this.taskOutputStore = taskOutputStore;
            return this;
        }

        /**
         * Sets the task result store used to persist what a background subagent finally produced.
         *
         * @param taskResultStore
         *            the task result store (nullable; the result is not retained when absent)
         * @return this builder
         */
        public Builder taskResultStore(TaskResultStore taskResultStore) {
            this.taskResultStore = taskResultStore;
            return this;
        }

        /**
         * Sets the session snapshot store used to persist a finished subagent's transcript for later resume.
         *
         * @param sessionSnapshotStore
         *            the snapshot store (nullable; no snapshot is persisted and resume is unavailable when absent)
         * @return this builder
         */
        public Builder sessionSnapshotStore(SessionSnapshotStore sessionSnapshotStore) {
            this.sessionSnapshotStore = sessionSnapshotStore;
            return this;
        }

        /**
         * Sets the previous session snapshot to resume from, loaded from the {@link SessionSnapshotStore} for
         * a {@code resume=<taskId>} request.
         *
         * @param previousSnapshot
         *            the snapshot to resume from (nullable; the subagent starts a fresh session when absent)
         * @return this builder
         */
        public Builder previousSnapshot(SessionSnapshot previousSnapshot) {
            this.previousSnapshot = previousSnapshot;
            return this;
        }

        /**
         * Sets the parent session's message queue used to push a guaranteed {@code <task-notification>} back to
         * the
         * launching agent when a background subagent task settles.
         *
         * @param messageQueueManager
         *            the message queue manager (nullable; no queued notification is pushed when absent)
         * @return this builder
         */
        public Builder messageQueueManager(MessageQueueManager messageQueueManager) {
            this.messageQueueManager = messageQueueManager;
            return this;
        }

        /**
         * Sets the parent executor's event sink used to emit a {@code SubagentTaskCompleted} event when a background
         * subagent task settles.
         *
         * @param parentEventSink
         *            the event sink (nullable; no event is emitted when absent)
         * @return this builder
         */
        public Builder parentEventSink(Consumer<AgentExecutionEvent> parentEventSink) {
            this.parentEventSink = parentEventSink;
            return this;
        }

        /**
         * Builds the SubagentExecutionEnvironment.
         *
         * @return A new SubagentExecutionEnvironment (never null)
         * @throws NullPointerException
         *             if any required field is null
         */
        public SubagentExecutionEnvironment build() {
            return new SubagentExecutionEnvironment(this);
        }
    }
}
