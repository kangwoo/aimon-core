package at.aimon.core.agent.impl.orca;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentEnvironmentSnapshotProvider;
import at.aimon.core.agent.context.ContextAssembler;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.transcript.ThrowingPromptTooLongHandler;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.schema.SchemaValidationMode;
import at.aimon.core.command.CommandExecutionManager;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.cost.CostEstimator;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.llm.retry.LlmFallbackPolicy;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.memory.MemoryContextProvider;
import at.aimon.core.skill.policy.SkillPreflightScanner;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.SubagentBackgroundConfig;
import at.aimon.core.subagent.SubagentBackgroundExecutionOptions;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.behavior.SubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.subagent.task.BackgroundTaskStore;
import at.aimon.core.subagent.task.InMemoryBackgroundTaskStore;
import at.aimon.core.subagent.task.NoopTaskStopSignal;
import at.aimon.core.subagent.task.TaskLeaseConfig;
import at.aimon.core.subagent.task.TaskStopSignal;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * Factory for creating {@link OrcaAgentExecutor} instances.
 *
 * <p>
 * This factory provides a simple way to create OrcaAgentExecutor with default or custom manager implementations. It
 * follows the Factory pattern to encapsulate the creation logic and provide sensible defaults.
 *
 * <p>
 * This factory can be instantiated and configured, making it suitable for dependency injection and testing scenarios.
 *
 * <p>
 * Example usage with defaults:
 *
 * <pre>
 * {
 *     &#64;code
 *     LlmClient llmClient = new OpenAILlmClient(apiKey);
 *     TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecordStore);
 *     OrcaAgentExecutorFactory factory = new OrcaAgentExecutorFactory();
 *     OrcaAgentExecutor executor = factory.create(llmClient, transcriptManager);
 * }
 * </pre>
 *
 * <p>
 * The {@link TranscriptManager} is mandatory and must be wired to the host's own
 * {@link at.aimon.core.agent.session.store.SessionRecordStore SessionRecordStore}. A {@code create(LlmClient)} overload
 * that substituted a factory-private in-memory store used to exist; it silently discarded transcripts at shutdown and
 * has been removed.
 *
 * <p>
 * Example usage with a custom gateway (retry/fallback/prompt-too-long semantics):
 *
 * <pre>
 * {
 *     &#64;code
 *     LlmCallGateway<TranscriptBuffer> gateway = LlmCallGateway.<TranscriptBuffer>builder().client(llmClient)
 *             .retryPolicy(LlmRetryPolicy.defaultPolicy()).fallbackPolicy(LlmFallbackPolicy.none())
 *             .promptTooLongHandler(ThrowingPromptTooLongHandler.INSTANCE).build();
 *     OrcaAgentExecutorFactory factory = new OrcaAgentExecutorFactory().withGateway(gateway);
 *     OrcaAgentExecutor executor = factory.create(llmClient, transcriptManager);
 * }
 * </pre>
 *
 * <p>
 * When no gateway override is configured, the factory builds a default pass-through gateway using
 * {@link LlmRetryPolicy#defaultPolicy()}, the configured fallback policy (or {@link LlmFallbackPolicy#none()} when
 * {@link #withFallbackPolicy(LlmFallbackPolicy)} is left unset), and {@link ThrowingPromptTooLongHandler#INSTANCE}
 * which rethrows {@link at.aimon.core.llm.exception.LlmPromptTooLongException} unchanged — preserving legacy behavior
 * until a compaction-driven handler lands. An explicit {@link #withGateway(LlmCallGateway)} override takes precedence
 * over the fallback-policy knob.
 *
 * <p>
 * For even more control, use the {@link OrcaAgentExecutor} constructor directly.
 *
 * @see OrcaAgentExecutor
 */
public class OrcaAgentExecutorFactory {

    private static final Logger log = LoggerFactory.getLogger(OrcaAgentExecutorFactory.class);

    private LlmCallGateway<TranscriptBuffer> gatewayOverride;
    private MessageQueueManager messageQueueManager;
    private AgentEnvironmentSnapshotProvider agentEnvironmentSnapshotProvider;
    private boolean useStreaming;
    private LlmStreamingOptions streamingOptions;
    private SkillPreflightScanner skillPreflightScanner;
    private PendingTurnRegistry pendingTurnRegistry;
    private Duration pendingTurnTtl;
    private MemoryContextProvider memoryContextProvider;
    private RewakeService rewakeService;
    private ToolConcurrencyConfig toolConcurrencyConfig;
    private SideEffectLevel maxSideEffectLevel;
    // Interactive approval for tools that write. Shared with the subagent executor so a fork honours the answer the
    // user gave in the session that launched it. Null => no approval is requested (unchanged behaviour).
    private SideEffectApprovalGate approvalGate;
    // How the tool executor reacts to a call that does not match the tool's declared schema. Null => the executor's
    // own default, SchemaValidationMode.WARN.
    private SchemaValidationMode schemaValidationMode;
    // Code-behavior subagents: a name registered here runs its SubagentBehavior instead of the ReAct loop. Threaded
    // into the DefaultSubagentExecutionManager built by createDefaultSubagentExecutionManager. Null => empty().
    private SubagentBehaviorRegistry subagentBehaviorRegistry;
    // Bounds background subagent fan-out (thread pool + queue). Null => unbounded cached pool (legacy behaviour).
    private SubagentBackgroundConfig subagentBackgroundConfig;
    // Multi-instance-ready store for background task metadata. Null => node-local InMemoryBackgroundTaskStore.
    private BackgroundTaskStore backgroundTaskStore;
    // Subagent §4: cross-node stop signal for background tasks. Null => NoopTaskStopSignal (local-only cancellation).
    private TaskStopSignal taskStopSignal;
    // §4: zombie-recovery lease config for background tasks. Null => no heartbeat/reaper threads (single-node).
    private TaskLeaseConfig taskLeaseConfig;
    // TRACE-01: optional tracer recording the per-turn span tree. Null => executor keeps Tracer.noop() (no tracing).
    private Tracer tracer;
    // TRACE-02: optional payload capture policy. Null => executor keeps TracePayloadPolicy.summaryOnly() (no content).
    private TracePayloadPolicy tracePayloadPolicy;
    // Optional cost estimator pricing each LLM call. Null => executor keeps CostEstimator.NOOP (zero-cost).
    private CostEstimator costEstimator;
    // Optional context assembler. Null => executor keeps ContextAssembler.NOOP (assembles nothing).
    private ContextAssembler contextAssembler;
    // Optional model-fallback policy applied to the default gateway. Null => LlmFallbackPolicy.none() (no
    // fallback). Only consulted when no explicit gateway override is set via withGateway(...).
    private LlmFallbackPolicy fallbackPolicy;

    /**
     * Creates a new factory instance.
     */
    public OrcaAgentExecutorFactory() {
        // Public constructor for instantiation
    }

    /**
     * Configures an optional {@link LlmCallGateway} override. When set, all subsequent {@code create(...)} calls use
     * this gateway instead of the factory's default pass-through gateway. When unset, the factory builds a default
     * pass-through gateway around the supplied {@link LlmClient}.
     *
     * <p>
     * The override must be pre-bound to the underlying client that matches the client passed to {@code create(...)}
     * — the factory does not substitute it.
     *
     * @param gatewayOverride
     *            the gateway to use for LLM calls (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withGateway(LlmCallGateway<TranscriptBuffer> gatewayOverride) {
        this.gatewayOverride = gatewayOverride;
        return this;
    }

    /**
     * Configures an optional {@link LlmFallbackPolicy} applied to the factory's default pass-through gateway.
     * When set, an LLM call that fails with an activating exception (per the policy) escalates through the configured
     * model chain — optionally only after {@link LlmFallbackPolicy#getConsecutiveFailureThreshold()} consecutive
     * activating failures on the same model. When {@code null} (the default), {@link LlmFallbackPolicy#none()} is used
     * and no fallback occurs, preserving the historical single-model behavior.
     *
     * <p>
     * This override is only consulted when no explicit {@link #withGateway(LlmCallGateway) gateway override} is set —
     * a supplied gateway already carries its own fallback policy and is used verbatim.
     *
     * @param fallbackPolicy
     *            the fallback policy for the default gateway (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withFallbackPolicy(LlmFallbackPolicy fallbackPolicy) {
        this.fallbackPolicy = fallbackPolicy;
        return this;
    }

    /**
     * Configures an optional {@link MessageQueueManager} for CQ-03 mid-turn injection. When set, the
     * {@link OrcaAgentExecutor} built by this factory drains queued user inputs at each ReAct iteration's tail and
     * appends them to the active conversation as {@code <system-reminder>}-wrapped user messages. When {@code null}
     * (the default), mid-turn injection is disabled and the executor behaves exactly as it did before CQ-03.
     *
     * <p>
     * This is an application-level concern: callers that wire a REPL, HTTP server or other input producer to the
     * queue should pass the same {@link MessageQueueManager} instance here so producers and the executor agree on a
     * single queue.
     *
     * @param messageQueueManager
     *            the queue manager (may be {@code null} to clear the configuration)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withMessageQueueManager(MessageQueueManager messageQueueManager) {
        this.messageQueueManager = messageQueueManager;
        return this;
    }

    /**
     * Configures an optional {@link AgentEnvironmentSnapshotProvider} for CTX-06 synthetic {@code messages[0]}
     * user-context
     * injection. When set, the {@link OrcaAgentExecutor} built by this factory injects a synthetic user-role message
     * at the head of every fresh conversation (one whose loaded memory contains no user messages yet), wrapping
     * session-level context (working directory, current date, CLAUDE.md-style extensions) in
     * {@code <system-reminder>} blocks. Resumed conversations always skip injection. When {@code null} (the default),
     * injection is disabled.
     *
     * @param agentEnvironmentSnapshotProvider
     *            the provider (may be {@code null} to clear the configuration)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withAgentEnvironmentSnapshotProvider(
            AgentEnvironmentSnapshotProvider agentEnvironmentSnapshotProvider) {
        this.agentEnvironmentSnapshotProvider = agentEnvironmentSnapshotProvider;
        return this;
    }

    /**
     * PSTREAM-09: enables or disables streaming LLM calls. When enabled, the built {@link OrcaAgentExecutor} routes
     * every LLM call through {@link LlmCallGateway#sendMessageStreaming} and emits token-level
     * {@link at.aimon.core.agent.stream.AssistantTextDelta} events.
     *
     * <p>
     * Default: {@code false} (legacy non-streaming behaviour).
     *
     * @param useStreaming
     *            whether to use streaming
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withUseStreaming(boolean useStreaming) {
        this.useStreaming = useStreaming;
        return this;
    }

    /**
     * PSTREAM-09: configures per-call streaming options passed to
     * {@link LlmCallGateway#sendMessageStreaming}. Only consulted when {@link #withUseStreaming(boolean)} is
     * {@code true}. When {@code null} (the default), {@link LlmStreamingOptions#defaults()} is used.
     *
     * @param streamingOptions
     *            the streaming options (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withStreamingOptions(LlmStreamingOptions streamingOptions) {
        this.streamingOptions = streamingOptions;
        return this;
    }

    /**
     * SK-11.4: configures the optional pre-flight {@link SkillPreflightScanner}. When set, the built
     * {@link OrcaAgentExecutor} inspects every LLM response's {@code Skill} tool_uses through this scanner before
     * dispatching tools and atomically suspends the turn when the policy returns {@code ASK}. Must be paired with
     * {@link #withPendingTurnRegistry(PendingTurnRegistry)} so suspended turns can be persisted for the approval
     * channel.
     *
     * <p>
     * When {@code null} (the default), no scanning is performed and the executor falls back to the per-tool
     * fail-closed path inside {@code SkillTool}.
     *
     * @param skillPreflightScanner
     *            the scanner (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withSkillPreflightScanner(SkillPreflightScanner skillPreflightScanner) {
        this.skillPreflightScanner = skillPreflightScanner;
        return this;
    }

    /**
     * SK-11.4: configures the {@link PendingTurnRegistry} that records suspended turns when the
     * {@link #withSkillPreflightScanner scanner} returns a suspend decision. Required iff a scanner is configured;
     * the {@link OrcaAgentExecutor} constructor rejects a non-null scanner with a null registry.
     *
     * @param pendingTurnRegistry
     *            the registry (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withPendingTurnRegistry(PendingTurnRegistry pendingTurnRegistry) {
        this.pendingTurnRegistry = pendingTurnRegistry;
        return this;
    }

    /**
     * SK-11.4: configures how long a suspended turn waits in the registry before the reaper drops it.
     *
     * <p>
     * Left unset, {@link OrcaAgentExecutor#DEFAULT_PENDING_TURN_TTL} applies — thirty minutes, chosen for a
     * terminal where the person who was asked is the person sitting there. A deployment whose approvals arrive
     * from somewhere else should say so: too long and suspended turns accumulate in the registry with nothing to
     * release them, too short and an approval that was on its way arrives to find nothing waiting.
     *
     * @param pendingTurnTtl
     *            the TTL; must be positive, or {@code null} to keep the default
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withPendingTurnTtl(Duration pendingTurnTtl) {
        this.pendingTurnTtl = pendingTurnTtl;
        return this;
    }

    /**
     * SK-MEM Stage 9: configures the optional {@link MemoryContextProvider} that contributes a memory-derived
     * system prompt part on every turn. When set, the built {@link OrcaAgentExecutor} appends the part returned by
     * {@link MemoryContextProvider#provide()} after the environment block. When {@code null} (the default), no
     * memory part is appended and the prompt matches the legacy non-memory shape.
     *
     * @param memoryContextProvider
     *            the provider (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withMemoryContextProvider(MemoryContextProvider memoryContextProvider) {
        this.memoryContextProvider = memoryContextProvider;
        return this;
    }

    /**
     * async-rewake §7.4: configures the application-scoped {@link RewakeService} that the built
     * {@link OrcaAgentExecutor}'s {@link DefaultHookExecutionManager} will hand rewake specs to. When set, the default
     * hook execution manager is wrapped via {@link DefaultHookExecutionManager#withRewakeService(RewakeService)} so
     * hooks returning {@code asyncRewake} schedule against this service. When {@code null} (the default), the manager
     * uses {@link RewakeService#NOOP} which logs a WARN and drops rewake specs.
     *
     * @param rewakeService
     *            the rewake service (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withRewakeService(RewakeService rewakeService) {
        this.rewakeService = rewakeService;
        return this;
    }

    /**
     * Configures the {@link SubagentBehaviorRegistry} threaded into the {@link DefaultSubagentExecutionManager} this
     * factory builds. A subagent name registered in this registry runs its
     * {@link at.aimon.core.subagent.behavior.SubagentBehavior} (Java code) instead of the LLM ReAct loop; all other
     * names run the unchanged data path. Pair each behavior with a same-named code {@code Subagent} data entry (for
     * discovery / tool allow-list) — see {@link at.aimon.core.subagent.behavior.SubagentBehaviorRegistrar}.
     *
     * <p>
     * When {@code null} (the default) {@link SubagentBehaviorRegistry#empty()} is used and behavior is unchanged.
     *
     * @param subagentBehaviorRegistry
     *            the code-behavior registry (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withSubagentBehaviorRegistry(SubagentBehaviorRegistry subagentBehaviorRegistry) {
        this.subagentBehaviorRegistry = subagentBehaviorRegistry;
        return this;
    }

    /**
     * PAR-06: configures batch-parallel tool execution. When set to an {@link ToolConcurrencyConfig#isEnabled()
     * enabled}
     * config, the built {@link OrcaAgentExecutor} runs batches whose tools are all
     * {@link at.aimon.core.agent.tool.ConcurrencyBehavior#CONCURRENT_SAFE CONCURRENT_SAFE} on a bounded worker pool.
     * When
     * {@code null} (the default) or {@link ToolConcurrencyConfig#disabled() disabled}, the executor keeps its default
     * sequential dispatcher and behaviour is unchanged.
     *
     * <p>
     * Each {@code create(...)} call builds its own executor-scoped dispatcher (and therefore its own worker pool when
     * enabled), so the pool lifetime tracks the executor rather than this factory.
     *
     * @param toolConcurrencyConfig
     *            the concurrency configuration (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withToolConcurrencyConfig(ToolConcurrencyConfig toolConcurrencyConfig) {
        this.toolConcurrencyConfig = toolConcurrencyConfig;
        return this;
    }

    /**
     * Restricts the built executor to tools declaring at most the given {@link SideEffectLevel}.
     *
     * <p>
     * The ceiling is applied in two places, and both are needed. The executor withholds the definitions of tools that
     * exceed it, so the LLM is never offered a call that cannot succeed; the {@link DefaultToolExecutionManager} it is
     * built with carries the same ceiling and refuses at execution time, which still matters because a model can name
     * a tool it was never shown.
     *
     * <p>
     * Passing {@link SideEffectLevel#READ_ONLY} yields an executor that can inspect but not change anything — the
     * shape an exploration or planning mode wants. The default is {@link SideEffectLevel#MUTATING}, which permits
     * every tool and leaves behaviour unchanged.
     *
     * <p>
     * Subagent forks are covered too, and by a shorter route: they share this manager, and
     * {@code DefaultSubagentExecutor} reads its ceiling back through
     * {@link at.aimon.core.agent.tool.ToolExecutionManager#getMaxSideEffectLevel()} to filter their own definition
     * lists. So the fork's two halves are driven by one value and cannot drift apart.
     *
     * <p>
     * This only takes effect for the tool execution manager the factory creates itself. A manager supplied through
     * {@code create(..., ToolExecutionManager, ...)} carries whatever ceiling its caller gave it — which then governs
     * both execution and the fork's definition filter. The turn's own definition filter is separate and follows this
     * setting either way.
     *
     * @param maxSideEffectLevel
     *            the most permissive level a tool may declare (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withMaxSideEffectLevel(SideEffectLevel maxSideEffectLevel) {
        this.maxSideEffectLevel = maxSideEffectLevel;
        return this;
    }

    /**
     * Requires the user to approve tool calls the gate does not exempt — by default every tool declaring more than
     * {@link SideEffectLevel#READ_ONLY}. When unset (the default) no approval is requested and behaviour is
     * unchanged.
     *
     * <p>
     * This is the <em>dynamic</em> counterpart to {@link #withMaxSideEffectLevel(SideEffectLevel)}: the ceiling is a
     * static decision the host makes up front and enforces with nobody present, while the gate asks a person at the
     * moment of the call. They compose, and setting one does not imply the other.
     *
     * <p>
     * The gate is passed to the subagent executor this factory creates as well, so forks are gated by the <em>same
     * instance</em> — that shared state is what lets a fork honour an answer the user gave in the session that
     * launched it rather than being asked through a channel it does not have.
     *
     * @param approvalGate
     *            the gate (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withApprovalGate(SideEffectApprovalGate approvalGate) {
        this.approvalGate = approvalGate;
        return this;
    }

    /**
     * Sets how a tool call that does not match the tool's declared schema is treated.
     *
     * <p>
     * The default is {@link SchemaValidationMode#WARN}: the mismatch is logged and the tool runs anyway. That is the
     * observe-first setting, meant to be left on long enough to find out what the models in a given deployment
     * actually send. {@link SchemaValidationMode#ENFORCE} rejects the call and hands the violations back to the model
     * instead of running the tool; {@link SchemaValidationMode#OFF} skips the check.
     *
     * <p>
     * Covers a slash command's skill too, and by the same route the ceiling takes: the command execution manager is
     * handed the very manager the agent dispatches through, so there is no second executor left on the default for
     * the two paths to disagree about. Passing an explicitly built {@code ToolExecutionManager} to {@code create(...)}
     * overrides this, since that manager brings its own executor.
     *
     * @param schemaValidationMode
     *            the mode (may be {@code null} to clear the override and take the default)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withSchemaValidationMode(SchemaValidationMode schemaValidationMode) {
        this.schemaValidationMode = schemaValidationMode;
        return this;
    }

    /**
     * Bounds background subagent execution. When set, {@link #createDefaultSubagentExecutionManager} builds a
     * bounded daemon thread pool ({@link SubagentBackgroundConfig#getMaxConcurrency() maxConcurrency} threads, backed
     * by
     * a queue of {@link SubagentBackgroundConfig#getQueueCapacity() queueCapacity}) instead of the legacy unbounded
     * cached pool, so a burst of {@code run_in_background} tasks cannot spawn threads without limit. When {@code null}
     * (the default) the unbounded cached pool is retained (no behaviour change).
     *
     * @param subagentBackgroundConfig
     *            the background pool configuration (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withSubagentBackgroundConfig(SubagentBackgroundConfig subagentBackgroundConfig) {
        this.subagentBackgroundConfig = subagentBackgroundConfig;
        return this;
    }

    /**
     * Installs a multi-instance-ready {@link BackgroundTaskStore} for background task metadata (listing/status).
     * When
     * {@code null} (the default) a node-local {@link InMemoryBackgroundTaskStore} is used. Supply a shared-backend
     * implementation to make {@code TaskList}/{@code AgentOutput} observe tasks across instances in a scale-out
     * deployment.
     *
     * @param backgroundTaskStore
     *            the task metadata store (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withBackgroundTaskStore(BackgroundTaskStore backgroundTaskStore) {
        this.backgroundTaskStore = backgroundTaskStore;
        return this;
    }

    /**
     * Subagent §4: installs the cross-node {@link TaskStopSignal} so a {@code Task.stop} issued on one node can cancel
     * a background task running on another. When {@code null} (the default) a {@link NoopTaskStopSignal} is used and
     * stops are local-only (single-node behaviour, unchanged).
     *
     * <p>
     * In a scale-out deployment, pair this with a shared {@link #withBackgroundTaskStore(BackgroundTaskStore) task
     * store}: the store lets a node observe a task it does not own as non-terminal, and the signal carries the stop to
     * the owning node. Installing a shared store without a real stop signal leaves cross-node stops unpropagated.
     *
     * @param taskStopSignal
     *            the cross-node stop signal (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withTaskStopSignal(TaskStopSignal taskStopSignal) {
        this.taskStopSignal = taskStopSignal;
        return this;
    }

    /**
     * Subagent §4: enables zombie-task lease recovery for background tasks. When set, the
     * {@link DefaultSubagentExecutionManager} built by this factory starts a heartbeat publisher (renewing the lease of
     * every background task this node owns) and a reaper (transitioning heartbeat-expired non-terminal tasks to
     * {@code FAILED} so a crashed node's tasks do not stay {@code RUNNING} in a shared store forever). When
     * {@code null}
     * (the default) no lease threads run and single-node behaviour is unchanged.
     *
     * <p>
     * This is only meaningful with a shared {@link #withBackgroundTaskStore(BackgroundTaskStore) task store}: with the
     * default node-local store a task is only ever visible on the node that owns it, so there is nothing to reap.
     *
     * @param taskLeaseConfig
     *            the lease configuration (may be {@code null} to disable lease recovery)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withTaskLeaseConfig(TaskLeaseConfig taskLeaseConfig) {
        this.taskLeaseConfig = taskLeaseConfig;
        return this;
    }

    /**
     * TRACE-01: installs the tracer that records the per-turn span tree on the created executor. When unset (or
     * {@code null}), the executor keeps {@link Tracer#noop()} and behaviour is unchanged.
     *
     * @param tracer
     *            the tracer (may be {@code null} to clear)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withTracer(Tracer tracer) {
        this.tracer = tracer;
        return this;
    }

    /**
     * TRACE-02: installs the payload capture policy on the created executor, governing whether tool result content is
     * captured in {@code TOOL} span outputs. When unset (or {@code null}), the executor keeps
     * {@link TracePayloadPolicy#summaryOnly()} and only the summary (length/error) is recorded.
     *
     * @param tracePayloadPolicy
     *            the payload capture policy (may be {@code null} to clear)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withTracePayloadPolicy(TracePayloadPolicy tracePayloadPolicy) {
        this.tracePayloadPolicy = tracePayloadPolicy;
        return this;
    }

    /**
     * Installs the cost estimator that prices each LLM call on the created executor, enabling per-model cost
     * tracking on the execution result and (when an {@link at.aimon.core.agent.budget.ExecutionBudget#getMaxCostUsd()
     * cost budget} is set) hard cost-budget enforcement. When unset (or {@code null}), the executor keeps
     * {@link CostEstimator#NOOP} (every call priced at zero) and behaviour is unchanged.
     *
     * @param costEstimator
     *            the cost estimator (may be {@code null} to clear)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withCostEstimator(CostEstimator costEstimator) {
        this.costEstimator = costEstimator;
        return this;
    }

    /**
     * Installs the context assembler that builds the runtime context blocks (environment / git branch / directory
     * summary / user extensions / attachments) injected into each turn. When unset (or {@code null}), the executor
     * keeps {@link ContextAssembler#NOOP} (assembles nothing) and the prompt shape is unchanged.
     *
     * @param contextAssembler
     *            the context assembler (may be {@code null} to clear)
     * @return this factory (for chaining)
     */
    public OrcaAgentExecutorFactory withContextAssembler(ContextAssembler contextAssembler) {
        this.contextAssembler = contextAssembler;
        return this;
    }

    /**
     * Creates an OrcaAgentExecutor with default manager implementations.
     *
     * <p>
     * Default implementations:
     *
     * <ul>
     * <li>ToolExecutionManager: {@link DefaultToolExecutionManager}
     * <li>HookExecutionManager: {@link DefaultHookExecutionManager}
     * <li>CommandExecutionManager: {@link DefaultCommandExecutionManager}
     * </ul>
     *
     * @param llmClient
     *            The LLM client (must not be null)
     * @param transcriptManager
     *            The transcript manager (must not be null)
     * @return A new OrcaAgentExecutor instance with default managers (never null)
     * @throws NullPointerException
     *             if llmClient or transcriptManager is null
     */
    public OrcaAgentExecutor create(LlmClient llmClient, TranscriptManager transcriptManager) {
        Objects.requireNonNull(llmClient, "LLM client cannot be null");
        Objects.requireNonNull(transcriptManager, "Transcript manager cannot be null");

        final ToolExecutionManager toolExecutionManager = createDefaultToolExecutionManager();
        final HookExecutionManager hookExecutionManager = createDefaultHookExecutionManager();
        final CommandExecutionManager commandExecutionManager = createDefaultCommandExecutionManager(llmClient,
                toolExecutionManager);
        final SubagentExecutionManager subagentExecutionManager = createDefaultSubagentExecutionManager(llmClient,
                toolExecutionManager, hookExecutionManager);

        final OrcaAgentExecutor executor = OrcaAgentExecutor.builder().gateway(resolveGateway(llmClient))
                .transcriptManager(transcriptManager).toolExecutionManager(toolExecutionManager)
                .hookExecutionManager(hookExecutionManager).commandExecutionManager(commandExecutionManager)
                .subagentExecutionManager(subagentExecutionManager).messageQueueManager(messageQueueManager)
                .agentEnvironmentSnapshotProvider(agentEnvironmentSnapshotProvider).useStreaming(useStreaming)
                .streamingOptions(streamingOptions).skillPreflightScanner(skillPreflightScanner)
                .pendingTurnRegistry(pendingTurnRegistry).pendingTurnTtl(pendingTurnTtl)
                .memoryContextProvider(memoryContextProvider).approvalGate(approvalGate).build();
        return applyExecutorOverrides(executor);
    }

    /**
     * Creates an OrcaAgentExecutor with custom manager implementations.
     *
     * <p>
     * This method allows full customization of all manager dependencies. Use this when you need to provide custom
     * implementations for testing or specialized behavior.
     *
     * @param llmClient
     *            The LLM client (must not be null)
     * @param transcriptManager
     *            The transcript manager (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager (must not be null)
     * @param commandExecutionManager
     *            The command execution manager (must not be null)
     * @param subagentExecutionManager
     *            The subagent execution manager (must not be null)
     * @return A new OrcaAgentExecutor instance with custom managers (never null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public OrcaAgentExecutor create(LlmClient llmClient, TranscriptManager transcriptManager,
            ToolExecutionManager toolExecutionManager, HookExecutionManager hookExecutionManager,
            CommandExecutionManager commandExecutionManager, SubagentExecutionManager subagentExecutionManager) {
        Objects.requireNonNull(llmClient, "LLM client cannot be null");
        Objects.requireNonNull(transcriptManager, "Transcript manager cannot be null");
        Objects.requireNonNull(toolExecutionManager, "Tool execution manager cannot be null");
        Objects.requireNonNull(hookExecutionManager, "Hook execution manager cannot be null");
        Objects.requireNonNull(commandExecutionManager, "Command execution manager cannot be null");
        Objects.requireNonNull(subagentExecutionManager, "Subagent execution manager cannot be null");

        final OrcaAgentExecutor executor = OrcaAgentExecutor.builder().gateway(resolveGateway(llmClient))
                .transcriptManager(transcriptManager).toolExecutionManager(toolExecutionManager)
                .hookExecutionManager(hookExecutionManager).commandExecutionManager(commandExecutionManager)
                .subagentExecutionManager(subagentExecutionManager).messageQueueManager(messageQueueManager)
                .agentEnvironmentSnapshotProvider(agentEnvironmentSnapshotProvider).useStreaming(useStreaming)
                .streamingOptions(streamingOptions).skillPreflightScanner(skillPreflightScanner)
                .pendingTurnRegistry(pendingTurnRegistry).pendingTurnTtl(pendingTurnTtl)
                .memoryContextProvider(memoryContextProvider).approvalGate(approvalGate).build();
        return applyExecutorOverrides(executor);
    }

    /**
     * Applies the post-construction executor overrides configured on this factory: the executor-scoped parallel
     * dispatcher (when an enabled {@link ToolConcurrencyConfig} was set), the {@link Tracer}, the
     * {@link TracePayloadPolicy}, the {@link CostEstimator}, and the {@link ContextAssembler}. Each is left at the
     * executor's default when the corresponding override is unset.
     */
    private OrcaAgentExecutor applyExecutorOverrides(OrcaAgentExecutor executor) {
        if (toolConcurrencyConfig != null && toolConcurrencyConfig.isEnabled()) {
            executor.parallelToolDispatcher = new DefaultParallelToolDispatcher(toolConcurrencyConfig);
        }
        if (maxSideEffectLevel != null) {
            executor.maxSideEffectLevel = maxSideEffectLevel;
        }
        if (tracer != null) {
            executor.tracer = tracer;
        }
        if (tracePayloadPolicy != null) {
            executor.tracePayloadPolicy = tracePayloadPolicy;
        }
        if (costEstimator != null) {
            executor.costEstimator = costEstimator;
        }
        if (contextAssembler != null) {
            executor.contextAssembler = contextAssembler;
        }
        return executor;
    }

    /**
     * Resolves the {@link LlmCallGateway} to use: returns the configured override if set, otherwise builds a default
     * pass-through gateway wrapping {@code llmClient} with {@link LlmRetryPolicy#defaultPolicy()}, the configured
     * {@link #withFallbackPolicy(LlmFallbackPolicy) fallback policy} (or {@link LlmFallbackPolicy#none()} when unset),
     * and {@link ThrowingPromptTooLongHandler#INSTANCE} which rethrows prompt-too-long errors unchanged.
     */
    protected LlmCallGateway<TranscriptBuffer> resolveGateway(LlmClient llmClient) {
        Objects.requireNonNull(llmClient, "LLM client cannot be null");
        if (gatewayOverride != null) {
            return gatewayOverride;
        }
        final LlmFallbackPolicy effectiveFallbackPolicy = fallbackPolicy != null
                ? fallbackPolicy
                : LlmFallbackPolicy.none();
        return LlmCallGateway.<TranscriptBuffer>builder().client(llmClient).retryPolicy(LlmRetryPolicy.defaultPolicy())
                .fallbackPolicy(effectiveFallbackPolicy).promptTooLongHandler(ThrowingPromptTooLongHandler.INSTANCE)
                .build();
    }

    protected SubagentExecutionManager createDefaultSubagentExecutionManager(LlmClient llmClient,
            ToolExecutionManager toolExecutionManager, HookExecutionManager hookExecutionManager) {
        final SubagentBehaviorRegistry behaviorRegistry = subagentBehaviorRegistry != null
                ? subagentBehaviorRegistry
                : SubagentBehaviorRegistry.empty();

        // Price the subagent ReAct path with the same cost estimator installed on the main executor, so an
        // workflow run's aggregate USD cost (its subagents run through this manager) is actually populated instead
        // of staying zero. The executor is built exactly as the (llmClient, ...) convenience constructors build it; the
        // estimator is only attached when configured, so the default (no-estimator) path stays behaviourally identical.
        final DefaultSubagentExecutor subagentExecutor = new DefaultSubagentExecutor(llmClient, toolExecutionManager,
                hookExecutionManager);
        if (costEstimator != null) {
            subagentExecutor.withCostEstimator(costEstimator);
        }
        // Gate forks with the *same* instance the main executor gets. A fork has no channel of its own to prompt
        // through, so sharing the gate is what lets it resolve against the launching session's remembered answers
        // instead of falling to the handler default.
        if (approvalGate != null) {
            subagentExecutor.withApprovalGate(approvalGate);
        }

        // When a background config is supplied, bound the pool; otherwise retain the legacy unbounded cached pool.
        // Subagent §4: inject the configured store, else a node-local in-memory one; the configured cross-node stop
        // signal, else NoopTaskStopSignal; and the lease config to enable zombie recovery, else null.
        // All are threaded through the explicit constructor only when at least one override is present, so the default
        // path is byte-for-byte the prior behaviour.
        if (subagentBackgroundConfig == null && backgroundTaskStore == null && taskStopSignal == null
                && taskLeaseConfig == null) {
            return new DefaultSubagentExecutionManager(subagentExecutor,
                    DefaultSubagentExecutionManager.newUnboundedBackgroundExecutor(), hookExecutionManager,
                    behaviorRegistry, llmClient);
        }
        final BackgroundTaskStore store = backgroundTaskStore != null
                ? backgroundTaskStore
                : new InMemoryBackgroundTaskStore();
        final TaskStopSignal stopSignal = taskStopSignal != null ? taskStopSignal : NoopTaskStopSignal.INSTANCE;
        // With a config, bound the pool per config; with only a store/signal override, keep the legacy unbounded cached
        // pool so swapping the store never silently changes background concurrency.
        final ExecutorService backgroundExecutor = subagentBackgroundConfig != null
                ? DefaultSubagentExecutionManager.newBackgroundExecutor(subagentBackgroundConfig)
                : DefaultSubagentExecutionManager.newUnboundedBackgroundExecutor();
        return new DefaultSubagentExecutionManager(subagentExecutor, hookExecutionManager, behaviorRegistry, llmClient,
                SubagentBackgroundExecutionOptions.builder().executorService(backgroundExecutor).taskStore(store)
                        .taskStopSignal(stopSignal).leaseConfig(taskLeaseConfig).build());
    }

    /**
     * Creates the default tool execution manager.
     *
     * <p>
     * This method can be overridden in subclasses to provide custom default implementations.
     *
     * <p>
     * The manager is built with the ceiling configured through {@link #withMaxSideEffectLevel(SideEffectLevel)} so it
     * refuses over-privileged tools at execution time. An override that ignores {@code maxSideEffectLevel} drops that
     * enforcement — the executor-side definition filter still hides the tools, but a model naming one from memory would
     * then reach it.
     *
     * @return A new DefaultToolExecutionManager instance (never null)
     */
    protected ToolExecutionManager createDefaultToolExecutionManager() {
        if (schemaValidationMode == null) {
            return maxSideEffectLevel == null
                    ? new DefaultToolExecutionManager()
                    : new DefaultToolExecutionManager(maxSideEffectLevel);
        }
        return maxSideEffectLevel == null
                ? new DefaultToolExecutionManager(schemaValidationMode)
                : new DefaultToolExecutionManager(schemaValidationMode, maxSideEffectLevel);
    }

    /**
     * Creates the default hook execution manager.
     *
     * <p>
     * This method can be overridden in subclasses to provide custom default implementations.
     *
     * @return A new DefaultHookExecutionManager instance (never null)
     */
    protected HookExecutionManager createDefaultHookExecutionManager() {
        final DefaultHookExecutionManager manager = new DefaultHookExecutionManager();
        if (rewakeService == null) {
            return manager;
        }
        return manager.withRewakeService(rewakeService);
    }

    /**
     * Creates the default command execution manager.
     *
     * <p>
     * This method can be overridden in subclasses to provide custom default implementations.
     *
     * <p>
     * The tool execution manager is the same instance the executor itself dispatches through, and it is handed on
     * rather than freshly minted for one reason: a skill invoked as {@code /my-skill} runs its own ReAct loop against
     * the agent's real {@link at.aimon.core.agent.tool.ToolRegistry ToolRegistry}, so a skill executor holding a
     * default manager would run tools this factory's own {@code maxSideEffectLevel} refuses. Sharing carries the
     * schema validation mode along with it, which is why there is no {@link SchemaValidationMode} parameter here.
     *
     * @param llmClient
     *            The LLM client for command execution (must not be null)
     * @param toolExecutionManager
     *            The manager user-invoked skills dispatch tools through (must not be null)
     * @return A new DefaultCommandExecutionManager instance (never null)
     */
    protected CommandExecutionManager createDefaultCommandExecutionManager(LlmClient llmClient,
            ToolExecutionManager toolExecutionManager) {
        return new DefaultCommandExecutionManager(llmClient, toolExecutionManager);
    }

}
