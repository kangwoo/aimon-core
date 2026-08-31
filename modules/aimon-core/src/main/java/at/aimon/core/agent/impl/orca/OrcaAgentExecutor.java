package at.aimon.core.agent.impl.orca;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentContent;
import at.aimon.core.agent.AgentContentRenderer;
import at.aimon.core.agent.AgentEnvironmentSnapshot;
import at.aimon.core.agent.AgentEnvironmentSnapshotProvider;
import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.budget.BudgetDecision;
import at.aimon.core.agent.budget.BudgetTracker;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.NoOpCompactionGuard;
import at.aimon.core.agent.compact.NoOpPromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.PromptSizeRecoveryDecision;
import at.aimon.core.agent.compact.PromptSizeRecoveryStrategy;
import at.aimon.core.agent.context.ContextAssembler;
import at.aimon.core.agent.context.ContextAssemblyRequest;
import at.aimon.core.agent.context.ContextBlock;
import at.aimon.core.agent.context.ContextBlockKind;
import at.aimon.core.agent.exception.ContextWindowExceededException;
import at.aimon.core.agent.impl.orca.tool.OrcaSkillForkExecutorResolver;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.CancellationSignals;
import at.aimon.core.agent.interrupt.CancelledExecutionException;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.SignalBackedLlmCancellation;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.loop.LoopTransition;
import at.aimon.core.agent.loop.LoopTransitionReason;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.prompt.SystemReminderFormatter;
import at.aimon.core.agent.prompt.UserContextMessageBuilder;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantTextDelta;
import at.aimon.core.agent.stream.AssistantTextStreamCompleted;
import at.aimon.core.agent.stream.AssistantTextStreamReset;
import at.aimon.core.agent.stream.CompactBoundary;
import at.aimon.core.agent.stream.ExecutionCompleted;
import at.aimon.core.agent.stream.ExecutionError;
import at.aimon.core.agent.stream.SkillTurnSuspendedEvent;
import at.aimon.core.agent.stream.StreamingAgentExecutor;
import at.aimon.core.agent.stream.ToolResultReady;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ParallelToolDispatcher;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.StreamingToolScheduler;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolContextEnrichmentInfo;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.search.ToolSearchCatalog;
import at.aimon.core.agent.tool.search.ToolSearchRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.command.CommandExecutionManager;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.exception.ExecutionBlockedByHookException;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.MessageArtifact;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.cost.CostEstimator;
import at.aimon.core.llm.cost.CostSummary;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.llm.retry.LlmFallbackPolicy;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamTarget;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.memory.MemoryContextProvider;
import at.aimon.core.memory.MemoryContextRequest;
import at.aimon.core.skill.execution.SkillToolDispatcher;
import at.aimon.core.skill.fork.SkillForkExecutor;
import at.aimon.core.skill.policy.SkillPreflightScanResult;
import at.aimon.core.skill.policy.SkillPreflightScanner;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurn;
import at.aimon.core.skill.policy.pending.PendingTurnId;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.toolinvocation.SingleToolInvoker;
import at.aimon.core.toolinvocation.ToolInvocationSpec;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.core.tools.todo.TodoWriteTool;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * Core agent implementation using the ReAct (Reasoning and Acting) pattern.
 *
 * <p>
 * The ReAct pattern combines reasoning and acting in an iterative loop:
 *
 * <ol>
 * <li><b>Thought:</b> The agent analyzes the current situation and plans the next action
 * <li><b>Action:</b> The agent uses tools to gather information or perform operations
 * <li><b>Observation:</b> The agent observes the results of the action
 * <li>Repeat until the goal is achieved or max iterations reached
 * </ol>
 *
 * <p>
 * Thread-safe if the provided LlmClient and ToolExecutor are thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create executor with required components
 *     OrcaAgentExecutor executor = new OrcaAgentExecutor(llmClient, sessionRecordStore, toolExecutionManager,
 *             hookExecutionManager, commandExecutionManager, subagentExecutionManager);
 *
 *     // Build the agent runtime with agent, tools, and environment
 *     OrcaAgentRuntime context = OrcaAgentRuntime.builder().agent(agent).toolRegistry(toolRegistry)
 *             .hookRegistry(hookRegistry).commandRegistry(commandRegistry).environment(environment).build();
 *
 *     // Build execution request with user input and optional session ID
 *     OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder()
 *             .userInput(UserInput.text("What files are in the current directory?")).sessionId("session-123")
 *             .build();
 *
 *     // Execute and get result
 *     OrcaAgentExecutionResult result = executor.execute(context, request);
 *     System.out.println(result.getFinalAnswer());
 * }
 * </pre>
 */
public class OrcaAgentExecutor
        implements
            AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult>,
            StreamingAgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> {
    private static final Logger log = LoggerFactory.getLogger(OrcaAgentExecutor.class);

    /**
     * Reminder key used to wrap mid-turn injected queue messages.
     *
     * <p>
     * This is observable via the {@code <system-reminder key="..."} marker in the injected user message content.
     */
    public static final String MID_TURN_INJECTION_KEY = "user-mid-turn-message";

    /**
     * Marker appended to a final assistant turn that the provider cut off at its max-output-token limit
     * ({@link StopReason#MAX_TOKENS}). The partial text is still surfaced to the caller, but this suffix makes the
     * truncation explicit both to the human reader and to any downstream consumer inspecting the final answer.
     */
    public static final String TRUNCATION_MARKER = "\n\n[System: response truncated at max_tokens]";

    /**
     * Death-spiral guard: the number of <em>consecutive</em> stalled iterations tolerated before the ReAct loop
     * aborts with {@link CompletionReason#ERROR}. An iteration is "stalled" when it issued tool calls but every one of
     * them failed (see {@link #isStalledIteration(List)}) — i.e. the model kept acting but made zero forward progress.
     * Once this many stalled iterations land back-to-back, the loop stops driving new LLM calls instead of burning the
     * remaining budget on a request that is not converging.
     */
    public static final int MAX_CONSECUTIVE_STALLED_ITERATIONS = 3;

    /**
     * The {@code tool_result} content returned for a {@code tool_use} that never started because the turn was
     * interrupted first. Phrased for the model, which may see it if the conversation is resumed: it explains that the
     * call was cancelled, not that the tool failed.
     */
    static final String INTERRUPTED_TOOL_SKIP_MESSAGE = "Interrupted — skipped: the turn was cancelled before this "
            + "tool started";

    /**
     * Tracing-span attribute key naming the {@link LoopTransitionReason} that re-entered this iteration. Present
     * only on ITERATION spans for iteration 2+ (the first iteration is the loop's entry, not a re-entry).
     */
    public static final String LOOP_TRANSITION_ATTR = "loop.transition";

    /** Tracing-span attribute key carrying the one-based iteration a {@link LoopTransition} introduced. */
    public static final String LOOP_TRANSITION_ITERATION_ATTR = "loop.transition.iteration";

    /**
     * Tracing-span attribute key carrying a {@link LoopTransition}'s optional secondary note (omitted if absent).
     */
    public static final String LOOP_TRANSITION_NOTE_ATTR = "loop.transition.note";

    /**
     * {@link CompactBoundary#getStrategyName() strategy name} reported when a compaction step runs before an
     * iteration. The conversation-compaction subsystem performs L3 summarisation (an LLM-generated summary replaces a
     * span of history — see {@code CompactionEngine}), so the strategy is fixed here rather than derived from
     * {@code CompactionMetadata}, which carries only the <em>trigger</em> (AUTO/MANUAL) and token deltas, not a
     * strategy label. The trigger dimension is already observable via the {@code BUDGET_COMPACT}
     * {@link LoopTransition} tag on the iteration span.
     */
    public static final String COMPACTION_STRATEGY_NAME = "summarization";

    private final LlmCallGateway<TranscriptBuffer> gateway;
    private final TranscriptManager transcriptManager;

    private final ToolExecutionManager toolExecutionManager;
    private final HookExecutionManager hookExecutionManager;
    private final CommandExecutionManager commandExecutionManager;
    private final SubagentExecutionManager subagentExecutionManager;

    /**
     * Shared per-tool invocation pipeline; identical logic drives the subagent {@code DefaultSubagentExecutor}.
     */
    private final SingleToolInvoker singleToolInvoker;

    /**
     * Optional message queue manager for mid-turn injection. When {@code null}, mid-turn injection is a no-op and the
     * executor behaves exactly as it did before CQ-03. See
     * {@link #injectQueuedMessages(ExecutionScope)} for the drain contract.
     */
    private final MessageQueueManager messageQueueManager;

    /** Renders the dynamic system prompt (agent content + environment + memory + assembled SYSTEM blocks). */
    private final SystemPromptRenderer systemPromptRenderer;

    /**
     * Optional dependency: when configured, a synthetic {@code <system-reminder>}-wrapped user-context message is
     * injected as {@code messages[0]} for fresh conversations (CTX-06). When {@code null}, injection is skipped with
     * a debug log.
     */
    private final AgentEnvironmentSnapshotProvider agentEnvironmentSnapshotProvider;

    /**
     * STREAM-03: package-private fan-out helper that dispatches {@link AgentExecutionEvent}s to listeners registered
     * via {@link #addEventListener(Consumer)}. Zero listeners is the common path and incurs only a null-check plus an
     * {@code isEmpty()} read.
     */
    private final EventEmitter eventEmitter = new EventEmitter();

    /**
     * Factory for the per-turn {@link InterruptCoordinator}. Invoked once per
     * {@link #executeReActLoop(ExecutionScope)} call; the returned coordinator owns the turn-scoped
     * {@link CancellationSignal} injected into every {@link ToolContext} and the
     * {@link TerminatorRegistrar}s issued to {@link InterruptBehavior#THREAD_INTERRUPT}/
     * {@link InterruptBehavior#EXTERNALLY_TERMINATED} tools.
     *
     * <p>
     * Package-private so tests inside {@code at.aimon.core.agent.impl.orca} can substitute a supplier that retains the
     * coordinator instance, letting them trip the signal from the test side. Production paths must not mutate this
     * field.
     */
    Supplier<InterruptCoordinator> interruptCoordinatorFactory = DefaultInterruptCoordinator::new;

    /**
     * PAR-03: dispatcher that decides, per tool batch, whether independent {@code CONCURRENT_SAFE} tools run in
     * parallel. Defaults to a sequential dispatcher (parallel execution disabled) so behaviour is unchanged unless a
     * parallel-enabled dispatcher is injected via {@link Builder#parallelToolDispatcher(ParallelToolDispatcher)}.
     *
     * <p>
     * Package-private and non-final, mirroring {@link #interruptCoordinatorFactory}: a defaulted collaborator the
     * builder (and tests inside {@code at.aimon.core.agent.impl.orca}) can override without threading another argument
     * through the telescoping constructor chain. Production paths must not mutate this after construction.
     *
     * <p>
     * {@code volatile}: the builder ({@link Builder#build()}) and {@link OrcaAgentExecutorFactory} write it
     * <em>after</em> construction, while request threads read it in {@code executeToolUses}. The executor is an
     * agent-scoped singleton shared across concurrent sessions, so without {@code volatile} a reader could observe
     * the default sequential dispatcher instead of an injected parallel one.
     */
    volatile ParallelToolDispatcher parallelToolDispatcher = DefaultParallelToolDispatcher.sequential();

    /**
     * Ceiling on the {@link SideEffectLevel} a tool may declare and still be offered to the LLM. Defaults to
     * {@link SideEffectLevel#MUTATING}, which permits every tool — behaviour is unchanged unless a stricter ceiling is
     * injected via {@link OrcaAgentExecutorFactory#withMaxSideEffectLevel(SideEffectLevel)}.
     *
     * <p>
     * Filtering here keeps blocked tools out of the LLM's tool list entirely, so no iteration is spent proposing a call
     * that would be refused. It is <em>not</em> the enforcement point: a model can name a tool it was never shown, so
     * {@code DefaultToolExecutionManager} carries the same ceiling and refuses at execution time.
     *
     * <p>
     * {@code volatile} for the same reason as {@link #parallelToolDispatcher} — written after construction, read by
     * request threads on an agent-scoped singleton shared across concurrent sessions.
     */
    volatile SideEffectLevel maxSideEffectLevel = SideEffectLevel.MUTATING;

    /**
     * TRACE-01: records a per-turn {@code TraceSpan} tree (turn → llm/tool) for LangSmith-style debugging. Defaults to
     * {@link Tracer#noop()} (zero overhead); a real tracer is injected via {@link Builder#tracer(Tracer)}. Like
     * {@link #parallelToolDispatcher} this is {@code volatile} because the executor is an agent-scoped singleton shared
     * across concurrent sessions.
     */
    volatile Tracer tracer = Tracer.noop();

    /**
     * TRACE-02: governs whether tool result content is captured in {@code TOOL} span outputs (in addition to the
     * {@code isError}/{@code contentChars} summary). Defaults to {@link TracePayloadPolicy#summaryOnly()} (summary
     * only,
     * no content); a content-capturing policy is injected via {@link Builder#tracePayloadPolicy(TracePayloadPolicy)}.
     * {@code volatile} for the same reason as {@link #tracer} — the executor is an agent-scoped singleton shared across
     * concurrent sessions.
     */
    volatile TracePayloadPolicy tracePayloadPolicy = TracePayloadPolicy.summaryOnly();

    /**
     * Prices each LLM call so per-model cost can be accumulated into the execution's {@link CostSummary} and (when
     * a
     * {@link at.aimon.core.agent.budget.ExecutionBudget#getMaxCostUsd() cost budget} is set) enforced as a hard STOP.
     * Defaults to {@link CostEstimator#NOOP} (every call priced at zero), so cost tracking is entirely opt-in and
     * wiring nothing leaves behaviour unchanged. {@code volatile} for the same reason as {@link #tracer} — the executor
     * is an agent-scoped singleton written after construction and read from concurrent request threads.
     */
    volatile CostEstimator costEstimator = CostEstimator.NOOP;

    /**
     * Assembles the runtime context (environment / git branch / directory summary / user extensions / between-turn
     * attachments) injected into each turn. Defaults to {@link ContextAssembler#NOOP} (assembles nothing), so context
     * assembly is entirely opt-in and wiring nothing leaves the prompt shape unchanged. {@code volatile} for the same
     * reason as {@link #costEstimator} — the executor is an agent-scoped singleton written after construction and read
     * from concurrent request threads.
     */
    volatile ContextAssembler contextAssembler = ContextAssembler.NOOP;

    /**
     * PSTREAM-09: when {@code true}, the ReAct loop routes LLM calls through
     * {@link LlmCallGateway#sendMessageStreaming} so the caller observes token-level
     * {@link AssistantTextDelta} events. When {@code false} (the default), the legacy non-streaming path is used.
     */
    private final boolean useStreaming;

    /**
     * PSTREAM-09: per-call options threaded into the streaming gateway. Only consulted when {@link #useStreaming} is
     * {@code true}; otherwise ignored. Never {@code null} when streaming is enabled — defaults to
     * {@link LlmStreamingOptions#defaults()}.
     */
    private final LlmStreamingOptions streamingOptions;

    /**
     * SK-11.4: optional pre-flight scanner that inspects {@code Skill} tool_uses returned by the LLM and decides
     * whether the turn must be suspended pending out-of-band approval. When {@code null}, no scanning is performed and
     * skill invocations follow the per-tool {@code SkillTool} policy path (so {@link
     * at.aimon.core.skill.policy.SkillInvocationDecision#ASK} surfaces as a per-tool error — fail-closed for
     * headless contexts).
     */
    private final SkillPreflightScanner skillPreflightScanner;

    /**
     * SK-11.4: optional registry that records suspended turns. Required iff {@link #skillPreflightScanner} is non-null
     * — without a registry there is no way to surface the suspension to an approval channel. Validated at construction
     * time.
     */
    private final PendingTurnRegistry pendingTurnRegistry;

    /**
     * SK-11.4: TTL applied when a pre-flight scan suspends a turn. Conservative default (30 minutes) lets a user step
     * away briefly without losing the prompt; the timeout reaper (SK-11.5) drops expired entries.
     */
    public static final Duration DEFAULT_PENDING_TURN_TTL = Duration.ofMinutes(30);

    /**
     * How long a suspended turn waits for its approval, defaulting to {@link #DEFAULT_PENDING_TURN_TTL}.
     *
     * <p>
     * The right value follows from who answers rather than from the value itself. Thirty minutes suits a terminal,
     * where the person who was asked is the person sitting there. It is far too long for a service whose approvals
     * arrive from a queue that is either draining or down — a suspended turn holds its place in
     * {@code PendingTurnRegistry} until the reaper takes it, so a backlog of them is memory nothing will release.
     * It is far too short for an approval that goes to a human on another shift.
     */
    private final Duration pendingTurnTtl;

    /**
     * Creates a new OrcaAgentExecutor backed by a pre-configured {@link LlmCallGateway}.
     *
     * <p>
     * This is the preferred constructor: callers that want retry/fallback/prompt-too-long semantics inject a fully
     * configured gateway. The ReAct loop invokes {@link LlmCallGateway} for every LLM call.
     *
     * @param gateway
     *            The LLM call gateway wrapping the underlying {@link LlmClient} with retry/fallback/PTL semantics
     *            (must not be null)
     * @param transcriptManager
     *            The transcript manager for managing session lifecycle (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager for executing tools (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager for executing hooks (must not be null)
     * @param commandExecutionManager
     *            The command execution manager for executing commands (must not be null)
     * @param subagentExecutionManager
     *            The subagent execution manager for managing subagents (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public OrcaAgentExecutor(LlmCallGateway<TranscriptBuffer> gateway, TranscriptManager transcriptManager,
            ToolExecutionManager toolExecutionManager, HookExecutionManager hookExecutionManager,
            CommandExecutionManager commandExecutionManager, SubagentExecutionManager subagentExecutionManager) {
        this(builder().gateway(gateway).transcriptManager(transcriptManager).toolExecutionManager(toolExecutionManager)
                .hookExecutionManager(hookExecutionManager).commandExecutionManager(commandExecutionManager)
                .subagentExecutionManager(subagentExecutionManager));
    }

    /**
     * Creates a new OrcaAgentExecutor with an optional {@link MessageQueueManager} for CQ-03 mid-turn injection.
     *
     * <p>
     * Convenience overload that delegates to the primary constructor passing {@code null} for
     * {@code agentEnvironmentSnapshotProvider}. See the primary constructor for full semantics.
     *
     * @param gateway
     *            The LLM call gateway wrapping the underlying {@link LlmClient} with retry/fallback/PTL semantics
     *            (must not be null)
     * @param transcriptManager
     *            The transcript manager for managing session lifecycle (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager for executing tools (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager for executing hooks (must not be null)
     * @param commandExecutionManager
     *            The command execution manager for executing commands (must not be null)
     * @param subagentExecutionManager
     *            The subagent execution manager for managing subagents (must not be null)
     * @param messageQueueManager
     *            The message queue manager for mid-turn injection, or {@code null} to disable mid-turn injection
     * @throws NullPointerException
     *             if any non-optional parameter is null
     */
    // checkstyle: ParameterNumber — existing warning, acknowledged; see CQ-03 design note.
    public OrcaAgentExecutor(LlmCallGateway<TranscriptBuffer> gateway, TranscriptManager transcriptManager,
            ToolExecutionManager toolExecutionManager, HookExecutionManager hookExecutionManager,
            CommandExecutionManager commandExecutionManager, SubagentExecutionManager subagentExecutionManager,
            MessageQueueManager messageQueueManager) {
        this(builder().gateway(gateway).transcriptManager(transcriptManager).toolExecutionManager(toolExecutionManager)
                .hookExecutionManager(hookExecutionManager).commandExecutionManager(commandExecutionManager)
                .subagentExecutionManager(subagentExecutionManager).messageQueueManager(messageQueueManager));
    }

    /**
     * Canonical constructor. Reads every dependency from the {@link Builder}, applies the same defaults and null-checks
     * the former telescoping constructors used, and folds in the post-build collaborator overrides
     * ({@link #parallelToolDispatcher}, {@link #tracer}, {@link #tracePayloadPolicy}, {@link #costEstimator},
     * {@link #contextAssembler}) so construction is atomic. Reachable only through {@link Builder#build()} and the
     * public convenience constructors, which funnel here.
     *
     * @param builder
     *            the populated builder (never null)
     */
    private OrcaAgentExecutor(Builder builder) {
        this.gateway = Objects.requireNonNull(builder.gateway, "gateway cannot be null");
        this.transcriptManager = Objects.requireNonNull(builder.transcriptManager, "Transcript manager cannot be null");
        this.toolExecutionManager = Objects.requireNonNull(builder.toolExecutionManager,
                "Tool execution manager cannot be null");
        this.hookExecutionManager = Objects.requireNonNull(builder.hookExecutionManager, "Hook handler cannot be null");
        this.singleToolInvoker = new SingleToolInvoker(toolExecutionManager, hookExecutionManager,
                builder.approvalGate);
        this.commandExecutionManager = Objects.requireNonNull(builder.commandExecutionManager,
                "Command handler cannot be null");
        this.subagentExecutionManager = Objects.requireNonNull(builder.subagentExecutionManager,
                "Subagent execution manager cannot be null");
        this.messageQueueManager = builder.messageQueueManager;
        this.agentEnvironmentSnapshotProvider = builder.agentEnvironmentSnapshotProvider;
        this.useStreaming = builder.useStreaming;
        this.streamingOptions = builder.useStreaming && builder.streamingOptions == null
                ? LlmStreamingOptions.defaults()
                : builder.streamingOptions;
        if (!builder.useStreaming && builder.streamingOptions != null) {
            log.debug("streamingOptions supplied but useStreaming=false; options will be ignored");
        }
        if (builder.skillPreflightScanner != null && builder.pendingTurnRegistry == null) {
            throw new IllegalArgumentException(
                    "pendingTurnRegistry must be provided when skillPreflightScanner is non-null");
        }
        this.skillPreflightScanner = builder.skillPreflightScanner;
        this.pendingTurnRegistry = builder.pendingTurnRegistry;
        this.pendingTurnTtl = builder.pendingTurnTtl == null ? DEFAULT_PENDING_TURN_TTL : builder.pendingTurnTtl;
        if (this.pendingTurnTtl.isNegative() || this.pendingTurnTtl.isZero()) {
            // A zero TTL is not "expire immediately" in any useful sense: the turn would be registered and reaped
            // before the approval it is waiting for could be written, so every ASK would suspend and then vanish.
            throw new IllegalArgumentException("pendingTurnTtl must be positive: " + this.pendingTurnTtl);
        }
        this.systemPromptRenderer = new SystemPromptRenderer(new AgentContentRenderer(), builder.memoryContextProvider);
        // Fold in the post-build collaborator overrides so construction is atomic. Each field carries a non-null
        // default from its declaration; only a builder-supplied override replaces it.
        if (builder.parallelToolDispatcher != null) {
            this.parallelToolDispatcher = builder.parallelToolDispatcher;
        }
        if (builder.tracer != null) {
            this.tracer = builder.tracer;
        }
        if (builder.tracePayloadPolicy != null) {
            this.tracePayloadPolicy = builder.tracePayloadPolicy;
        }
        if (builder.costEstimator != null) {
            this.costEstimator = builder.costEstimator;
        }
        if (builder.contextAssembler != null) {
            this.contextAssembler = builder.contextAssembler;
        }
    }

    /**
     * Creates a new OrcaAgentExecutor from a raw {@link LlmClient}, auto-wrapping it in a pass-through
     * {@link LlmCallGateway} that uses {@link LlmRetryPolicy#defaultPolicy()} and {@link LlmFallbackPolicy#none()}
     * and no {@link at.aimon.core.llm.invoke.PromptTooLongHandler} (so prompt-too-long errors are rethrown as-is,
     * preserving legacy behavior).
     *
     * <p>
     * Prefer {@link #OrcaAgentExecutor(LlmCallGateway, TranscriptManager, ToolExecutionManager,
     * HookExecutionManager, CommandExecutionManager, SubagentExecutionManager)} when you need custom retry/fallback
     * semantics or a concrete prompt-too-long handler.
     *
     * @param llmClient
     *            The LLM client for communicating with language models (must not be null)
     * @param transcriptManager
     *            The transcript manager for managing session lifecycle (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager for executing tools (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager for executing hooks (must not be null)
     * @param commandExecutionManager
     *            The command execution manager for executing commands (must not be null)
     * @param subagentExecutionManager
     *            The subagent execution manager for managing subagents (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public OrcaAgentExecutor(LlmClient llmClient, TranscriptManager transcriptManager,
            ToolExecutionManager toolExecutionManager, HookExecutionManager hookExecutionManager,
            CommandExecutionManager commandExecutionManager, SubagentExecutionManager subagentExecutionManager) {
        this(LlmCallGateway.<TranscriptBuffer>withDefaultRetry(llmClient), transcriptManager, toolExecutionManager,
                hookExecutionManager, commandExecutionManager, subagentExecutionManager);
    }

    /**
     * Returns a {@link Builder} for constructing an {@code OrcaAgentExecutor} without going through the telescoping
     * constructor chain. Required dependencies must be set; optional fields default to {@code null}/legacy behavior.
     *
     * <p>
     * Preferred for new call sites; existing constructors are kept for back-compat.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder that replaces the telescoping constructor chain. Use {@link OrcaAgentExecutor#builder()} to
     * obtain an instance. Required fields are validated in {@link #build()}; optional fields default to legacy
     * behaviour (no streaming, no skill pre-flight, no memory injection, etc.).
     */
    public static final class Builder {
        private LlmCallGateway<TranscriptBuffer> gateway;
        private TranscriptManager transcriptManager;
        private ToolExecutionManager toolExecutionManager;
        private HookExecutionManager hookExecutionManager;
        private SideEffectApprovalGate approvalGate;
        private CommandExecutionManager commandExecutionManager;
        private SubagentExecutionManager subagentExecutionManager;
        private MessageQueueManager messageQueueManager;
        private AgentEnvironmentSnapshotProvider agentEnvironmentSnapshotProvider;
        private boolean useStreaming;
        private LlmStreamingOptions streamingOptions;
        private SkillPreflightScanner skillPreflightScanner;
        private PendingTurnRegistry pendingTurnRegistry;
        private Duration pendingTurnTtl;
        private MemoryContextProvider memoryContextProvider;
        private ParallelToolDispatcher parallelToolDispatcher;
        private Tracer tracer;
        private TracePayloadPolicy tracePayloadPolicy;
        private CostEstimator costEstimator;
        private ContextAssembler contextAssembler;

        private Builder() {
        }

        public Builder gateway(LlmCallGateway<TranscriptBuffer> gateway) {
            this.gateway = gateway;
            return this;
        }

        public Builder llmClient(LlmClient llmClient) {
            this.gateway = LlmCallGateway.<TranscriptBuffer>withDefaultRetry(llmClient);
            return this;
        }

        public Builder transcriptManager(TranscriptManager transcriptManager) {
            this.transcriptManager = transcriptManager;
            return this;
        }

        public Builder toolExecutionManager(ToolExecutionManager toolExecutionManager) {
            this.toolExecutionManager = toolExecutionManager;
            return this;
        }

        public Builder hookExecutionManager(HookExecutionManager hookExecutionManager) {
            this.hookExecutionManager = hookExecutionManager;
            return this;
        }

        /**
         * Sets the gate that requires user approval before a tool declaring a side effect runs. Unlike the other
         * executor overrides this one is set at construction, because the {@link SingleToolInvoker} that consults it
         * is built in the constructor.
         *
         * @param approvalGate
         *            the gate, or {@code null} (the default) to run without approval prompts
         * @return this builder
         */
        public Builder approvalGate(SideEffectApprovalGate approvalGate) {
            this.approvalGate = approvalGate;
            return this;
        }

        public Builder commandExecutionManager(CommandExecutionManager commandExecutionManager) {
            this.commandExecutionManager = commandExecutionManager;
            return this;
        }

        public Builder subagentExecutionManager(SubagentExecutionManager subagentExecutionManager) {
            this.subagentExecutionManager = subagentExecutionManager;
            return this;
        }

        public Builder messageQueueManager(MessageQueueManager messageQueueManager) {
            this.messageQueueManager = messageQueueManager;
            return this;
        }

        public Builder agentEnvironmentSnapshotProvider(
                AgentEnvironmentSnapshotProvider agentEnvironmentSnapshotProvider) {
            this.agentEnvironmentSnapshotProvider = agentEnvironmentSnapshotProvider;
            return this;
        }

        public Builder useStreaming(boolean useStreaming) {
            this.useStreaming = useStreaming;
            return this;
        }

        public Builder streamingOptions(LlmStreamingOptions streamingOptions) {
            this.streamingOptions = streamingOptions;
            return this;
        }

        public Builder skillPreflightScanner(SkillPreflightScanner skillPreflightScanner) {
            this.skillPreflightScanner = skillPreflightScanner;
            return this;
        }

        public Builder pendingTurnRegistry(PendingTurnRegistry pendingTurnRegistry) {
            this.pendingTurnRegistry = pendingTurnRegistry;
            return this;
        }

        /**
         * SK-11.4: sets how long a suspended turn waits before the reaper drops it.
         *
         * @param pendingTurnTtl
         *            the TTL; must be positive, or {@code null} to keep
         *            {@link OrcaAgentExecutor#DEFAULT_PENDING_TURN_TTL}
         * @return this builder
         */
        public Builder pendingTurnTtl(Duration pendingTurnTtl) {
            this.pendingTurnTtl = pendingTurnTtl;
            return this;
        }

        public Builder memoryContextProvider(MemoryContextProvider memoryContextProvider) {
            this.memoryContextProvider = memoryContextProvider;
            return this;
        }

        /**
         * PAR-03/PAR-06: injects the dispatcher that may parallelise batches of {@code CONCURRENT_SAFE} tools. When
         * unset (or {@code null}), the executor keeps its default sequential dispatcher and behaviour is unchanged.
         */
        public Builder parallelToolDispatcher(ParallelToolDispatcher parallelToolDispatcher) {
            this.parallelToolDispatcher = parallelToolDispatcher;
            return this;
        }

        /**
         * TRACE-01: injects the tracer that records the per-turn span tree. When unset (or {@code null}), the executor
         * keeps {@link Tracer#noop()} and behaviour is unchanged.
         */
        public Builder tracer(Tracer tracer) {
            this.tracer = tracer;
            return this;
        }

        /**
         * TRACE-02: injects the payload capture policy governing whether tool result content is captured in TOOL span
         * outputs. When unset (or {@code null}), the executor keeps {@link TracePayloadPolicy#summaryOnly()} and only
         * the summary is recorded.
         */
        public Builder tracePayloadPolicy(TracePayloadPolicy tracePayloadPolicy) {
            this.tracePayloadPolicy = tracePayloadPolicy;
            return this;
        }

        /**
         * Injects the estimator that prices each LLM call for per-model cost tracking and optional cost-budget
         * enforcement. When unset (or {@code null}), the executor keeps {@link CostEstimator#NOOP} (every call priced
         * at
         * zero) and behaviour is unchanged.
         */
        public Builder costEstimator(CostEstimator costEstimator) {
            this.costEstimator = costEstimator;
            return this;
        }

        /**
         * Injects the assembler that builds the runtime context blocks (environment / git / directory summary /
         * user extensions / attachments) injected into each turn. When unset (or {@code null}), the executor keeps
         * {@link ContextAssembler#NOOP} (assembles nothing) and the prompt shape is unchanged.
         */
        public Builder contextAssembler(ContextAssembler contextAssembler) {
            this.contextAssembler = contextAssembler;
            return this;
        }

        public OrcaAgentExecutor build() {
            // Validate required fields up front so the failure message identifies the missing builder field
            // (rather than surfacing a generic "X cannot be null" from the underlying constructor).
            Objects.requireNonNull(gateway, "gateway is required (use Builder.gateway(...) or Builder.llmClient(...))");
            Objects.requireNonNull(transcriptManager, "transcriptManager is required");
            Objects.requireNonNull(toolExecutionManager, "toolExecutionManager is required");
            Objects.requireNonNull(hookExecutionManager, "hookExecutionManager is required");
            Objects.requireNonNull(commandExecutionManager, "commandExecutionManager is required");
            Objects.requireNonNull(subagentExecutionManager, "subagentExecutionManager is required");
            return new OrcaAgentExecutor(this);
        }
    }

    /**
     * Creates a tool context with the necessary environment information and artifact collector.
     *
     * @param scope
     *            The execution scope containing context, user info, attributes, and artifact collector
     * @param sessionRegistry
     *            The per-session tool registry (may be a {@link ToolSearchRegistry})
     * @param cancellationSignal
     *            The turn-scoped cancellation signal that cooperative tools poll (must not be null)
     * @param messageQueueManager
     *            The session-scoped message queue, or {@code null} when none is configured. Published so the
     *            {@code Task} tool can forward it to a background subagent for guaranteed completion notification.
     * @param agentEventSink
     *            A bound reference to this executor's event emitter (never null). Published so the {@code Task}
     *            tool can forward it to a background subagent for best-effort {@code SubagentTaskCompleted} emission.
     * @return The tool context (never null)
     */
    private static ToolContext createToolContext(ExecutionScope scope, ToolRegistry sessionRegistry,
            CancellationSignal cancellationSignal, MessageQueueManager messageQueueManager,
            Consumer<AgentExecutionEvent> agentEventSink) {
        final ToolContext.Builder builder = ToolContext.builder();
        builder.put(ToolContextKeys.AGENT_RUNTIME_ID, scope.agentRuntime.getId());
        builder.put(ToolContextKeys.SESSION_ID, scope.transcriptBuffer.getSessionId());

        // Add environment if available
        final Environment environment = scope.getEnvironment();
        if (environment != null) {
            builder.put(ToolContextKeys.ENVIRONMENT_KEY, environment);
        }
        if (scope.getPrincipal() != null) {
            builder.put(ToolContextKeys.PRINCIPAL, scope.getPrincipal());
        }

        builder.put(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY, scope.getExecutionAttributes());
        builder.put(ToolContextKeys.LLM_CALL_METADATA_KEY, scope.llmCallMetadata);
        builder.put(ToolContextKeys.ARTIFACT_COLLECTOR, scope.artifactCollector);
        builder.put(TodoWriteTool.CONTEXT_ID_KEY, scope.transcriptBuffer.getSessionId().value());

        // PAR-05: inject a thread-safe, turn-scoped read-tracking set. This serves two purposes: (1) it backs
        // EditTool's read-before-edit guard (previously a no-op in production because no executor injected the set),
        // and (2) it is concurrency-safe so parallel CONCURRENT_SAFE Read tools can record reads without racing. The
        // set is created once per turn (createToolContext is called once before the iteration loop), so reads persist
        // across iterations.
        builder.put(ReadTool.READ_FILES_KEY, ConcurrentHashMap.newKeySet());

        // Publish the turn-scoped signal so cooperative tools can poll it through InterruptAccess#signalOf.
        builder.put(InterruptToolKeys.CANCELLATION_SIGNAL, cancellationSignal);

        // Publish the session-scoped message queue (when present) and this executor's event sink so the Task
        // tool can forward both onto a background subagent's environment, enabling completion notification back to the
        // launching agent (guaranteed queue path + best-effort stream event).
        if (messageQueueManager != null) {
            builder.put(ToolContextKeys.MESSAGE_QUEUE_MANAGER, messageQueueManager);
        }
        builder.put(ToolContextKeys.AGENT_EVENT_SINK, agentEventSink);

        // Inject per-session ToolSearchRegistry if applicable
        if (sessionRegistry instanceof ToolSearchRegistry searchRegistry) {
            builder.put(ToolContextKeys.TOOL_SEARCH_REGISTRY, searchRegistry);
        }

        // Inject KnowledgeStore and KnowledgeScope if configured
        scope.agentRuntime.getKnowledgeStore().ifPresent(ks -> {
            builder.put(ToolContextKeys.KNOWLEDGE_STORE, ks);
            builder.put(ToolContextKeys.KNOWLEDGE_SCOPE, new at.aimon.core.knowledge.KnowledgeScope(
                    scope.getAgent().getMetadata().getName(), scope.agentRuntime.getId().value()));
        });

        applyEnrichers(builder, scope);

        return builder.build();
    }

    /**
     * Invokes the agent runtime's {@link ToolContextEnricher enrichers}, isolating each failure so a misbehaving
     * enricher cannot abort tool-context assembly.
     *
     * <p>
     * {@code invokingSessionId} is deliberately left unset: the main agent is the invoker, not an invokee. Its
     * session id is the one every fork below it inherits, so there is no outer session to defer to and
     * {@code getInvokingSessionId().or(this::getSessionId)} correctly collapses to this session. The session id
     * itself, unlike a fork's, is a real one — this run <em>is</em> that session's turn — which is why this path sets
     * it where the fork path sets an execution id instead.
     */
    private static void applyEnrichers(ToolContext.Builder builder, ExecutionScope scope) {
        final List<ToolContextEnricher> enrichers = scope.agentRuntime.getToolContextEnrichers();
        if (enrichers.isEmpty()) {
            return;
        }
        final ToolContextEnrichmentInfo info = ToolContextEnrichmentInfo.builder()
                .sessionId(scope.transcriptBuffer.getSessionId()).agentRuntimeId(scope.agentRuntime.getId()).build();
        for (ToolContextEnricher enricher : enrichers) {
            try {
                enricher.enrich(builder, info);
            } catch (Exception e) {
                log.warn("ToolContextEnricher {} failed: {}", enricher.getClass().getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Creates a per-session tool registry. If the underlying registry is a {@link ToolSearchCatalog}, a new
     * {@link ToolSearchRegistry} is created for session-scoped tool activation. Otherwise, the original registry
     * is
     * returned unchanged.
     *
     * @param toolRegistry
     *            the agent runtime.s tool registry
     * @return the per-session registry
     */
    private static ToolRegistry createSessionRegistry(ToolRegistry toolRegistry) {
        if (toolRegistry instanceof ToolSearchCatalog catalog) {
            return catalog.createRegistry();
        }
        return toolRegistry;
    }

    /**
     * Builds execution metadata for the current execution state.
     *
     * @param iterationCount
     *            The number of iterations completed
     * @param tokenUsage
     *            The accumulated token usage
     * @param startTime
     *            The execution start time
     * @return The execution metadata (never null)
     */
    private static ExecutionMetadata buildExecutionMetadata(int iterationCount, TokenUsage tokenUsage,
            Instant startTime) {
        return ExecutionMetadata.builder().iterationCount(iterationCount).tokenUsage(tokenUsage)
                .timestamps(startTime, Instant.now()).build();
    }

    /**
     * Determines the appropriate error message based on the exception type.
     *
     * @param exception
     *            The exception that occurred
     * @return The error message (never null)
     */
    private static String determineErrorMessage(Exception exception) {
        if (exception instanceof ExecutionBlockedByHookException e) {
            return e.getMessage();
        } else if (exception instanceof LlmClientException e) {
            return "LLM client error: " + e.getMessage();
        } else {
            return "Unexpected error: " + exception.getMessage();
        }
    }

    /**
     * Logs the error with appropriate log level and message.
     *
     * @param exception
     *            The exception that occurred
     * @param errorMessage
     *            The error message to log
     */
    private static void logError(Exception exception, String errorMessage) {
        if (exception instanceof ExecutionBlockedByHookException) {
            log.warn(errorMessage);
        } else {
            log.error(errorMessage, exception);
        }
    }

    /**
     * Executes the agent with the given agent runtime and request.
     *
     * <p>
     * The agent will use the ReAct pattern to iteratively reason and act until it can provide a final answer or reaches
     * the maximum iteration limit.
     *
     * <p>
     * This method allows runtime injection of tools, configuration, and user information, providing maximum flexibility
     * for dynamic execution.
     *
     * @param agentRuntime
     *            The agent runtime with tools and config (must not be null)
     * @param executionRequest
     *            The execution request with user message and info (must not be null)
     * @return The execution result containing the final answer or error
     * @throws NullPointerException
     *             if any parameter is null
     */
    @Override
    public OrcaAgentExecutionResult execute(OrcaAgentRuntime agentRuntime, OrcaAgentExecutionRequest executionRequest) {
        return execute(agentRuntime, executionRequest, null);
    }

    /**
     * Internal execution entry point that also accepts a per-execution event listener.
     *
     * <p>
     * H1: {@code perExecutionListener} is non-null only for the streaming {@link #events} / {@link #executeAsync}
     * surfaces. It is registered on this turn's own {@link ExecutionScope#eventSink} rather than the executor-wide
     * shared emitter, so concurrent sessions of the same agent never observe each other's events. The
     * synchronous 2-arg {@link #execute(OrcaAgentRuntime, OrcaAgentExecutionRequest)} passes {@code null}.
     */
    private OrcaAgentExecutionResult execute(OrcaAgentRuntime agentRuntime, OrcaAgentExecutionRequest executionRequest,
            Consumer<AgentExecutionEvent> perExecutionListener) {
        Objects.requireNonNull(agentRuntime, "Agent runtime cannot be null");
        Objects.requireNonNull(executionRequest, "Execution request cannot be null");

        log.debug("Starting agent execution");

        final Agent agent = agentRuntime.getAgent();

        // Assemble the runtime context blocks once at turn start. Empty under the NOOP assembler, so the prompt
        // shape is unchanged unless an assembler is wired. SYSTEM blocks fold into the system prompt; USER_PREPEND /
        // ATTACHMENT blocks are injected as synthetic user-role <system-reminder> messages below.
        final List<ContextBlock> assembledContext = assembleContext(agentRuntime, agent);

        // Build dynamic system prompt as structured parts (CTX-05). The concatenated() form is stored in
        // TranscriptBuffer to preserve the on-disk/session representation, while the parts form is passed
        // directly to the parts-aware LlmClient.sendMessage overload inside the ReAct loop.
        final SystemPromptParts systemPromptParts = systemPromptRenderer.buildSystemPromptParts(agent.getContent(),
                executionRequest.getSystemPromptVariables(), agentRuntime.getEnvironment(), assembledContext,
                buildMemoryContextRequest(executionRequest));
        final String systemPrompt = systemPromptRenderer.renderSystemPromptString(systemPromptParts, agent.getContent(),
                executionRequest.getSystemPromptVariables(), agentRuntime.getEnvironment());

        // Initialize transcript buffer before try-finally so it is always saved,
        // even when OnStart hooks block the execution.
        //
        // CTX-06: split the init flow into three steps so the synthetic messages[0] user-context block can be
        // inserted BEFORE the real user message, and only for fresh conversations. A conversation is considered
        // "resumed" when its loaded memory already contains at least one user message — in that case injection is
        // skipped to avoid duplicating the synthetic block on every turn.
        final Message userMessage = UserInputConverter.buildUserMessage(executionRequest.getUserInput());
        final TranscriptBuffer transcriptBuffer = transcriptManager.initialize(executionRequest.getSessionId(),
                systemPrompt);
        // Mark where this turn starts before it adds anything at all. The synthetic context blocks below belong to
        // the turn as much as the user message does, so a retry that left them behind would inject a second copy on
        // a fresh conversation and leave an orphaned one on every other.
        //
        // The request's own input is what is recorded, not the message just built from it: a retry re-submits the
        // request and the conversion runs again, so an image, a document or a multimodal turn replays as itself
        // instead of as the text representation that is all a Message can be read back as. The options go with it,
        // because a turn is also who submitted it and under what context — replaying the words alone would run the
        // same request as a different caller.
        transcriptBuffer.beginTurn(executionRequest.getUserInput(), executionRequest.getSubmitOptions());
        maybeInjectUserContextMessage(agentRuntime, executionRequest, transcriptBuffer);
        // Inject the assembled USER_PREPEND / ATTACHMENT blocks (if any) as a synthetic <system-reminder> user
        // message, after the legacy user-context block and before the real user message. No-op when the assembler
        // contributed no such blocks (always the case under the NOOP default).
        injectAssembledUserContext(transcriptBuffer, assembledContext);
        transcriptBuffer.addMessage(userMessage);

        // Resolve effective LLM call metadata: caller-supplied wins, otherwise auto-derive from agent + session.
        final LlmCallMetadata baseMetadata = executionRequest.getLlmCallMetadata()
                .withDefaults(LlmCallMetadata.builder().component(agent.getName()).feature("react-loop")
                        .traceId(transcriptBuffer.getSessionId().value()).build());

        // TRACE-01: mint the per-turn root span and enrich the metadata so downstream LLM (decorator) and tool spans
        // nest under it. enrich(...) is a no-op under Tracer.noop(), so tracing-off leaves the metadata untouched.
        // Fail-safe: tracing must never break the turn. A misbehaving Tracer falls back to a no-op span and the
        // original metadata so the try/finally below (session save) is always reached.
        Tracer.Span turnSpan;
        LlmCallMetadata effectiveMetadata;
        // Snapshot the volatile payload policy once so the turn-span inputs are built against a single, consistent
        // policy (the executor is agent-scoped and the policy may be swapped concurrently); mirrors recordToolOutcome.
        final TracePayloadPolicy payloadPolicy = tracePayloadPolicy;
        try {
            turnSpan = tracer.startRoot(transcriptBuffer.getSessionId().value(), SpanType.TURN,
                    "turn:" + agent.getName(), turnInputs(userMessage.getContent(), systemPrompt, payloadPolicy));
            effectiveMetadata = turnSpan.enrich(baseMetadata);
        } catch (RuntimeException e) {
            log.warn("Tracing setup failed; continuing without a turn span: {}", e.getMessage());
            turnSpan = Tracer.noop().startRoot(transcriptBuffer.getSessionId().value(), SpanType.TURN,
                    "turn:" + agent.getName(), Map.of());
            effectiveMetadata = baseMetadata;
        }

        final BudgetTracker budgetTracker = new BudgetTracker(
                executionRequest.getBudget().orElseGet(ExecutionBudget::unlimited));

        final ExecutionScope scope = new ExecutionScope(agentRuntime, executionRequest, transcriptBuffer,
                systemPromptParts, effectiveMetadata, budgetTracker);
        // TRACE-01: the turn span is the active parent until the ReAct loop swaps in per-iteration spans.
        scope.activeSpan = turnSpan;

        // H1: streaming callers deliver events to THIS execution only. Register their listener on the scope's own
        // sink (before any event is emitted) so concurrent sessions of the same agent cannot observe each
        // other's events. Global addEventListener() observers keep receiving all events via the shared emitter.
        if (perExecutionListener != null) {
            scope.eventSink.addListener(perExecutionListener);
        }

        // Per-turn event dispatcher: fans every AgentExecutionEvent out to the shared emitter (global observers) and
        // this turn's own sink. Built after the sink is wired and before the first event is emitted.
        scope.eventDispatcher = new AgentEventDispatcher(eventEmitter, scope.agentRuntime.getId(), scope.startTime,
                scope.eventSink);

        // Captured so the finally below can tell whether the turn already reported a cancellation as its
        // CompletionReason. Stays null when the turn exits by throwing.
        OrcaAgentExecutionResult turnResult = null;
        try {
            // Execute OnStart hooks and check for blocks
            checkOnStartHooks(scope, userMessage.getContent());

            // Check if the user input is a command (starts with /)
            // Attach the per-model cost summary once here — the single choke point both terminal flows funnel
            // through — so no other completion path needs to thread it. Empty (a no-op wither) when no cost estimator
            // was wired or the command flow ran (no LLM calls).
            final OrcaAgentExecutionResult result = (commandExecutionManager.isCommand(executionRequest)
                    ? executeCommandFlow(scope, executionRequest)
                    : executeReActLoop(scope)).withCostSummary(scope.costSummary);
            recordTurnOutcome(turnSpan, result);
            turnResult = result;
            return result;
        } catch (RuntimeException e) {
            turnSpan.error(e);
            throw e;
        } finally {
            turnSpan.close();
            // Interrupt-flag hygiene: consume any interrupt still live at turn end BEFORE saveSilently — an
            // interrupt-sensitive SessionRecordStore would otherwise abort the persist of the very turn we just
            // finished. Any path that does not cross a checkpoint after the flag was set arrives here with it live:
            // the command flow (which has no checkpoints at all), an interrupt landing after the loop's final tail
            // check, and exits that bypass the checkpoints entirely (an LlmClient that restores the flag and then
            // throws is the reachable example today).
            consumeLingeringInterrupt(scope, "turn end");
            // A turn that was stopped leaves its mark in place, so the partial trail can be taken back out and the
            // turn run again; any other outcome drops it. Cleared before the save rather than after, so what is
            // persisted already says whether this session has something to retry — a process that dies between the
            // two would otherwise leave a completed turn looking interrupted.
            if (!finalisedAsInterrupted(turnResult)) {
                transcriptBuffer.endTurn();
            }
            transcriptManager.saveSilently(transcriptBuffer);
            // The flag is only SWALLOWED when the turn already reported the cancellation as
            // CompletionReason.INTERRUPTED — the result carries that news, so a poisoned thread would add nothing and
            // an embedder driving turns from a reused worker would just see its next blocking call fail. On every
            // other outcome the caller asked this thread to stop and nothing else recorded the request, so eating it
            // would silently break their cancellation protocol: re-arm it. Doing so after saveSilently keeps the
            // persist protected either way.
            if (scope.interruptConsumed && !finalisedAsInterrupted(turnResult)) {
                log.debug("Re-arming the caller's interrupt: turn {} finalised as {}, not INTERRUPTED",
                        transcriptBuffer.getSessionId().value(),
                        turnResult == null ? "an exception" : turnResult.getCompletionReason());
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Interrupt-flag hygiene: consumes a thread interrupt that is still live at a turn-finalisation point and
     * latches the fact on the scope.
     *
     * <p>
     * Two finalisation points call this. {@link #invokeOnStop} runs first, on every terminal path: its hooks execute
     * under {@code DefaultHookExecutor}'s {@code Future#get(timeout)}, whose fail-open policy maps the
     * {@link InterruptedException} a live flag triggers into a successful {@code HookResult} — an OnStop hook would
     * silently never run. {@code execute()}'s finally runs last, guarding {@code saveSilently} and deciding whether
     * the caller's interrupt has to be handed back on the way out.
     *
     * @param scope
     *            the execution scope whose {@code interruptConsumed} latch is set when a live flag is found
     * @param where
     *            short label naming the finalisation point, for the diagnostic log
     * @return {@code true} if a live interrupt was found and cleared
     */
    private static boolean consumeLingeringInterrupt(ExecutionScope scope, String where) {
        if (!Thread.interrupted()) {
            return false;
        }
        scope.interruptConsumed = true;
        log.debug("Cleared a lingering thread interrupt at {} for session {}", where,
                scope.transcriptBuffer.getSessionId().value());
        return true;
    }

    /**
     * Whether the turn finished by reporting the cancellation itself. Only then is swallowing a consumed
     * interrupt lossless — the {@link CompletionReason#INTERRUPTED} on the result already carries the news.
     *
     * @param result
     *            the turn's result, or {@code null} when the turn exited by throwing
     * @return {@code true} if the result reports {@link CompletionReason#INTERRUPTED}
     */
    private static boolean finalisedAsInterrupted(OrcaAgentExecutionResult result) {
        return result != null && result.getCompletionReason() == CompletionReason.INTERRUPTED;
    }

    /**
     * TRACE-01: records the turn span's outcome (success, iterations, completion reason, final-answer size). Only a
     * genuine error message flips the span to ERROR; interrupted/budget stops are reflected via the outputs.
     */
    private static void recordTurnOutcome(Tracer.Span turnSpan, OrcaAgentExecutionResult result) {
        final String finalAnswer = result.getFinalAnswer();
        turnSpan.setOutputs(Map.of("success", result.isSuccess(), "iterations", result.getIterationCount(),
                "completionReason", String.valueOf(result.getCompletionReason()), "finalAnswerChars",
                finalAnswer == null ? 0 : finalAnswer.length()));
        if (!result.isSuccess() && result.getErrorMessage() != null) {
            turnSpan.error(result.getErrorMessage());
        }
    }

    /**
     * Builds the TURN span inputs (TRACE-02). The system prompt is assembled once per turn (outside the ReAct loop), so
     * the turn span is its natural capture point — recording it here keeps each iteration's LLM span lean rather than
     * repeating the near-identical prompt on every call. {@code systemPromptChars} is always recorded as a summary; the
     * full prompt text is attached (truncated to the policy cap) only when {@code policy} captures content. The policy
     * is
     * passed in (rather than read from the field) so the caller can snapshot the volatile once, mirroring
     * {@link #recordToolOutcome}. Secret masking is applied separately by the tracer's {@code SpanRedactor} at storage
     * time.
     *
     * @param userMessage
     *            the rendered user message content for this turn
     * @param systemPrompt
     *            the rendered system prompt string for this turn
     * @param policy
     *            the payload capture policy snapshot governing full-text capture
     * @return a mutable inputs map for the turn span (never null)
     */
    private static Map<String, Object> turnInputs(String userMessage, String systemPrompt, TracePayloadPolicy policy) {
        final Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("userMessage", userMessage);
        inputs.put("systemPromptChars", systemPrompt == null ? 0 : systemPrompt.length());
        if (policy.capturesContent() && systemPrompt != null) {
            inputs.put("systemPrompt", policy.truncate(systemPrompt));
        }
        return inputs;
    }

    /**
     * Injects the synthetic {@code messages[0]} user-context block for fresh conversations (CTX-06).
     *
     * <p>
     * No-op when:
     *
     * <ul>
     * <li>The caller opted out via {@link OrcaAgentExecutionRequest.Builder#userContextInjection(boolean)
     * userContextInjection(false)}.
     * <li>No {@link AgentEnvironmentSnapshotProvider} has been configured on this executor.
     * <li>The conversation was resumed — i.e., memory already contains at least one user message. We cannot rely on
     * "memory is non-empty" alone because systems MAY persist assistant-only turns; checking user messages is the
     * authoritative signal that the conversation has previously carried real user input.
     * <li>The resolved session context materialises to no reminder entries (defensive guard for degenerate inputs).
     * </ul>
     *
     * <p>
     * Otherwise, a user-role message built by {@link UserContextMessageBuilder#build(AgentEnvironmentSnapshot)} is
     * appended
     * BEFORE the real user message, so the LLM sees the synthetic block as {@code messages[0]}.
     *
     * @param agentRuntime
     *            the agent runtime used to resolve the provider entry (must not be null)
     * @param executionRequest
     *            the request carrying the opt-out flag (must not be null)
     * @param transcriptBuffer
     *            the freshly initialised memory to append the synthetic block to (must not be null)
     */
    private void maybeInjectUserContextMessage(OrcaAgentRuntime agentRuntime,
            OrcaAgentExecutionRequest executionRequest, TranscriptBuffer transcriptBuffer) {
        if (!executionRequest.isUserContextInjectionEnabled()) {
            log.debug("User-context injection disabled for this request");
            return;
        }
        if (agentEnvironmentSnapshotProvider == null) {
            log.debug("No AgentEnvironmentSnapshotProvider configured; skipping user-context injection");
            return;
        }
        if (transcriptBuffer.countUserMessages() > 0) {
            log.debug("Conversation resumed (existing user messages present); skipping user-context injection");
            return;
        }

        final AgentEnvironmentSnapshot agentEnvironmentSnapshot = agentEnvironmentSnapshotProvider.get(agentRuntime);
        final var synthetic = UserContextMessageBuilder.build(agentEnvironmentSnapshot);
        if (synthetic.isEmpty()) {
            log.debug("AgentEnvironmentSnapshot yielded no reminder entries; skipping user-context injection");
            return;
        }

        transcriptBuffer.addMessage(synthetic.get());
        log.debug("Injected synthetic user-context message as messages[0]");
    }

    /**
     * Assembles the runtime context blocks for this turn via the wired {@link ContextAssembler}.
     *
     * <p>
     * Returns an empty list under the {@link ContextAssembler#NOOP default} assembler, so nothing changes unless an
     * assembler is wired. The call is snapshotted once (the field is {@code volatile}) and guarded defensively: the
     * assembler's contract already forbids throwing, but a misbehaving custom assembler must never break the turn.
     *
     * @param agentRuntime
     *            the agent runtime supplying the filesystem and environment (must not be null)
     * @param agent
     *            the invoking agent (must not be null)
     * @return the assembled blocks in injection order; never null, empty when nothing applies
     */
    private List<ContextBlock> assembleContext(OrcaAgentRuntime agentRuntime, Agent agent) {
        final ContextAssembler assembler = contextAssembler;
        if (assembler == ContextAssembler.NOOP) {
            return List.of();
        }
        try {
            final ContextAssemblyRequest request = ContextAssemblyRequest.builder()
                    .environment(agentRuntime.getEnvironment()).fileSystem(agentRuntime.getFileSystem())
                    .agentName(agent.getName()).iteration(0).build();
            final List<ContextBlock> blocks = assembler.assemble(request);
            return blocks == null ? List.of() : blocks;
        } catch (RuntimeException e) {
            log.warn("ContextAssembler failed; proceeding without assembled context: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Injects the assembled {@link ContextBlockKind#USER_PREPEND USER_PREPEND} and
     * {@link ContextBlockKind#ATTACHMENT ATTACHMENT} blocks as a single synthetic user-role
     * {@code <system-reminder>} message.
     *
     * <p>
     * No-op when no such blocks were assembled. {@link ContextBlockKind#SYSTEM SYSTEM} blocks are handled separately by
     * {@link #buildSystemPromptParts}. Wrapping is delegated to {@link SystemReminderFormatter}; the whole step is
     * guarded so a malformed block (e.g. a body containing a nested reminder marker) is logged and skipped rather than
     * breaking the turn.
     *
     * @param transcriptBuffer
     *            the memory to append the synthetic block to (must not be null)
     * @param assembledContext
     *            the blocks assembled for this turn (must not be null)
     */
    private void injectAssembledUserContext(TranscriptBuffer transcriptBuffer, List<ContextBlock> assembledContext) {
        if (assembledContext.isEmpty()) {
            return;
        }
        final Map<String, String> entries = new LinkedHashMap<>();
        for (ContextBlock block : assembledContext) {
            if (block.getKind() == ContextBlockKind.USER_PREPEND || block.getKind() == ContextBlockKind.ATTACHMENT) {
                entries.put(block.getKey(), block.getBody());
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        try {
            final String body = SystemReminderFormatter.wrapMany(entries);
            transcriptBuffer.addMessage(Message.user(body));
            log.debug("Injected {} assembled user-context block(s) as a synthetic reminder message", entries.size());
        } catch (RuntimeException e) {
            log.warn("Failed to inject assembled user-context blocks; skipping: {}", e.getMessage());
        }
    }

    /**
     * Executes OnStart hooks, throws if any hook blocks the execution, and otherwise feeds any advisory hook feedback
     * back into the conversation.
     *
     * <p>
     * The feedback message is appended after the real user message (already in memory at this point) so the model
     * reads the note as context for the turn it is about to take, and is wrapped in a {@code <system-reminder>} block
     * so it is not mistaken for genuine user intent. Mirrors {@code DefaultSubagentExecutor#fireOnStart} — without
     * this the main agent silently dropped feedback that subagents surfaced.
     *
     * @param scope
     *            The execution scope
     * @param userMessage
     *            The user message text that triggered the execution
     * @throws ExecutionBlockedByHookException
     *             if any OnStart hook blocks the execution
     */
    private void checkOnStartHooks(ExecutionScope scope, String userMessage) {
        final List<HookResult> onStartResults = invokeOnStart(scope, userMessage);
        if (hookExecutionManager.hasBlockedResult(onStartResults)) {
            final List<String> blockReasons = hookExecutionManager.collectBlockedReasons(onStartResults);
            throw new ExecutionBlockedByHookException(InvokerType.MAIN_AGENT, scope.getAgent().getName(), "OnStart",
                    blockReasons);
        }
        HookFeedback.toReminderBlock(HookFeedback.collectAdvisory(onStartResults))
                .ifPresent(block -> scope.transcriptBuffer.addMessage(Message.user(block)));
    }

    /**
     * Executes the complete command flow including execution, result handling, and hooks.
     *
     * @param scope
     *            The execution scope
     * @param executionRequest
     *            The agent execution request containing the command
     * @return The execution result (never null)
     */
    private OrcaAgentExecutionResult executeCommandFlow(ExecutionScope scope,
            OrcaAgentExecutionRequest executionRequest) {
        final String userMessage = executionRequest.getUserInput().asText();
        log.info("Executing command: {}", userMessage);

        final CommandExecutionResult commandExecutionResult = executeCommand(scope, executionRequest,
                scope.transcriptBuffer);

        // Extract metadata from command result if available
        final ExecutionMetadata metadata = commandExecutionResult.getMetadata()
                .orElseGet(() -> buildExecutionMetadata(0, TokenUsage.empty(), scope.startTime));

        // Attach any artifacts produced during command execution to the assistant message
        final List<FileArtifact> commandArtifacts = scope.artifactCollector.getArtifacts();
        if (commandArtifacts.isEmpty()) {
            scope.transcriptBuffer.addMessage(Message.assistant(commandExecutionResult.getResponse()));
        } else {
            final List<MessageArtifact> messageArtifacts = commandArtifacts.stream()
                    .map(FileArtifact::toMessageArtifact).toList();
            scope.transcriptBuffer
                    .addMessage(Message.assistant(commandExecutionResult.getResponse(), List.of(), messageArtifacts));
        }

        invokeOnStop(scope, commandExecutionResult.isSuccess(), commandExecutionResult.getResponse(), metadata);

        // Return command execution result as failure or success based on command result
        if (commandExecutionResult.isSuccess()) {
            return OrcaAgentExecutionResult.success(commandExecutionResult.getResponse(),
                    scope.transcriptBuffer.toSnapshot(), metadata, scope.artifactCollector.getArtifacts())
                    .withCompactionEvents(scope.compactionEvents);
        } else {
            return OrcaAgentExecutionResult.failure(commandExecutionResult.getResponse(),
                    scope.transcriptBuffer.toSnapshot(), metadata, scope.artifactCollector.getArtifacts())
                    .withCompactionEvents(scope.compactionEvents);
        }
    }

    /**
     * Executes the ReAct loop until completion or max iterations.
     *
     * @param scope
     *            The execution scope
     * @return The execution result (never null)
     */
    // The ReAct loop is one cohesive control flow: a single interrupt-coordinator scope wrapping the
    // reason -> act iteration with interleaved cancellation, budget/compaction, streaming-overlap and suspend/resume
    // handling. Splitting it into helpers would fragment that shared state and control flow across the framework's
    // most critical path for no real gain, so the method-length limit is suppressed here rather than force-extracted.
    @SuppressWarnings("checkstyle:MethodLength")
    private OrcaAgentExecutionResult executeReActLoop(ExecutionScope scope) {
        // Create per-session registry (enables ToolSearch activation isolation)
        final ToolRegistry sessionRegistry = createSessionRegistry(scope.agentRuntime.getToolRegistry());

        // One coordinator per executeReActLoop call. AutoCloseable drops pending registrars on any exit path
        // (normal return, budget stop, interrupt, thrown exception).
        try (InterruptCoordinator coordinator = Objects.requireNonNull(interruptCoordinatorFactory.get(),
                "interruptCoordinatorFactory must not return null")) {
            final CancellationSignal cancellationSignal = coordinator.getSignal();
            // LLM-CANCEL: one adapter per turn bridges the turn signal to the active LLM HTTP call so a trip can
            // actively abort the in-flight stream (not just wait for the next iteration boundary). Created once — it
            // registers a single signal listener for the whole turn (see SignalBackedLlmCancellation).
            final SignalBackedLlmCancellation llmCancellation = new SignalBackedLlmCancellation(cancellationSignal);

            // Publish the fresh coordinator to the request's observer exactly once so session/queue-level
            // callers (DefaultLiveSession, REPL SIGINT handler, priority-queue preemption) can trip the turn. The
            // observer is a no-op by default, preserving behaviour for requests that do not opt in.
            scope.executionRequest.getInterruptObserver().accept(coordinator);

            // Live-metrics seam: publish the per-turn BudgetTracker so session-level callers
            // (DefaultLiveSession.status()) can read live iteration / token / elapsed counters. No-op by default,
            // so requests that do not opt in are unaffected. Published once, alongside the interrupt coordinator.
            scope.executionRequest.getBudgetObserver().accept(scope.budgetTracker);

            final ToolContext toolContext = createToolContext(scope, sessionRegistry, cancellationSignal,
                    messageQueueManager, event -> scope.eventDispatcher.dispatch(event));

            TokenUsage accumulatedTokens = TokenUsage.empty();
            // Model name for cost attribution — constant for the whole execution, resolved once. Empty (null) when
            // the agent left the model name defaulted; the estimator/summary bucket such calls under "unknown".
            final String costModelName = scope.getAgent().getMetadata().getModel().getName().orElse(null);
            // Cost tracking is opt-in. When the default NOOP estimator is in place nothing is recorded, so the
            // exposed CostSummary stays empty. Resolved once so a mid-loop field swap cannot toggle behaviour per turn.
            final boolean costTrackingEnabled = costEstimator != CostEstimator.NOOP;
            int iterationCount = 0;
            // Consecutive stalled-iteration counter (reset to 0 on any iteration that made progress). Tripping
            // MAX_CONSECUTIVE_STALLED_ITERATIONS aborts the loop before the next LLM call — the death-spiral guard.
            int consecutiveStalledIterations = 0;
            // Number of queued user inputs drained at the PREVIOUS iteration's tail. Read at the top of the next
            // iteration to tag its LoopTransition as QUEUED_INPUT (observation-only; never drives control flow).
            int injectedLastTail = 0;
            final int maxIterations = scope.getAgent().getMetadata().getMaxIterations();

            // CONV-COMPACT-01: resolve the compaction guard once per ReAct loop. NoOpCompactionGuard is the framework
            // default — behaviour is unchanged unless the caller wires a real guard via the agent runtime.
            final CompactionGuard compactionGuard = scope.agentRuntime.getCompactionGuard()
                    .orElse(NoOpCompactionGuard.instance());

            try {
                while (iterationCount < maxIterations) {
                    // Honour a trip that landed before this iteration starts (e.g. between tool-result commit
                    // and the next sendMessage call, or before the very first LLM call). No new LLM traffic or tool
                    // invocation is issued once the signal is raised.
                    if (isInterrupted(coordinator)) {
                        return handleInterrupted(scope, iterationCount, accumulatedTokens);
                    }

                    // Consult the budget tracker BEFORE starting a new iteration so exhausted budgets do not incur
                    // another LLM call. The agent-metadata maxIterations bound in the while-condition remains in place;
                    // when hit, the loop finalises via handleMaxIterations — a normal
                    // CompletionReason.MAX_ITERATIONS
                    // return, no longer a thrown MaxIterationsExceededException.
                    final BudgetDecision decision = scope.budgetTracker.check();
                    if (decision == BudgetDecision.STOP) {
                        return handleBudgetStop(scope, iterationCount, accumulatedTokens);
                    }
                    // A soft budget hint asks us to proactively compact before spending more tokens. Route this
                    // iteration's compaction gate through forceCompact (lower effective trigger) rather than the normal
                    // auto-compact band. Self-limiting: once memory has been compacted small, forceCompact returns NONE
                    // until the context regrows.
                    final boolean budgetForcedCompaction = decision == BudgetDecision.SHOULD_COMPACT;
                    if (budgetForcedCompaction) {
                        log.info("Budget hint: SHOULD_COMPACT — forcing proactive compaction before iteration {}",
                                iterationCount + 1);
                    }

                    iterationCount++;
                    scope.budgetTracker.recordIteration();
                    log.debug("Starting iteration {} of {}", iterationCount, maxIterations);

                    // CONV-COMPACT-01: AUTO compaction gate. Evaluates the conversation against per-model thresholds
                    // and may rewrite memory in place before the next LLM call. When the budget tracker requested
                    // proactive compaction, forceCompact lowers the effective trigger to the warning band.
                    // Snapshot the message count BEFORE the guard runs — it rewrites memory in place, so this is
                    // the
                    // only point where the pre-compaction size is observable for the CompactBoundary event below.
                    final int messagesBeforeCompaction = scope.transcriptBuffer.size();
                    final CompactionDecision compactionDecision = budgetForcedCompaction
                            ? compactionGuard.forceCompact(scope.transcriptBuffer,
                                    scope.getAgent().getMetadata().getModel(), scope.getHookRegistry(),
                                    scope.getEnvironment())
                            : compactionGuard.maybeCompact(scope.transcriptBuffer,
                                    scope.getAgent().getMetadata().getModel(), scope.getHookRegistry(),
                                    scope.getEnvironment());
                    switch (compactionDecision.getAction()) {
                        case BLOCK :
                            log.error("Compaction guard blocked iteration {}: {}", iterationCount,
                                    compactionDecision.getReason());
                            throw new ContextWindowExceededException(compactionDecision.getEstimatedTokens(),
                                    compactionDecision.getBlockingLimit(), compactionDecision.getReason());
                        case COMPACT :
                            log.info("Compaction performed before iteration {}: {}", iterationCount,
                                    compactionDecision.getReason());
                            compactionDecision.getCompactionResult()
                                    .ifPresent(r -> scope.compactionEvents.add(r.getMetadata()));
                            // Publish the compaction-boundary observability event. Emitted here (not at the
                            // iteration tail) so it is ordered immediately before this iteration's IterationStarted,
                            // reflecting that the compaction happened just before the LLM call it precedes.
                            scope.eventDispatcher.emitCompactBoundary(iterationCount, messagesBeforeCompaction,
                                    scope.transcriptBuffer.size());
                            break;
                        case WARN :
                            log.warn("Compaction guard warning at iteration {}: {}", iterationCount,
                                    compactionDecision.getReason());
                            break;
                        case NONE :
                        default :
                            break;
                    }

                    // STREAM-03: publish iteration-start boundary before issuing the LLM call.
                    scope.eventDispatcher.emitIterationStarted(iterationCount);

                    // TRACE-01: open an ITERATION span; this iteration's LLM and tool spans nest under it. The active
                    // span is restored and the iteration span closed in the finally below (covers every exit path).
                    final Tracer.Span iterationSpan = safeStartChild(scope.activeSpan.context(), SpanType.ITERATION,
                            "iteration#" + iterationCount, null);
                    final Tracer.Span parentActiveSpan = scope.activeSpan;
                    scope.activeSpan = iterationSpan;
                    // Tag every re-entry (iteration 2+) with why the loop continued. The first iteration is the
                    // loop's entry, not a re-entry, so it carries no transition. Observation-only: attached to the
                    // span, never consulted for control flow.
                    if (iterationCount > 1) {
                        annotateLoopTransition(iterationSpan,
                                resolveLoopTransition(iterationCount, budgetForcedCompaction, injectedLastTail));
                    }
                    try {

                        // Query available tools each iteration so newly activated deferred tools are included.
                        // Tools exceeding the side-effect ceiling are withheld so the LLM never proposes a call the
                        // execution manager would refuse.
                        final List<ToolDefinition> availableTools = sessionRegistry.findAll().stream()
                                .filter(t -> maxSideEffectLevel.permits(t.getSideEffectLevel()))
                                .map(Tool::getDefinition).toList();

                        // Streaming-tool overlap (design §11): when the executor streams AND the dispatcher
                        // supports eager dispatch (parallel + the streamingOverlap opt-in, pool open), install a
                        // per-iteration scheduler so completed side-effect-free tool_use blocks run during the token
                        // stream. It shares the executor-scoped pool via the dispatcher and holds only per-attempt
                        // state. When overlap is off this stays null and every path below is byte-identical to the
                        // non-overlap behaviour. Built here (iteration span already active) so the eager runner's
                        // effective tool context matches the harvest path exactly.
                        final boolean overlapActive = useStreaming && parallelToolDispatcher.supportsEagerDispatch();
                        scope.streamingToolScheduler = overlapActive
                                ? new StreamingToolScheduler(parallelToolDispatcher, sessionRegistry,
                                        toolRunner(scope, toolContext, iterationCount, coordinator, sessionRegistry))
                                : null;

                        final LlmResponse response;
                        try {
                            response = invokeGateway(scope, iterationCount, availableTools, cancellationSignal,
                                    llmCancellation);
                        } finally {
                            // LLM-CANCEL: drop the just-finished call's abort lever so a trip landing while the next
                            // tools run cannot invoke a stale (already-closed) stream. Idempotent close() makes this
                            // hygiene rather than strictly required.
                            llmCancellation.clearAbort();
                        }

                        // Accumulate tokens
                        accumulatedTokens = accumulatedTokens.add(response.getTokenUsage());
                        scope.budgetTracker.recordTokens(response.getTokenUsage());

                        // When a cost estimator is wired (opt-in), price this call and fold the cost into the
                        // per-model summary + the opt-in cost budget axis. With the default CostEstimator.NOOP the
                        // whole
                        // block is skipped so the exposed CostSummary stays empty (isEmpty()==true) — cost tracking is
                        // entirely opt-in and adds no per-model bookkeeping when unused. The budget axis is USD-only by
                        // contract, so a non-USD estimate (advanced misconfiguration) still accumulates in the summary
                        // but is not enforced as a STOP.
                        if (costTrackingEnabled) {
                            final Money callCost = costEstimator.estimate(costModelName, response.getTokenUsage());
                            scope.costSummary = scope.costSummary.record(costModelName, response.getTokenUsage(),
                                    callCost);
                            if (Money.USD.equals(callCost.getCurrency())) {
                                scope.budgetTracker.recordCost(callCost);
                            }
                        }

                        // STREAM-03: publish assistant-message-received for intermediate (tool-calling) responses only.
                        // The terminal response (no tool uses) is surfaced through AgentExecutionResult#getFinalAnswer
                        // and
                        // rendered by the REPL's displayResult; emitting it here would duplicate the final answer line.
                        if (response.hasToolUses()) {
                            scope.eventDispatcher.emitAssistantMessageReceived(iterationCount, response);
                        }

                        // SK-11.4: pre-flight scan of Skill tool_uses. If any need user approval, suspend the turn
                        // atomically — no assistant message and no tool_result are committed to TranscriptBuffer, so
                        // a
                        // subsequent resume can re-issue the LLM call from the same memory state and the cached
                        // approvals
                        // (populated by the approval channel before resume) flip the policy to ALLOW. Skipped when the
                        // scanner is unconfigured (headless context) so the legacy fail-closed path through SkillTool
                        // continues to apply.
                        if (skillPreflightScanner != null && response.hasToolUses()) {
                            final SkillPreflightScanResult scan = skillPreflightScanner.scan(response.getToolUses(),
                                    scope.agentRuntime.getId(), scope.transcriptBuffer.getSessionId(),
                                    scope.getPrincipal());
                            if (scan.shouldSuspend()) {
                                // The turn suspends without committing this response — drop any eager tool work so
                                // nothing from the abandoned response is harvested on resume. Safe: eager tools are
                                // side-effect-free, so their partial execution is simply discarded.
                                discardEagerToolUses(scope);
                                return handleSuspended(scope, iterationCount, accumulatedTokens,
                                        scan.getPendingSkills());
                            }
                        }

                        // If no tools uses, we have the final answer
                        if (!response.hasToolUses()) {
                            // A turn the provider cut off at max_tokens is NOT a clean final answer. Surface the
                            // partial text with an explicit marker and terminate as TRUNCATED (isSuccessful()=false) so
                            // callers can distinguish it from a normal COMPLETED finish, rather than silently treating
                            // the truncated fragment as the agent's considered answer.
                            final boolean truncated = response.getStopReason().filter(StopReason::isTruncated)
                                    .isPresent();
                            if (truncated) {
                                final String flaggedAnswer = response.getTextContent() + TRUNCATION_MARKER;
                                scope.transcriptBuffer
                                        .addMessage(Message.assistant(flaggedAnswer, response.getToolUses()));
                                scope.eventDispatcher.emitIterationCompleted(iterationCount, false);
                                scope.eventDispatcher.emitExecutionCompleted(iterationCount,
                                        CompletionReason.TRUNCATED);
                                return createTruncatedResult(scope, flaggedAnswer, iterationCount, accumulatedTokens);
                            }
                            scope.transcriptBuffer
                                    .addMessage(Message.assistant(response.getTextContent(), response.getToolUses()));
                            // STREAM-03: iteration-complete + execution-complete(COMPLETED) for the terminal-success
                            // path.
                            scope.eventDispatcher.emitIterationCompleted(iterationCount, false);
                            scope.eventDispatcher.emitExecutionCompleted(iterationCount, CompletionReason.COMPLETED);
                            return createSuccessResult(scope, response.getTextContent(), iterationCount,
                                    accumulatedTokens);
                        }

                        // Mark the artifact count before tool execution; sliceFrom() below closes the window.
                        final int artifactCountBefore = scope.artifactCollector.size();

                        // Execute all tool uses
                        final List<ToolUseResult> toolUseResults = executeToolUses(scope, toolContext,
                                response.getToolUses(), iterationCount, coordinator, sessionRegistry);

                        // Collect artifacts produced during this iteration's tool execution and convert to
                        // MessageArtifact. One snapshot does the slicing — reading the collector twice (size, then
                        // elements) would let a late add shift the window between the two reads.
                        final List<MessageArtifact> iterationArtifacts = scope.artifactCollector
                                .sliceFrom(artifactCountBefore).stream().map(FileArtifact::toMessageArtifact).toList();

                        // Add assistant response with artifacts to conversation
                        scope.transcriptBuffer.addMessage(Message.assistant(response.getTextContent(),
                                response.getToolUses(), iterationArtifacts));

                        if (!toolUseResults.isEmpty()) {
                            scope.transcriptBuffer.addMessage(Message.toolUseResults(toolUseResults));
                        }

                        // Death-spiral guard: an iteration that issued tool calls but had every one of them
                        // fail made no forward progress. Count consecutive such iterations; once
                        // MAX_CONSECUTIVE_STALLED_ITERATIONS land back-to-back, abort here — BEFORE the queue drain and
                        // budget continuation below — rather than feeding the all-error result back into another LLM
                        // call and spending the rest of the budget on a request that is not converging. Any iteration
                        // that made progress resets the counter.
                        // An interrupted batch is not a death spiral. Once the signal is tripped every
                        // not-yet-started tool_use is short-circuited to an error result (see toolRunner), so an
                        // interrupted iteration looks all-error to isStalledIteration. Counting it would let a trip
                        // that lands on the third consecutive failing iteration finalise the turn as STALLED instead of
                        // INTERRUPTED. Read the signal directly here — the consuming isInterrupted(coordinator) check
                        // below owns the thread-interrupt half and must stay the single evaluation point for it.
                        if (!cancellationSignal.isCancelled() && isStalledIteration(toolUseResults)) {
                            consecutiveStalledIterations++;
                            if (consecutiveStalledIterations >= MAX_CONSECUTIVE_STALLED_ITERATIONS) {
                                scope.eventDispatcher.emitIterationCompleted(iterationCount, false);
                                return handleStalledIteration(scope, iterationCount, accumulatedTokens,
                                        consecutiveStalledIterations);
                            }
                        } else {
                            consecutiveStalledIterations = 0;
                        }

                        // CQ-03: iteration-tail mid-turn injection. After tool results are committed to memory and
                        // BEFORE
                        // the next sendMessage call, drain queued user inputs scoped to this agent runtime and
                        // append
                        // them as <system-reminder>-wrapped user messages. When no queue manager is configured this is
                        // a
                        // no-op, preserving legacy behavior. The drained count is carried into the next iteration
                        // so its LoopTransition can be tagged QUEUED_INPUT.
                        injectedLastTail = injectQueuedMessages(scope);

                        // Iteration-tail trip check. A tool may have cooperatively tripped the signal during
                        // this
                        // iteration, or an external caller may have landed a trip between tool return and the next LLM
                        // call. Exit cleanly as INTERRUPTED rather than launching another sendMessage.
                        if (isInterrupted(coordinator)) {
                            scope.eventDispatcher.emitIterationCompleted(iterationCount, false);
                            return handleInterrupted(scope, iterationCount, accumulatedTokens);
                        }

                        // STREAM-03: iteration-complete with willContinue=true — another LLM call will follow.
                        scope.eventDispatcher.emitIterationCompleted(iterationCount, true);
                    } finally {
                        scope.activeSpan = parentActiveSpan;
                        iterationSpan.close();
                    }
                }

                // The agent-metadata iteration ceiling is a policy limit, not an error. Finalise via a normal
                // return carrying CompletionReason.MAX_ITERATIONS — the same shape as budget-driven stops — rather
                // than throwing MaxIterationsExceededException into the catch below and routing through the generic
                // error path.
                return handleMaxIterations(scope, iterationCount, accumulatedTokens, maxIterations);

            } catch (CancelledExecutionException e) {
                // PSTREAM-09: streaming sink raised the checkpoint mid-stream. Partial text (if any) has already been
                // appended to memory inside invokeGateway and an AssistantTextStreamCompleted(finishReason=
                // "interrupted") event has been emitted; fall through to the shared INTERRUPTED finalisation path.
                log.debug("ReAct loop cancelled: {}", e.getMessage());
                return handleInterrupted(scope, iterationCount, accumulatedTokens);
            } catch (Exception e) {
                return handleExecutionError(e, scope, iterationCount, accumulatedTokens);
            }
        }
    }

    /**
     * The loop's single cancellation checkpoint. Reports whether this turn must stop, treating a live thread
     * interrupt as equivalent to a tripped {@link CancellationSignal} — and, crucially, <b>consuming</b> that flag via
     * {@link CancellationSignals#isCancelledOrInterrupted(CancellationSignal)}.
     *
     * <p>
     * Consuming the flag is what makes the check safe rather than merely thorough. A {@code THREAD_INTERRUPT} tool's
     * terminator (or a tool that restored the flag after catching {@link InterruptedException}) leaves the executing
     * thread interrupted. Everything the loop subsequently runs under a blocking wait then fails immediately —
     * including {@code DefaultHookExecutor}'s {@code Future#get(timeout)}, whose fail-open policy maps the resulting
     * {@link InterruptedException} to a successful {@code HookResult}. A PreTool hook that returned BLOCKED would
     * therefore be downgraded to an allow, silently bypassing the permission gate. Clearing the flag at every
     * checkpoint removes that window; leaving it set was the defect.
     *
     * <p>
     * When the flag was the <em>only</em> evidence of cancellation, it is promoted into the turn-scoped signal so the
     * decision survives its own consumption: subsequent checkpoints, the tail finalisation and any registered
     * terminator all observe a normally tripped turn. {@link InterruptReason#PARENT_CANCELLED} is the closest match —
     * an interrupt on the executing thread means an owner of that thread (session, {@code Future#cancel(true)},
     * managed shutdown) asked this execution to stop.
     *
     * <p>
     * Because the thread-interrupt half is consumed, this must be evaluated <b>once</b> per decision point; a second
     * call at the same point sees only the (now promoted) signal.
     *
     * @param coordinator
     *            the turn-scoped interrupt coordinator (must not be null)
     * @return {@code true} if the turn must stop
     */
    private boolean isInterrupted(InterruptCoordinator coordinator) {
        final CancellationSignal signal = coordinator.getSignal();
        if (!CancellationSignals.isCancelledOrInterrupted(signal)) {
            return false;
        }
        if (!signal.isCancelled()) {
            log.debug("Promoting a thread interrupt into the turn's cancellation signal");
            coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED);
        }
        return true;
    }

    /**
     * Drains queued user inputs scoped to the current agent runtime and appends them as user-role messages wrapped
     * in {@code <system-reminder key="user-mid-turn-message">} blocks.
     *
     * <p>
     * This is a no-op when {@link #messageQueueManager} is {@code null}. The drain is scoped by matching
     * {@link QueuedInput#getAgentRuntimeId()} against the active
     * {@link OrcaAgentRuntime#getId() agent runtime id}, so sub-agent or other-agent queue entries are
     * NOT consumed here. Only {@link QueuedInputPriority#NEXT} (and higher-priority) entries are drained — the
     * {@link QueuedInputPriority#LATER} tier is left in place. The {@code TURN_END} drain path is owned by CQ-05.
     *
     * <p>
     * Ordering: messages are appended in priority-then-FIFO order as returned by {@link MessageQueueManager}. Each
     * queued entry becomes its own user message; no batching or coalescing is performed at this layer.
     *
     * @param scope
     *            the active execution scope; must not be null
     * @return the number of queued inputs drained and appended (0 when the queue is disabled or empty); used by the
     *         loop to tag the next iteration's {@link LoopTransition} as {@link LoopTransitionReason#QUEUED_INPUT}
     */
    private int injectQueuedMessages(ExecutionScope scope) {
        if (messageQueueManager == null) {
            return 0;
        }
        final AgentRuntimeId agentRuntimeId = scope.agentRuntime.getId();
        final List<QueuedInput> drained = messageQueueManager
                .drainForInjection(q -> agentRuntimeId.equals(q.getAgentRuntimeId()), QueuedInputPriority.NEXT);
        if (drained.isEmpty()) {
            return 0;
        }
        int injected = 0;
        for (QueuedInput queued : drained) {
            // Per-message isolation: these entries are already drained (removed) from the queue, so a throw from wrap()
            // or addMessage() must not abort the turn or discard the co-drained siblings. Log and skip just the bad
            // one.
            try {
                final String wrapped = SystemReminderFormatter.wrap(MID_TURN_INJECTION_KEY, queued.getInputText());
                scope.transcriptBuffer.addMessage(Message.user(wrapped));
                injected++;
                log.debug("Injected queued user message uuid={} priority={} into session {}", queued.getUuid(),
                        queued.getPriority(), scope.transcriptBuffer.getSessionId().value());
            } catch (RuntimeException e) {
                log.warn("Skipped injecting queued user message uuid={} priority={}: {}", queued.getUuid(),
                        queued.getPriority(), e.getMessage());
            }
        }
        return injected;
    }

    /**
     * Derives the {@link LoopTransition} that explains why the loop is re-entering {@code iteration} (always &gt;=
     * 2; the first iteration is the loop's entry, not a re-entry). Pure function of the two re-entry signals — it reads
     * no fields and mutates nothing — so it is package-private and static for direct unit testing.
     *
     * <p>
     * When both signals are set (the previous iteration drained queued input <em>and</em> the budget tracker forced a
     * compaction before this one), the more operationally significant {@link LoopTransitionReason#BUDGET_COMPACT} wins
     * and the queued-input drain is preserved in the note. This precedence is observation-only and never affects
     * control flow.
     *
     * @param iteration
     *            the one-based iteration being entered (must be &gt;= 2 in practice)
     * @param budgetForcedCompaction
     *            whether the budget tracker requested a proactive compaction before this iteration
     * @param injectedLastTail
     *            the number of queued user inputs drained at the previous iteration's tail
     * @return the resolved transition (never null)
     */
    static LoopTransition resolveLoopTransition(int iteration, boolean budgetForcedCompaction, int injectedLastTail) {
        final String queuedNote = injectedLastTail > 0 ? injectedLastTail + " queued input(s) drained" : null;
        if (budgetForcedCompaction) {
            return LoopTransition.of(LoopTransitionReason.BUDGET_COMPACT, iteration, queuedNote);
        }
        if (injectedLastTail > 0) {
            return LoopTransition.of(LoopTransitionReason.QUEUED_INPUT, iteration, queuedNote);
        }
        return LoopTransition.of(LoopTransitionReason.NEXT_ITERATION, iteration);
    }

    /**
     * Attaches a {@link LoopTransition} to an ITERATION tracing span as string attributes. The note attribute is
     * written only when present, so {@code loop.transition.note} is absent (not blank) for note-less transitions.
     *
     * @param span
     *            the iteration span to annotate (must not be null)
     * @param transition
     *            the transition to record (must not be null)
     */
    private static void annotateLoopTransition(Tracer.Span span, LoopTransition transition) {
        span.setAttribute(LOOP_TRANSITION_ATTR, transition.getReason().name());
        span.setAttribute(LOOP_TRANSITION_ITERATION_ATTR, Integer.toString(transition.getIteration()));
        transition.getNote().ifPresent(note -> span.setAttribute(LOOP_TRANSITION_NOTE_ATTR, note));
    }

    /**
     * Executes a command and returns the result.
     *
     * @param scope
     *            The execution scope carrying agent-runtime attribution
     * @param executionRequest
     *            The agent execution request containing the command
     * @param transcriptBuffer
     *            The conversation context for commands that need to modify it
     * @return The command execution result (never null)
     */
    private CommandExecutionResult executeCommand(ExecutionScope scope, OrcaAgentExecutionRequest executionRequest,
            TranscriptBuffer transcriptBuffer) {
        // One coordinator per command, closed on every exit path so no terminator outlives the command that
        // registered it. The ReAct loop mints its own at line ~1415 and the two flows are mutually exclusive
        // (executeCommandFlow or executeReActLoop, never both), so these can never overlap. Nothing trips this one
        // today — a slash command is not interruptible — but an inline skill's tools must still be handed a
        // registrar, because SingleToolInvoker asks for one whenever the target tool declares THREAD_INTERRUPT.
        try (InterruptCoordinator commandCoordinator = Objects.requireNonNull(interruptCoordinatorFactory.get(),
                "interruptCoordinatorFactory must not return null")) {
            final OrcaAgentRuntime agentRuntime = scope.agentRuntime;
            // Forward parent agent-runtime attribution into the command flow so user-slash invocations of
            // fork-mode skills can propagate the agent runtime ID, execution attributes, and LLM call metadata
            // to the spawned subagent. The fork executor is resolved with the same availability check
            // OrcaSkillToolProvider uses for SkillTool, so both invocation paths share fork wiring semantics.
            final SkillForkExecutor skillForkExecutor = OrcaSkillForkExecutorResolver.resolve(agentRuntime.getAgent(),
                    agentRuntime.getSubagentRegistry(), agentRuntime.getToolRegistry(), agentRuntime.getHookRegistry(),
                    agentRuntime.getEnvironment(), subagentExecutionManager);
            final ToolContext commandToolContext = ToolContext.builder()
                    .put(ToolContextKeys.AGENT_RUNTIME_ID, agentRuntime.getId())
                    // The session id belongs here too: a `/my-skill` invocation of a fork-mode skill spawns a
                    // subagent, and the spawn site reads this key to tell the fork which session it acts for.
                    // Without it the user's own slash command would produce a fork that inherits none of the
                    // approvals granted in the session the user typed it into.
                    .put(ToolContextKeys.SESSION_ID, transcriptBuffer.getSessionId())
                    .put(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY, scope.getExecutionAttributes())
                    .put(ToolContextKeys.LLM_CALL_METADATA_KEY, scope.llmCallMetadata)
                    .put(ToolContextKeys.SKILL_FORK_EXECUTOR_KEY, skillForkExecutor)
                    .put(ToolContextKeys.SKILL_TOOL_DISPATCHER_KEY, commandToolDispatcher(scope, commandCoordinator))
                    .build();
            return commandExecutionManager.execute(executionRequest, transcriptBuffer,
                    agentRuntime.getCommandRegistry(), agentRuntime.getToolRegistry(),
                    agentRuntime.getAgent().getMetadata().getModel(), commandToolContext);
        } catch (Exception e) {
            return CommandExecutionResult.failure("Command execution error: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the {@link SkillToolDispatcher} bound into the command tool context.
     *
     * <p>
     * An inline skill invoked as {@code /my-skill} runs its own ReAct loop, and it used to run that loop straight
     * against the {@code ToolExecutionManager}: same registry as the agent, but reached by a path with no
     * PermissionRequest hooks, no side-effect approval gate and no Pre/PostTool. Typing a slash was therefore a way to
     * run a tool the agent itself would have had to ask about. Routing the batch through {@link #singleToolInvoker}
     * closes that, and does it by reusing the pipeline rather than restating it — the parity risk the shared
     * pipeline exists to avoid.
     *
     * <p>
     * The invocation is stamped {@code MAIN_AGENT}: a skill is not a subagent, it is the agent doing what the user
     * asked. The skill's own {@code allowed-tools} list arrives per call from {@code LlmSkillExecutor} and narrows
     * dispatch further, which is the one thing the loop's own dispatch does not do (it passes an empty list).
     *
     * @param scope
     *            the execution scope supplying the agent runtime and execution attributes (must not be null)
     * @param coordinator
     *            the command-scoped interrupt coordinator used to mint per-tool registrars (must not be null)
     * @return a dispatcher bound to this command's runtime and coordinator (never null)
     */
    private SkillToolDispatcher commandToolDispatcher(ExecutionScope scope, InterruptCoordinator coordinator) {
        final OrcaAgentRuntime agentRuntime = scope.agentRuntime;
        return (toolRegistry, toolContext, toolUses, allowedTools, iterationCount) -> toolUses.stream()
                .map(toolUse -> singleToolInvoker.invoke(ToolInvocationSpec.builder()
                        .invokerType(InvokerType.MAIN_AGENT).invokerName(agentRuntime.getAgent().getName())
                        .hookRegistry(agentRuntime.getHookRegistry()).environment(agentRuntime.getEnvironment())
                        .executionAttributes(scope.getExecutionAttributes()).toolRegistry(toolRegistry)
                        .sessionRegistry(toolRegistry).allowedTools(allowedTools).coordinator(coordinator)
                        .toolContext(toolContext).toolUse(toolUse).iterationCount(iterationCount).build()))
                .toList();
    }

    /**
     * Executes multiple tool uses and returns their results.
     *
     * <p>
     * STREAM-03: Emits a {@link ToolUseStarted} event before each tool invocation and a
     * {@link ToolResultReady} event after each tool result is produced. Both emissions are listener-gated so the
     * zero-listener path pays only an {@code isEmpty()} read per boundary.
     *
     * <p>
     * The {@code coordinator} and {@code sessionRegistry} parameters thread the turn-scoped interrupt
     * machinery down to {@link #executeSingleTool} so each invocation can allocate a
     * {@link TerminatorRegistrar} sized to the target tool's declared {@link InterruptBehavior}.
     *
     * @param scope
     *            The execution scope carrying event-emission context, the agent runtime, and the execution
     *            attributes (must not be null)
     * @param toolContext
     *            The tool execution context
     * @param toolUses
     *            The list of tool uses to execute
     * @param iterationCount
     *            The current iteration count
     * @param coordinator
     *            The turn-scoped interrupt coordinator used to mint per-tool registrars (must not be null)
     * @param sessionRegistry
     *            The per-session tool registry used to look up the target tool's
     *            {@link InterruptBehavior} declaration (must not be null)
     * @return The list of tool use results (never null)
     */
    private List<ToolUseResult> executeToolUses(ExecutionScope scope, ToolContext toolContext, List<ToolUse> toolUses,
            int iterationCount, InterruptCoordinator coordinator, ToolRegistry sessionRegistry) {
        final Function<ToolUse, ToolUseResult> runner = toolRunner(scope, toolContext, iterationCount, coordinator,
                sessionRegistry);

        // Streaming-tool overlap (design §11.2): when a per-iteration scheduler eagerly ran the safe prefix of this
        // batch during the token stream, harvest those results (and run any deferred suffix inline on this thread)
        // instead of re-dispatching. Lifecycle events still fire in input order at harvest, so the observable event
        // stream and the results are byte-identical to the non-overlap path — only wall-clock is reduced. When no eager
        // work exists (overlap off, batch poisoned at tool 0, or a retry disabled it) this falls through to the
        // ordinary
        // batch-dispatch path unchanged.
        final StreamingToolScheduler scheduler = scope.streamingToolScheduler;
        if (scheduler != null && scheduler.hasAnyEager()) {
            return harvestEagerToolUses(scope, iterationCount, toolUses, runner, scheduler);
        }

        // PAR-03: delegate batch execution to the dispatcher. The gate (inside the dispatcher) consults
        // sessionRegistry — the same source executeSingleTool uses for the interrupt-behaviour lookup. When the
        // gate fails (default sequential dispatcher, single tool, or a SEQUENTIAL/unsafe tool in the batch) this is
        // 1:1 equivalent to the previous for-loop, including event ordering. executeSingleTool is unchanged and still
        // owns Pre/PostTool hooks, permission checks, and interrupt registration.
        return parallelToolDispatcher.dispatch(toolUses, sessionRegistry, runner,
                toolUse -> scope.eventDispatcher.emitToolUseStarted(iterationCount, toolUse),
                (toolUse, result) -> scope.eventDispatcher.emitToolResultReady(iterationCount, toolUse, result));
    }

    /**
     * Builds the single-tool execution callback the dispatcher / scheduler invoke per tool_use. It closes over the
     * effective tool context (the active-span-enriched {@link LlmCallMetadata}) so eager and harvest execution use the
     * same {@code executeSingleTool} pathway — Pre/PostTool hooks, permission checks, interrupt registration all
     * unchanged.
     *
     * <p>
     * Interrupt gate (interrupt design §7): the callback is gated on the turn's cancellation state, so a trip that
     * lands <em>mid-batch</em> stops the batch instead of only the next iteration. Without this, a tool that
     * cooperatively tripped the signal (or an external Ctrl+C landing between two tools) still let every remaining
     * {@code tool_use} of the same LLM response execute — a user who cancelled at tool 1 of 5 watched the other four
     * run to completion.
     *
     * <p>
     * The gate lives here rather than in the dispatcher so all three dispatch shapes inherit it from one place:
     * sequential batches, parallel batches, and the eager/harvest split. It deliberately gates only work that has
     * <b>not started</b>, and "started" is measured on the thread about to run the tool: the callback body is what
     * evaluates the gate, and under parallel/eager dispatch that body runs on the pool worker. A task already
     * submitted to the dispatch pool but not yet picked up by a worker is therefore still skipped. Only a tool that
     * has already entered {@link #executeSingleTool} is awaited and contributes its real result — abandoning those
     * would leak running work and lose side effects the model already caused; stopping them is the {@code Terminator}
     * machinery's job, not this gate's.
     *
     * <p>
     * A skipped tool returns an error result rather than nothing: every {@code tool_use} in the assistant message
     * must be answered by exactly one {@code tool_result}, or the provider rejects the conversation on the next turn.
     */
    private Function<ToolUse, ToolUseResult> toolRunner(ExecutionScope scope, ToolContext toolContext,
            int iterationCount, InterruptCoordinator coordinator, ToolRegistry sessionRegistry) {
        final ToolContext effectiveToolContext = resolveEffectiveToolContext(scope, toolContext);
        return toolUse -> {
            if (isInterrupted(coordinator)) {
                log.debug("Skipping tool_use {} ({}): the turn was interrupted before it started", toolUse.getId(),
                        toolUse.getName());
                return ToolUseResult.error(toolUse.getId(), INTERRUPTED_TOOL_SKIP_MESSAGE);
            }
            return executeSingleTool(scope.agentRuntime, effectiveToolContext, toolUse, iterationCount,
                    scope.getExecutionAttributes(), coordinator, sessionRegistry);
        };
    }

    /**
     * TRACE-01: when tracing is active, give tools the active (ITERATION) span's metadata so TOOL spans — and any
     * subagents they spawn — nest under the current iteration. A no-op tracer returns the same metadata instance, so
     * the tool context is passed through unchanged (zero overhead when tracing is off).
     */
    private ToolContext resolveEffectiveToolContext(ExecutionScope scope, ToolContext toolContext) {
        final LlmCallMetadata iterationMetadata = scope.activeSpan.enrich(scope.llmCallMetadata);
        return iterationMetadata == scope.llmCallMetadata
                ? toolContext
                : ToolContext.builder().putAll(toolContext.getContext())
                        .put(ToolContextKeys.LLM_CALL_METADATA_KEY, iterationMetadata).build();
    }

    /**
     * Harvests a batch whose safe prefix already executed eagerly during the token stream. Iterates the batch in
     * the model's original order: an eager tool's result is joined; a deferred (non-eager) tool runs inline here,
     * exactly as the sequential dispatch path would. {@code onStarted}/{@code onCompleted} fire per tool in input
     * order,
     * so the event stream matches the non-overlap path.
     */
    private List<ToolUseResult> harvestEagerToolUses(ExecutionScope scope, int iterationCount, List<ToolUse> toolUses,
            Function<ToolUse, ToolUseResult> runner, StreamingToolScheduler scheduler) {
        final List<ToolUseResult> results = new ArrayList<>(toolUses.size());
        for (ToolUse toolUse : toolUses) {
            scope.eventDispatcher.emitToolUseStarted(iterationCount, toolUse);
            final ToolUseResult result = scheduler.hasEager(toolUse.getId())
                    ? scheduler.joinEager(toolUse)
                    : runToolOnCallingThread(runner, toolUse);
            scope.eventDispatcher.emitToolResultReady(iterationCount, toolUse, result);
            results.add(result);
        }
        return results;
    }

    /**
     * Runs a single deferred tool on the calling thread for the harvest suffix, isolating a runner contract
     * violation (null return or throw) into an error result — mirroring the dispatcher's own {@code runSafely} so the
     * suffix behaves identically to the sequential path.
     */
    private ToolUseResult runToolOnCallingThread(Function<ToolUse, ToolUseResult> runner, ToolUse toolUse) {
        try {
            final ToolUseResult result = runner.apply(toolUse);
            if (result != null) {
                return result;
            }
            log.error("Tool runner returned null for tool_use {} ({})", toolUse.getId(), toolUse.getName());
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: runner returned no result");
        } catch (Exception e) {
            log.error("Tool runner threw for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(), e.getMessage(),
                    e);
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: " + e.getMessage());
        }
    }

    /**
     * Discards any eager tool work for the current iteration without harvesting it — used on the skill-approval
     * suspend path that abandons the response. Safe because eager tools are side-effect-free; their partial execution
     * is
     * simply dropped. No-op when overlap is off.
     */
    private void discardEagerToolUses(ExecutionScope scope) {
        final StreamingToolScheduler scheduler = scope.streamingToolScheduler;
        if (scheduler != null) {
            scheduler.cancelAll();
        }
    }

    /**
     * Creates a success result with proper metadata and hooks.
     *
     * @param scope
     *            The execution scope
     * @param finalAnswer
     *            The final answer from the agent
     * @param iterationCount
     *            The number of iterations completed
     * @param accumulatedTokens
     *            The accumulated token usage
     * @return The success result (never null)
     */
    private OrcaAgentExecutionResult createSuccessResult(ExecutionScope scope, String finalAnswer, int iterationCount,
            TokenUsage accumulatedTokens) {
        log.info("Agent execution completed successfully in {} iterations", iterationCount);
        log.debug("Token usage: {}", accumulatedTokens);

        final ExecutionMetadata metadata = buildExecutionMetadata(iterationCount, accumulatedTokens, scope.startTime);

        invokeOnStop(scope, true, finalAnswer, metadata);

        // PSTREAM-09: thread the streaming flag through so the CLI/REPL can skip duplicate rendering of the final
        // answer when it has already been streamed via AssistantTextDelta events.
        return OrcaAgentExecutionResult
                .success(finalAnswer, scope.transcriptBuffer.toSnapshot(), metadata,
                        scope.artifactCollector.getArtifacts(), CompletionReason.COMPLETED, useStreaming)
                .withCompactionEvents(scope.compactionEvents);
    }

    /**
     * Creates a terminal result for a final turn that the provider truncated at its max-output-token limit.
     *
     * <p>
     * Mirrors {@link #createSuccessResult} — same metadata, {@code OnStop} hooks, streaming flag, and compaction-event
     * propagation — but carries {@link CompletionReason#TRUNCATED} instead of {@link CompletionReason#COMPLETED}. The
     * {@code finalAnswer} passed in already carries the {@link #TRUNCATION_MARKER} suffix. The underlying result's
     * {@code success} flag stays {@code true} (the flagged partial text is still surfaced to the caller), while the
     * completion reason lets callers detect that the answer is incomplete via {@code getCompletionReason()} /
     * {@link CompletionReason#isSuccessful()}.
     *
     * @param scope
     *            The execution scope
     * @param finalAnswer
     *            The partial answer, already suffixed with {@link #TRUNCATION_MARKER}
     * @param iterationCount
     *            The number of iterations completed
     * @param accumulatedTokens
     *            The accumulated token usage
     * @return The truncated result (never null)
     */
    private OrcaAgentExecutionResult createTruncatedResult(ExecutionScope scope, String finalAnswer, int iterationCount,
            TokenUsage accumulatedTokens) {
        log.warn("Agent execution truncated at max_tokens after {} iterations; surfacing flagged partial answer",
                iterationCount);
        log.debug("Token usage: {}", accumulatedTokens);

        final ExecutionMetadata metadata = buildExecutionMetadata(iterationCount, accumulatedTokens, scope.startTime);

        invokeOnStop(scope, true, finalAnswer, metadata);

        return OrcaAgentExecutionResult
                .success(finalAnswer, scope.transcriptBuffer.toSnapshot(), metadata,
                        scope.artifactCollector.getArtifacts(), CompletionReason.TRUNCATED, useStreaming)
                .withCompactionEvents(scope.compactionEvents);
    }

    /**
     * TRACE-01: wraps {@link #dispatchSingleTool} in a {@code TOOL} span parented to the active turn span (read from
     * the tool context metadata). Captures the tool name, input, iteration, and success/error outcome. When tracing is
     * off (no enriched span context in the metadata), no span is created and dispatch runs unchanged.
     */
    private ToolUseResult executeSingleTool(OrcaAgentRuntime agentRuntime, ToolContext toolContext, ToolUse toolUse,
            int iterationCount, Map<String, Object> executionAttributes, InterruptCoordinator coordinator,
            ToolRegistry sessionRegistry) {
        final Tracer.Span toolSpan = openToolSpan(toolContext, toolUse, iterationCount);
        if (toolSpan == null) {
            return dispatchSingleTool(agentRuntime, toolContext, toolUse, iterationCount, executionAttributes,
                    coordinator, sessionRegistry);
        }
        try {
            final ToolUseResult result = dispatchSingleTool(agentRuntime, toolContext, toolUse, iterationCount,
                    executionAttributes, coordinator, sessionRegistry);
            recordToolOutcome(toolSpan, result, tracePayloadPolicy);
            return result;
        } catch (RuntimeException e) {
            toolSpan.error(e);
            throw e;
        } finally {
            toolSpan.close();
        }
    }

    /**
     * Opens a {@code TOOL} span as a child of the turn span encoded in the tool context's
     * {@link ToolContextKeys#LLM_CALL_METADATA_KEY} metadata, or returns {@code null} when tracing is off / the call
     * was not enriched (so no orphan span is produced).
     */
    private Tracer.Span openToolSpan(ToolContext toolContext, ToolUse toolUse, int iterationCount) {
        final Optional<SpanContext> parent = toolContext.get(ToolContextKeys.LLM_CALL_METADATA_KEY)
                .flatMap(SpanContext::readFrom);
        if (parent.isEmpty()) {
            return null;
        }
        final Tracer.Span span = safeStartChild(parent.get(), SpanType.TOOL, toolUse.getName(),
                Map.of("toolUseId", toolUse.getId(), "input", toolUse.getInput()));
        span.setAttribute("iteration", String.valueOf(iterationCount));
        return span;
    }

    /**
     * Fail-safe span start: a misbehaving {@link Tracer} that throws from {@code startChild} falls back to a no-op span
     * so tracing can never break agent execution (mirrors the turn-span guard in {@link #execute}).
     */
    private Tracer.Span safeStartChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
        try {
            return tracer.startChild(parent, type, name, inputs);
        } catch (RuntimeException e) {
            log.warn("Tracer.startChild failed; using a no-op span: {}", e.getMessage());
            return Tracer.noop().startChild(parent, type, name, inputs);
        }
    }

    /**
     * Records the tool span outcome. Always captures the {@code isError}/{@code contentChars} summary; TRACE-02: when
     * {@code policy} {@link TracePayloadPolicy#capturesContent() captures content}, the (truncated) result content is
     * additionally attached under {@code content}.
     */
    private static void recordToolOutcome(Tracer.Span toolSpan, ToolUseResult result, TracePayloadPolicy policy) {
        final String content = result.getContent();
        final Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("isError", result.isError());
        outputs.put("contentChars", content == null ? 0 : content.length());
        if (policy.capturesContent() && content != null) {
            outputs.put("content", policy.truncate(content));
        }
        toolSpan.setOutputs(outputs);
        if (result.isError()) {
            toolSpan.error(content);
        }
    }

    /**
     * Executes a single tool use, including pre-tool and post-tool hooks.
     *
     * <p>
     * Before invoking the tool, the target's {@link Tool#getInterruptBehavior()} is read from
     * {@code sessionRegistry}. Tools declaring {@link InterruptBehavior#THREAD_INTERRUPT} or
     * {@link InterruptBehavior#EXTERNALLY_TERMINATED} receive a fresh {@link TerminatorRegistrar} from
     * {@code coordinator} injected into the enriched {@link ToolContext}, and THREAD_INTERRUPT tools additionally get
     * a {@code Thread.currentThread()::interrupt} terminator pre-registered. The registrar is always closed in a
     * finally block so terminators cannot leak into subsequent tool calls. Tools declaring COOPERATIVE or
     * NON_INTERRUPTIBLE see no registrar in their context; COOPERATIVE tools still observe the turn-scoped
     * {@link CancellationSignal} injected at the executor level.
     *
     * @param agentRuntime
     *            The agent runtime
     * @param toolContext
     *            The tool execution context
     * @param toolUse
     *            The tool use request from LLM
     * @param iterationCount
     *            The current iteration count
     * @param executionAttributes
     *            The execution attributes
     * @param coordinator
     *            The turn-scoped interrupt coordinator used to mint the per-tool registrar (must not be null)
     * @param sessionRegistry
     *            The per-session registry used to look up the target tool's {@link InterruptBehavior}
     *            (must not be null)
     * @return The tool use result (never null)
     */
    // checkstyle: ParameterNumber — `coordinator` and `sessionRegistry` thread the interrupt
    // machinery down to the per-tool invocation site; acknowledged.
    private ToolUseResult dispatchSingleTool(OrcaAgentRuntime agentRuntime, ToolContext toolContext, ToolUse toolUse,
            int iterationCount, Map<String, Object> executionAttributes, InterruptCoordinator coordinator,
            ToolRegistry sessionRegistry) {
        // Delegate the interrupt-registrar + PermissionRequest/PreTool/execute/PostTool sequence to the shared
        // pipeline. The main-agent variance is the MAIN_AGENT invoker identity and an empty allow-list (unrestricted).
        final Agent agent = agentRuntime.getAgent();
        final ToolInvocationSpec spec = ToolInvocationSpec.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName(agent.getName()).hookRegistry(agentRuntime.getHookRegistry())
                .environment(agentRuntime.getEnvironment()).executionAttributes(executionAttributes)
                .toolRegistry(agentRuntime.getToolRegistry()).sessionRegistry(sessionRegistry).allowedTools(List.of())
                .coordinator(coordinator).toolContext(toolContext).toolUse(toolUse).iterationCount(iterationCount)
                .build();
        return singleToolInvoker.invoke(spec);
    }

    /**
     * Invokes the OnStart hooks before agent execution begins.
     *
     * @param scope
     *            The execution scope
     * @param userMessage
     *            The user message that triggered the execution
     * @return The list of hook results from all OnStart hooks
     */
    private List<HookResult> invokeOnStart(ExecutionScope scope, String userMessage) {
        final OnStartContext onStartContext = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT)
                .invokerName(scope.getAgent().getName()).hookRegistry(scope.getHookRegistry())
                .environment(scope.getEnvironment()).userMessage(userMessage)
                .executionAttributes(scope.getExecutionAttributes()).build();
        return hookExecutionManager.executeOnStart(onStartContext);
    }

    /**
     * Invokes the OnStop hooks after agent execution completes.
     *
     * @param scope
     *            The execution scope
     * @param success
     *            Whether the execution was successful
     * @param finalAnswer
     *            The final answer or error message from the execution
     * @param metadata
     *            The execution metadata including iteration count, token usage, and timestamps
     */
    private void invokeOnStop(ExecutionScope scope, boolean success, String finalAnswer, ExecutionMetadata metadata) {
        // Interrupt-flag hygiene: OnStop hooks are the turn's last hooks and run on whatever thread finalised
        // it — which, on the error path, is a thread the loop's checkpoints never swept (an LlmClient that restored
        // the flag before throwing reaches handleExecutionError directly). A live flag makes the hook executor's timed
        // get throw InterruptedException, which its fail-open policy maps to a successful HookResult, so the hooks
        // would be silently skipped. Consume it here, ahead of the hooks, rather than in execute()'s finally which
        // runs only after every terminal path has already invoked them.
        consumeLingeringInterrupt(scope, "OnStop");
        final OnStopContext onStopContext = OnStopContext.builder().executorType(InvokerType.MAIN_AGENT)
                .invokerName(scope.getAgent().getName()).hookRegistry(scope.getHookRegistry())
                .environment(scope.getEnvironment()).success(success).finalAnswer(finalAnswer).metadata(metadata)
                .executionAttributes(scope.getExecutionAttributes()).build();
        hookExecutionManager.executeOnStop(onStopContext);
    }

    /**
     * Death-spiral guard: decides whether a completed iteration was <em>stalled</em> — one that issued tool
     * calls but had <b>every</b> tool result come back an error, i.e. the model acted yet made no forward progress.
     *
     * <p>
     * An iteration with no tool calls at all is never stalled: the loop treats an empty-tool response as a terminal
     * answer and this predicate is not reached for it. An iteration where at least one tool succeeded is treated as
     * progress and resets the consecutive-stall counter. This keeps the guard conservative — it fires only on
     * genuinely non-converging iterations, not on a single transient tool failure.
     *
     * @param toolUseResults
     *            the results of the tools executed this iteration (never null; may be empty)
     * @return {@code true} if the iteration issued at least one tool call and all of them failed
     */
    static boolean isStalledIteration(List<ToolUseResult> toolUseResults) {
        return !toolUseResults.isEmpty() && toolUseResults.stream().allMatch(ToolUseResult::isError);
    }

    /**
     * Death-spiral guard: finalises a ReAct loop that has produced
     * {@link #MAX_CONSECUTIVE_STALLED_ITERATIONS} consecutive {@link #isStalledIteration(List) stalled iterations}.
     *
     * <p>
     * Mirrors {@link #handleBudgetStop} structurally: builds execution metadata from the current accumulators, invokes
     * the {@code OnStop(success=false)} hooks, emits an {@link ExecutionCompleted} boundary carrying
     * {@link CompletionReason#ERROR}, and returns a failure result. The assistant message and tool_results for the
     * final stalled iteration have already been committed to memory by the loop, so the snapshot reflects the point
     * at
     * which convergence was abandoned.
     *
     * @param scope
     *            the execution scope (must not be null)
     * @param iterationCount
     *            the number of iterations completed when the guard tripped
     * @param accumulatedTokens
     *            the accumulated token usage at the trip point
     * @param stalledIterations
     *            the consecutive stalled-iteration count that tripped the guard (for the diagnostic message)
     * @return the failure result carrying {@link CompletionReason#ERROR} (never null)
     */
    private OrcaAgentExecutionResult handleStalledIteration(ExecutionScope scope, int iterationCount,
            TokenUsage accumulatedTokens, int stalledIterations) {
        final String stopMessage = "Execution aborted: " + stalledIterations
                + " consecutive tool-only iterations made no progress (all tool calls failed)";
        log.warn("Death-spiral guard tripped: consecutiveStalledIterations={}, iterations={}, tokens={}",
                stalledIterations, iterationCount, accumulatedTokens.getTotalTokens());

        final ExecutionMetadata metadata = buildExecutionMetadata(iterationCount, accumulatedTokens, scope.startTime);

        invokeOnStop(scope, false, stopMessage, metadata);

        scope.eventDispatcher.emitExecutionCompleted(iterationCount, CompletionReason.ERROR);

        return OrcaAgentExecutionResult
                .failure(stopMessage, scope.transcriptBuffer.toSnapshot(), metadata,
                        scope.artifactCollector.getArtifacts(), CompletionReason.ERROR, useStreaming)
                .withCompactionEvents(scope.compactionEvents);
    }

    /**
     * Handles a {@link BudgetDecision#STOP} by finalising execution with the appropriate {@link CompletionReason}.
     *
     * <p>
     * The assistant text from the previous iteration (if any) is already preserved in the session snapshot
     * attached
     * to the result; the {@code finalAnswer} slot carries a concise explanation of the stop cause so callers that
     * ignore
     * the snapshot still see why execution ended.
     *
     * @param scope
     *            The execution scope
     * @param iterationCount
     *            The number of iterations completed before the stop decision
     * @param accumulatedTokens
     *            The accumulated token usage at the stop point
     * @return The failure result carrying the budget completion reason (never null)
     */
    private OrcaAgentExecutionResult handleBudgetStop(ExecutionScope scope, int iterationCount,
            TokenUsage accumulatedTokens) {
        final CompletionReason reason = scope.budgetTracker.getStopReason().orElse(CompletionReason.ABORTED);
        final String stopMessage = "Execution stopped by budget: " + reason;
        log.warn("Budget exhausted: reason={}, iterations={}, tokens={}, elapsed={}", reason, iterationCount,
                accumulatedTokens.getTotalTokens(), scope.budgetTracker.elapsed());

        final ExecutionMetadata metadata = buildExecutionMetadata(iterationCount, accumulatedTokens, scope.startTime);

        invokeOnStop(scope, false, stopMessage, metadata);

        // STREAM-03: budget-driven terminal boundary carries the concrete stop reason.
        scope.eventDispatcher.emitExecutionCompleted(iterationCount, reason);

        return OrcaAgentExecutionResult.failure(stopMessage, scope.transcriptBuffer.toSnapshot(), metadata,
                scope.artifactCollector.getArtifacts(), reason).withCompactionEvents(scope.compactionEvents);
    }

    /**
     * Finalises a ReAct loop whose turn-scoped {@link CancellationSignal} was tripped, either mid-iteration
     * (a cooperative tool returned early) or between iterations (an external caller invoked
     * {@link InterruptCoordinator#requestInterrupt(InterruptReason)} between a tool-result commit and the next
     * {@code sendMessage} call).
     *
     * <p>
     * Mirrors {@link #handleBudgetStop} structurally: builds execution metadata from the current iteration and token
     * accumulators, invokes the {@code OnStop(success=false)} hooks, emits an {@link ExecutionCompleted} boundary
     * carrying {@link CompletionReason#INTERRUPTED}, and returns a failure result whose final-answer slot carries a
     * concise "interrupted" marker so callers that ignore the session snapshot still see the termination cause.
     *
     * @param scope
     *            The execution scope (must not be null)
     * @param iterationCount
     *            The number of iterations completed before the trip was observed
     * @param accumulatedTokens
     *            The accumulated token usage at the interrupt point
     * @return The failure result carrying {@link CompletionReason#INTERRUPTED} (never null)
     */
    private OrcaAgentExecutionResult handleInterrupted(ExecutionScope scope, int iterationCount,
            TokenUsage accumulatedTokens) {
        final String stopMessage = "Execution interrupted";
        log.info("Execution interrupted: iterations={}, tokens={}", iterationCount, accumulatedTokens.getTotalTokens());

        final ExecutionMetadata metadata = buildExecutionMetadata(iterationCount, accumulatedTokens, scope.startTime);

        invokeOnStop(scope, false, stopMessage, metadata);

        scope.eventDispatcher.emitExecutionCompleted(iterationCount, CompletionReason.INTERRUPTED);

        // PSTREAM-09: preserve the streaming flag on the interrupted result so callers that streamed partial text
        // don't re-render it from the final failure payload.
        return OrcaAgentExecutionResult
                .failure(stopMessage, scope.transcriptBuffer.toSnapshot(), metadata,
                        scope.artifactCollector.getArtifacts(), CompletionReason.INTERRUPTED, useStreaming)
                .withCompactionEvents(scope.compactionEvents);
    }

    /**
     * SK-11.4: finalises a ReAct loop whose pre-flight scan flagged at least one {@code Skill} tool_use as needing
     * out-of-band approval.
     *
     * <p>
     * Performs an "atomic suspension" — neither the assistant message nor any tool_result for the in-flight LLM
     * iteration is committed to {@link TranscriptBuffer}. Registers a {@link PendingTurn} on the configured registry,
     * emits a {@link SkillTurnSuspendedEvent} so approval channels can surface it to the user, then mirrors
     * {@link #handleInterrupted} for the OnStop hook + iteration-complete + execution-complete sequence and returns a
     * failure result with {@link CompletionReason#SUSPENDED}.
     *
     * @param scope
     *            the execution scope (must not be null)
     * @param iterationCount
     *            the number of iterations completed when the suspension was decided
     * @param accumulatedTokens
     *            the accumulated token usage at the suspension point
     * @param pendingSkills
     *            the requests that need user approval (must not be null or empty)
     * @return the failure result carrying {@link CompletionReason#SUSPENDED} (never null)
     */
    private OrcaAgentExecutionResult handleSuspended(ExecutionScope scope, int iterationCount,
            TokenUsage accumulatedTokens, List<PendingSkillRequest> pendingSkills) {
        final Instant now = Instant.now();
        final PendingTurn turn = PendingTurn.builder().id(PendingTurnId.generate())
                .agentRuntimeId(scope.agentRuntime.getId()).sessionId(scope.transcriptBuffer.getSessionId())
                .pendingSkills(pendingSkills).createdAt(now).ttl(pendingTurnTtl).build();
        pendingTurnRegistry.register(turn);

        final String stopMessage = "Execution suspended pending approval for " + pendingSkills.size()
                + " skill invocation(s); pendingTurnId=" + turn.getId();
        log.info("Skill turn suspended: pendingTurnId={}, skills={}, iteration={}", turn.getId(), pendingSkills.size(),
                iterationCount);

        final ExecutionMetadata metadata = buildExecutionMetadata(iterationCount, accumulatedTokens, scope.startTime);

        invokeOnStop(scope, false, stopMessage, metadata);

        scope.eventDispatcher.emitSkillTurnSuspended(iterationCount, turn);
        scope.eventDispatcher.emitIterationCompleted(iterationCount, false);
        scope.eventDispatcher.emitExecutionCompleted(iterationCount, CompletionReason.SUSPENDED);

        return OrcaAgentExecutionResult
                .failure(stopMessage, scope.transcriptBuffer.toSnapshot(), metadata,
                        scope.artifactCollector.getArtifacts(), CompletionReason.SUSPENDED)
                .withCompactionEvents(scope.compactionEvents);
    }

    /**
     * Finalises a ReAct loop that reached the agent-metadata {@code maxIterations} ceiling.
     *
     * <p>
     * The iteration ceiling is a policy limit, not an error, so this path mirrors {@link #handleBudgetStop} rather than
     * {@link #handleExecutionError}: it builds execution metadata, invokes the {@code OnStop(success=false)} hooks,
     * emits an {@link ExecutionCompleted} boundary carrying {@link CompletionReason#MAX_ITERATIONS}, and returns a
     * failure result whose {@code finalAnswer} slot carries a concise cause marker. Replacing the former
     * {@code throw new MaxIterationsExceededException} keeps the exhaustion out of control flow and aligns the result's
     * completion reason with the emitted event (previously the generic error path returned {@code ERROR} while emitting
     * {@code MAX_ITERATIONS}).
     *
     * <p>
     * The last iteration already emitted its {@link #emitIterationCompleted} boundary at the loop tail, so — like
     * {@link #handleBudgetStop} — this method does not emit another one.
     *
     * @param scope
     *            the execution scope (must not be null)
     * @param iterationCount
     *            the number of iterations completed (equals {@code maxIterations} at this point)
     * @param accumulatedTokens
     *            the accumulated token usage at the ceiling
     * @param maxIterations
     *            the configured ceiling, surfaced in the stop message
     * @return the failure result carrying {@link CompletionReason#MAX_ITERATIONS} (never null)
     */
    private OrcaAgentExecutionResult handleMaxIterations(ExecutionScope scope, int iterationCount,
            TokenUsage accumulatedTokens, int maxIterations) {
        final String stopMessage = "Maximum iterations exceeded: " + maxIterations;
        log.warn("Max iterations reached: max={}, iterations={}, tokens={}", maxIterations, iterationCount,
                accumulatedTokens.getTotalTokens());

        final ExecutionMetadata metadata = buildExecutionMetadata(iterationCount, accumulatedTokens, scope.startTime);

        invokeOnStop(scope, false, stopMessage, metadata);

        // STREAM-03: policy-driven clean termination — the agent-metadata iteration ceiling acts as a fixed-size
        // iteration budget, emitted as ExecutionCompleted(MAX_ITERATIONS) for symmetry with budget-driven STOP.
        scope.eventDispatcher.emitExecutionCompleted(iterationCount, CompletionReason.MAX_ITERATIONS);

        return OrcaAgentExecutionResult
                .failure(stopMessage, scope.transcriptBuffer.toSnapshot(), metadata,
                        scope.artifactCollector.getArtifacts(), CompletionReason.MAX_ITERATIONS)
                .withCompactionEvents(scope.compactionEvents);
    }

    /**
     * Handles execution errors by logging, invoking OnStop hooks, and creating a failure result.
     *
     * @param exception
     *            The exception that occurred
     * @param scope
     *            The execution scope
     * @param iterationCount
     *            The number of iterations completed
     * @param accumulatedTokens
     *            The accumulated token usage
     * @return The failure result (never null)
     */
    private OrcaAgentExecutionResult handleExecutionError(Exception exception, ExecutionScope scope, int iterationCount,
            TokenUsage accumulatedTokens) {
        final String errorMessage = determineErrorMessage(exception);
        logError(exception, errorMessage);

        final ExecutionMetadata metadata = buildExecutionMetadata(iterationCount, accumulatedTokens, scope.startTime);

        invokeOnStop(scope, false, errorMessage, metadata);

        // STREAM-03: terminal event emission. All exceptions reaching this path surface as ExecutionError with cause
        // preserved for observers. (Max-iterations exhaustion is no longer routed here — it is finalised via a normal
        // return in handleMaxIterations.)
        scope.eventDispatcher.emitExecutionError(exception, errorMessage);

        return OrcaAgentExecutionResult.failure(errorMessage, scope.transcriptBuffer.toSnapshot(), metadata,
                scope.artifactCollector.getArtifacts()).withCompactionEvents(scope.compactionEvents);
    }

    /**
     * Names the execution the memory provider is being asked about.
     *
     * <p>
     * Both fields are taken from the request rather than from anything the executor holds, because the executor is
     * agent-scoped: it serves every session of this agent, and a peer or session captured here would be the one every
     * session got. A session-less execution (subagent fork, rewake replay, scheduled routine) simply carries neither,
     * and the resolver decides what that means.
     */
    private static MemoryContextRequest buildMemoryContextRequest(OrcaAgentExecutionRequest executionRequest) {
        return MemoryContextRequest.builder().sessionId(executionRequest.getSessionId())
                .principal(executionRequest.getPrincipal().orElse(null)).build();
    }

    /**
     * Package-private test seam: builds the structured system prompt for the given agent content, delegating to
     * {@link SystemPromptRenderer}. External callers must go through the public {@code execute(...)} entry point.
     *
     * @param agentContent
     *            the agent content containing the base system prompt (must not be null)
     * @param systemPromptVariables
     *            the system prompt variables (must not be null, may be empty)
     * @param environment
     *            the runtime environment, or {@code null} to omit the environment segment
     * @return the structured prompt; never {@code null}
     */
    SystemPromptParts buildSystemPromptParts(AgentContent agentContent, Map<String, Object> systemPromptVariables,
            Environment environment) {
        return systemPromptRenderer.buildSystemPromptParts(agentContent, systemPromptVariables, environment);
    }

    /**
     * Gets the LLM client wrapped by the gateway.
     *
     * @return The LLM client (never null)
     */
    public LlmClient getLlmClient() {
        return gateway.getClient();
    }

    /**
     * Gets the LLM call gateway.
     *
     * @return The LLM call gateway (never null)
     */
    public LlmCallGateway<TranscriptBuffer> getGateway() {
        return gateway;
    }

    /**
     * Gets the transcript manager.
     *
     * @return The transcript manager (never null)
     */
    public TranscriptManager getTranscriptManager() {
        return transcriptManager;
    }

    /**
     * Gets the tool execution manager.
     *
     * @return The tool execution manager (never null)
     */
    public ToolExecutionManager getToolExecutionManager() {
        return toolExecutionManager;
    }

    /**
     * Gets the hook execution manager.
     *
     * @return The hook execution manager (never null)
     */
    public HookExecutionManager getHookExecutionManager() {
        return hookExecutionManager;
    }

    /**
     * Gets the command execution manager.
     *
     * @return The command execution manager (never null)
     */
    public CommandExecutionManager getCommandExecutionManager() {
        return commandExecutionManager;
    }

    /**
     * Gets the subagent execution manager.
     *
     * @return The subagent execution manager (never null)
     */
    public SubagentExecutionManager getSubagentExecutionManager() {
        return subagentExecutionManager;
    }

    // =================================================================================================================
    // STREAM-03: streaming event support
    // =================================================================================================================

    /**
     * Registers {@code listener} to receive {@link AgentExecutionEvent}s emitted by subsequent executions.
     *
     * <p>
     * Listeners are invoked on the executor's thread at each progress point. Exceptions thrown by listeners are caught
     * and logged by the framework; they do not propagate back to the ReAct loop or cancel the execution.
     *
     * <p>
     * <b>Package-private by design:</b> this global fan-out model is an Orca-specific extension that is not part of
     * the public {@link StreamingAgentExecutor} contract. External consumers should subscribe per-execution via
     * {@link #events(OrcaAgentRuntime, OrcaAgentExecutionRequest)} or
     * {@link #executeAsync(OrcaAgentRuntime, OrcaAgentExecutionRequest, Consumer)} instead.
     *
     * @param listener
     *            the listener to register (must not be null)
     * @throws NullPointerException
     *             if {@code listener} is null
     */
    void addEventListener(Consumer<AgentExecutionEvent> listener) {
        eventEmitter.addListener(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * <p>
     * Removal is identity-based: only the first registered instance {@code ==} to {@code listener} is removed.
     *
     * <p>
     * Package-private: see {@link #addEventListener(Consumer)} for the rationale.
     *
     * @param listener
     *            the listener to remove (must not be null)
     * @return {@code true} if the listener was found and removed; {@code false} otherwise
     * @throws NullPointerException
     *             if {@code listener} is null
     */
    boolean removeEventListener(Consumer<AgentExecutionEvent> listener) {
        return eventEmitter.removeListener(listener);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Returns a cold, unicast publisher: the agent is executed on the first subscription. The publisher delivers every
     * emitted event (including a terminal {@link ExecutionCompleted} or {@link ExecutionError}) on the caller thread
     * and then calls {@link Flow.Subscriber#onComplete()}. Listeners registered via {@link #addEventListener(Consumer)}
     * continue to receive the same events in parallel.
     */
    @Override
    public Flow.Publisher<AgentExecutionEvent> events(OrcaAgentRuntime context, OrcaAgentExecutionRequest request) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(request, "request cannot be null");
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber cannot be null");
            final Executor inlineExecutor = Runnable::run;
            try (SubmissionPublisher<AgentExecutionEvent> publisher = new SubmissionPublisher<>(inlineExecutor,
                    Flow.defaultBufferSize())) {
                publisher.subscribe(subscriber);
                final Consumer<AgentExecutionEvent> forwarder = publisher::submit;
                // H1: deliver events for THIS execution only — register on the turn's own scope sink (via the
                // per-execution execute overload), not the shared emitter, to avoid cross-session bleed.
                execute(context, request, forwarder);
            }
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Executes the agent synchronously on a fresh per-call single-thread executor, invoking {@code listener} once per
     * emitted event in wall-clock order. Exceptions thrown by {@code listener} are caught and logged; they do not
     * abort the execution or prevent subsequent events from being delivered.
     *
     * <p>
     * <b>Executor lifecycle:</b> each call allocates its own {@link ExecutorService} and shuts it down in
     * {@link CompletableFuture#whenComplete} to prevent single-thread-executor leaks when callers repeatedly invoke
     * {@code executeAsync}. The worker thread is a daemon, so a missed shutdown would not block JVM exit, but the
     * explicit {@code shutdown()} keeps the thread table clean across long-lived test runs and embedded hosts.
     */
    @Override
    public CompletionStage<OrcaAgentExecutionResult> executeAsync(OrcaAgentRuntime context,
            OrcaAgentExecutionRequest request, Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(listener, "listener cannot be null");
        final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "orca-executeAsync");
            t.setDaemon(true);
            return t;
        });
        return CompletableFuture.supplyAsync(() -> {
            // H1: deliver events for THIS execution only — the listener is registered on the turn's own scope sink
            // (via the per-execution execute overload), not the shared emitter, to avoid cross-session bleed.
            return execute(context, request, listener);
        }, asyncExecutor).whenComplete((result, error) -> asyncExecutor.shutdown());
    }

    // =================================================================================================================
    // PSTREAM-09: streaming integration
    // =================================================================================================================

    /**
     * PSTREAM-09: issues one LLM call using either the streaming or non-streaming gateway entry point.
     *
     * <p>
     * In non-streaming mode this is a thin delegate to {@link LlmCallGateway#sendMessage}. In streaming mode a
     * fresh {@link StreamingEventSink} is allocated per iteration and threaded to
     * {@link LlmCallGateway#sendMessageStreaming}; if the sink raises a {@link CancelledExecutionException} during
     * {@link LlmStreamChunk.Kind#TEXT_DELTA} checkpointing, the accumulated partial text is appended to the
     * conversation as a user-less assistant message so the next LLM call has an accurate history view, a terminal
     * {@link AssistantTextStreamCompleted} event carrying {@code finishReason="interrupted"} is emitted, and the
     * exception is rethrown so the outer ReAct loop routes into {@link #handleInterrupted}. If any other
     * {@link RuntimeException} escapes the gateway after at least one delta was emitted, a synthetic
     * {@code finishReason="error"} completion is emitted so UI consumers observe a terminal event for every started
     * stream; the exception is then rethrown so the ReAct loop's normal error path handles it.
     *
     * @param scope
     *            the active execution scope (must not be null)
     * @param iteration
     *            the 1-based iteration number currently executing
     * @param availableTools
     *            the tool definitions visible to the LLM for this iteration
     * @param cancellationSignal
     *            the turn-scoped cancellation signal polled from inside the streaming sink
     * @return the aggregated LLM response
     */
    private LlmResponse invokeGateway(ExecutionScope scope, int iteration, List<ToolDefinition> availableTools,
            CancellationSignal cancellationSignal, SignalBackedLlmCancellation llmCancellation) {
        try {
            return invokeGatewayOnce(scope, iteration, availableTools, cancellationSignal, llmCancellation);
        } catch (LlmPromptTooLongException e) {
            // Post-call prompt-too-long fallback. Consult the recovery strategy to drop the
            // oldest non-protected message and retry once. If the strategy declines (NONE) or the retry itself fails,
            // the original behaviour resumes — the exception propagates to the ReAct loop's error path.
            final PromptSizeRecoveryStrategy strategy = scope.agentRuntime.getPromptSizeRecoveryStrategy()
                    .orElse(NoOpPromptSizeRecoveryStrategy.instance());
            final List<Message> currentMessages = scope.transcriptBuffer.getMessages();
            final PromptSizeRecoveryDecision decision = strategy.recover(currentMessages, e);
            if (decision.getAction() != PromptSizeRecoveryDecision.Action.RETRY) {
                log.warn("Prompt-too-long recovery declined at iteration {} ({}); rethrowing", iteration,
                        decision.getReason());
                throw e;
            }
            final List<Message> recovered = decision.getRecoveredMessages()
                    .orElseThrow(() -> new IllegalStateException("RETRY decision must carry recoveredMessages"));
            log.warn("Prompt-too-long recovery applied at iteration {}: {}", iteration, decision.getReason());
            scope.transcriptBuffer.replaceWith(recovered);
            return invokeGatewayOnce(scope, iteration, availableTools, cancellationSignal, llmCancellation);
        }
    }

    private LlmResponse invokeGatewayOnce(ExecutionScope scope, int iteration, List<ToolDefinition> availableTools,
            CancellationSignal cancellationSignal, SignalBackedLlmCancellation llmCancellation) {
        // TRACE-01: attribute this LLM call to the active span (the current ITERATION span, or the turn span outside
        // the loop). enrich(...) is a no-op under Tracer.noop(), so the metadata is unchanged when tracing is off.
        final LlmCallMetadata callMetadata = scope.activeSpan.enrich(scope.llmCallMetadata);
        if (!useStreaming) {
            try {
                return gateway.sendMessage(scope.systemPromptParts, scope.transcriptBuffer.getMessages(),
                        availableTools, scope.getAgent().getMetadata().getModel(), callMetadata, llmCancellation);
            } catch (LlmCallCancelledException e) {
                // Two sources can raise this on the blocking path: the gateway's pre-attempt short-circuit, and a
                // genuine in-flight abort — a cancellable token (isSupported()) makes the provider reroute the blocking
                // call through its streaming path, which owns a StreamResponse.close() abort lever. Both translate to
                // CancelledExecutionException so the ReAct loop unwinds through handleInterrupted like every other
                // cancellation.
                throw toCancelledExecution(cancellationSignal, e);
            }
        }

        // Streaming-tool overlap: re-arm eager dispatch for this attempt. On a prompt-too-long re-issue the same
        // per-iteration scheduler is reused, so this cancels any leftover futures from the discarded attempt and clears
        // the poison/disabled flags before the fresh stream starts. Null when overlap is off.
        final StreamingToolScheduler scheduler = scope.streamingToolScheduler;
        if (scheduler != null) {
            scheduler.resetForNewAttempt();
        }

        final StreamingEventSink sink = new StreamingEventSink(scope, iteration, cancellationSignal, scheduler);
        try {
            final LlmStreamTarget streamTarget = LlmStreamTarget.builder().options(streamingOptions).sink(sink)
                    .retryListener(sink::onRetry).build();
            return gateway.sendMessageStreaming(scope.systemPromptParts, scope.transcriptBuffer.getMessages(),
                    availableTools, scope.getAgent().getMetadata().getModel(), callMetadata, streamTarget,
                    llmCancellation);
        } catch (CancelledExecutionException e) {
            // The turn is being cancelled mid-stream — drop any eager tool work (side-effect-free, so safe to
            // discard) before preserving the streamed prefix.
            if (scheduler != null) {
                scheduler.cancelAll();
            }
            final String partial = sink.peekText();
            if (!partial.isEmpty()) {
                // Preserve the streamed prefix so the final session snapshot and any follow-up LLM call carry
                // exactly what the user saw. Tool uses are intentionally empty — a mid-stream cancel cannot complete
                // a tool_call.
                scope.transcriptBuffer.addMessage(Message.assistant(partial, List.of()));
            }
            sink.emitInterruptedCompletion();
            throw e;
        } catch (LlmCallCancelledException e) {
            // LLM-CANCEL: the provider actively aborted the HTTP stream because the turn signal tripped (distinct from
            // the sink-checkpoint CancelledExecutionException path above — this fires when close() wins the race).
            // Treat
            // it identically: drop eager tool work, preserve the streamed prefix, emit an interrupted completion, then
            // translate to CancelledExecutionException so the ReAct loop routes into handleInterrupted.
            if (scheduler != null) {
                scheduler.cancelAll();
            }
            final String partial = sink.peekText();
            if (!partial.isEmpty()) {
                scope.transcriptBuffer.addMessage(Message.assistant(partial, List.of()));
            }
            sink.emitInterruptedCompletion();
            throw toCancelledExecution(cancellationSignal, e);
        } catch (RuntimeException e) {
            // Unexpected provider failure (or a prompt-too-long rethrow) after deltas were emitted: drop eager tool
            // work and close the stream with a synthetic "error" completion so UI consumers see a terminal event for
            // every started stream, then rethrow to the ReAct loop's normal error path (or invokeGateway's
            // prompt-too-long recovery, which re-arms via resetForNewAttempt above on the retry).
            if (scheduler != null) {
                scheduler.cancelAll();
            }
            sink.emitErrorCompletion();
            throw e;
        }
    }

    /**
     * Maps a provider/gateway {@link LlmCallCancelledException} onto the turn's {@link CancelledExecutionException},
     * carrying the {@link InterruptReason} recorded on the signal so downstream observability sees the true cause. The
     * original llm-side exception is attached as a suppressed exception for diagnostics. The signal is expected to be
     * tripped whenever this exception is produced; {@link InterruptReason#USER_SIGINT} is a defensive fallback.
     *
     * @param cancellationSignal
     *            the turn's signal, read for its trip reason
     * @param cause
     *            the llm-side cancellation exception being translated
     * @return the mapped {@link CancelledExecutionException} to throw into the ReAct loop
     */
    private static CancelledExecutionException toCancelledExecution(CancellationSignal cancellationSignal,
            LlmCallCancelledException cause) {
        final CancelledExecutionException mapped = new CancelledExecutionException(
                cancellationSignal.getReason().orElse(InterruptReason.USER_SIGINT));
        mapped.addSuppressed(cause);
        return mapped;
    }

    @Override
    public String toString() {
        return "OrcaAgentExecutor{" + "provider='" + gateway.getClient().getProviderName() + '\'' + '}';
    }

    /**
     * PSTREAM-09: per-iteration streaming sink. Bridges {@link LlmStreamChunk}s delivered by the gateway into
     * {@link AssistantTextDelta} / {@link AssistantTextStreamCompleted} / {@link AssistantTextStreamReset} events,
     * while mirroring the text into a {@link ChunkAggregator} so {@link #peekText()} exposes the prefix already shown
     * to the user in the event of a mid-stream cancel.
     *
     * <p>
     * Lifecycle:
     * <ul>
     * <li>One aggregator per attempt. On {@link #onRetry(int, int, String)} the slot is swapped for a fresh
     * aggregator and the per-attempt chunk counter is reset, ensuring {@link AssistantTextDelta#getChunkIndex()} is
     * monotonic within each attempt and starts at {@code 0} on the upcoming one.</li>
     * <li>Exactly one {@link AssistantTextStreamCompleted} per attempt — emitted either from the terminal
     * {@code STREAM_END} chunk or, on mid-stream cancel, via {@link #emitInterruptedCompletion()}.</li>
     * <li>A successful call receives its {@code STREAM_END} chunk through {@link #accept(LlmStreamChunk)} and
     * {@code emitInterruptedCompletion()} is <b>not</b> invoked on that path.</li>
     * </ul>
     */
    private final class StreamingEventSink implements LlmStreamSink {

        private final ExecutionScope scope;
        private final int iteration;
        private final CancellationSignal cancellationSignal;
        private final StreamingToolScheduler streamingToolScheduler;
        private ChunkAggregator aggregator;
        private int nextChunkIndex;
        private boolean completionEmitted;

        StreamingEventSink(ExecutionScope scope, int iteration, CancellationSignal cancellationSignal,
                StreamingToolScheduler streamingToolScheduler) {
            this.scope = Objects.requireNonNull(scope, "scope");
            this.iteration = iteration;
            this.cancellationSignal = Objects.requireNonNull(cancellationSignal, "cancellationSignal");
            // Nullable by design: null when streaming-tool overlap is off. A null scheduler simply ignores
            // TOOL_USE_READY chunks (which providers still emit), so overlap-off streaming is unchanged.
            this.streamingToolScheduler = streamingToolScheduler;
            this.aggregator = new ChunkAggregator();
            this.nextChunkIndex = 0;
            this.completionEmitted = false;
        }

        @Override
        public void accept(LlmStreamChunk chunk) {
            Objects.requireNonNull(chunk, "chunk");
            aggregator.accept(chunk);
            switch (chunk.getKind()) {
                case TEXT_DELTA -> {
                    final String delta = chunk.getTextDelta().orElseThrow();
                    scope.eventDispatcher.emitAssistantTextDelta(iteration, delta, nextChunkIndex++);
                    // Honour a trip landed during streaming. Throws CancelledExecutionException out of the
                    // provider's stream loop; invokeGateway catches it, preserves the partial prefix, and rethrows.
                    cancellationSignal.checkpoint();
                }
                case TOOL_USE_READY -> {
                    // Streaming-tool overlap: a tool_use block finished streaming. Hand it to the scheduler so a
                    // side-effect-free tool starts executing now, overlapping the remaining token stream. No lifecycle
                    // event is emitted here — onStarted/onCompleted fire at harvest in input order, keeping the event
                    // stream identical to the non-overlap path. Providers emit this chunk regardless of overlap config,
                    // so a null scheduler (overlap off) correctly ignores it.
                    if (streamingToolScheduler != null) {
                        streamingToolScheduler.onToolUseReady(chunk.getToolUse().orElseThrow());
                    }
                }
                case STREAM_END -> {
                    final TokenUsage tokenUsage = chunk.getTokenUsage().orElse(null);
                    final String finishReason = chunk.getFinishReason().orElse(null);
                    scope.eventDispatcher.emitAssistantTextStreamCompleted(iteration, aggregator.peekText().length(),
                            tokenUsage, finishReason);
                    completionEmitted = true;
                }
                default -> throw new IllegalStateException("Unknown chunk kind: " + chunk.getKind());
            }
        }

        /**
         * Gateway callback invoked between discarded attempts. Swaps the aggregator and emits the reset event so the
         * caller can clear any rendered prefix before the next attempt's chunks arrive.
         */
        void onRetry(int previousAttemptIndex, int nextAttemptIndex, String reason) {
            // Streaming-tool overlap: the previous attempt is being discarded — cancel and drop its eager tool
            // work so nothing from it is harvested, and keep eager off for the remainder of this streaming call (a
            // fresh
            // invokeGatewayOnce re-arms via resetForNewAttempt). The retried attempt's tools then run on the ordinary
            // harvest path. Safe because eager tools are side-effect-free.
            if (streamingToolScheduler != null) {
                streamingToolScheduler.disableForRetry();
            }
            scope.eventDispatcher.emitAssistantTextStreamReset(iteration, previousAttemptIndex, nextAttemptIndex,
                    reason);
            this.aggregator = new ChunkAggregator();
            this.nextChunkIndex = 0;
            this.completionEmitted = false;
        }

        /** @return the accumulated assistant text for the current attempt; never null. */
        String peekText() {
            return aggregator.peekText();
        }

        /**
         * Emits the synthetic "interrupted" completion event when a mid-stream cancel aborts the call before
         * STREAM_END is observed. No-op if a completion event has already been emitted for the current attempt.
         */
        void emitInterruptedCompletion() {
            if (completionEmitted) {
                return;
            }
            scope.eventDispatcher.emitAssistantTextStreamCompleted(iteration, aggregator.peekText().length(), null,
                    "interrupted");
            completionEmitted = true;
        }

        /**
         * Emits a synthetic {@code finishReason="error"} completion when the gateway raises an unexpected
         * {@link RuntimeException} after deltas have already been emitted. No-op if no deltas were emitted on the
         * current attempt (UI never observed a stream start) or if a completion has already been emitted.
         */
        void emitErrorCompletion() {
            if (completionEmitted || nextChunkIndex == 0) {
                return;
            }
            scope.eventDispatcher.emitAssistantTextStreamCompleted(iteration, aggregator.peekText().length(), null,
                    "error");
            completionEmitted = true;
        }
    }

    /**
     * Bundles the per-execution state that flows through the execution pipeline.
     *
     * <p>
     * Introduced to reduce method parameter counts. One instance per execution — never shared across turns, which is
     * what lets the mutable half below exist at all.
     *
     * <p>
     * The fields split in two, and the split is the contract:
     * <ul>
     * <li><b>{@code final}</b> — the collaborators and per-execution values fixed once (the six constructor parameters,
     * plus {@link #startTime}, {@link #artifactCollector}, {@link #compactionEvents} and {@link #eventSink}, which the
     * sole call site always derived here anyway). Note that {@code final} bounds the <i>reference</i>, not the object:
     * {@code artifactCollector} and {@code compactionEvents} are mutated throughout the execution.
     * <li><b>mutable</b> — {@link #eventDispatcher}, {@link #costSummary}, {@link #activeSpan},
     * {@link #streamingToolScheduler} and {@link #interruptConsumed} are assigned or reassigned after construction.
     * Each carries its own javadoc stating when it is written and by whom; those are the authority, not this list.
     * </ul>
     *
     * <p>
     * Thread-safety follows from that split rather than from any lock: the pipeline writes the mutable fields from the
     * execution thread, so the two that a parallel-tool worker thread may read concurrently ({@code activeSpan},
     * {@code interruptConsumed}) are {@code volatile}. A new mutable field readable off the execution thread needs the
     * same treatment.
     */
    private static class ExecutionScope {
        final OrcaAgentRuntime agentRuntime;
        final OrcaAgentExecutionRequest executionRequest;
        final TranscriptBuffer transcriptBuffer;
        final SystemPromptParts systemPromptParts;
        final LlmCallMetadata llmCallMetadata;
        // Defaults captured at scope construction — the sole call site always passed Instant.now() / a fresh
        // ArtifactCollector, so they are initialized here rather than threaded through the constructor.
        final Instant startTime = Instant.now();
        final ArtifactCollector artifactCollector = new ArtifactCollector();
        final BudgetTracker budgetTracker;
        final List<CompactionMetadata> compactionEvents = new ArrayList<>();

        /**
         * H1: per-execution event sink. The streaming surfaces ({@link #events} / {@link #executeAsync}) register
         * their listener HERE rather than on the executor-wide shared {@code eventEmitter}, so an agent-scoped
         * executor that serves multiple concurrent sessions never fans one turn's token/tool events out to
         * another turn's subscriber. Global observers registered via {@link #addEventListener} still receive every
         * execution's events through the shared emitter (their documented, opt-in cross-execution contract).
         */
        final EventEmitter eventSink = new EventEmitter();

        /**
         * Per-turn emitter of {@link AgentExecutionEvent}s to both the shared emitter and {@link #eventSink}. Assigned
         * once in {@code execute()} after the scope is built and before the first event is emitted.
         */
        AgentEventDispatcher eventDispatcher;

        /**
         * Per-model estimated cost accumulated across this execution's LLM calls. Reassigned functionally each
         * iteration ({@code costSummary = costSummary.record(...)}) since {@link CostSummary} is immutable, and
         * attached
         * once to the result at the {@code execute()} choke point. Starts empty (zero cost) so the field is safe even
         * when no cost estimator is wired.
         */
        CostSummary costSummary = CostSummary.empty();

        /**
         * TRACE-01: the span that downstream LLM/tool calls attach under. Set to the turn span after construction, then
         * swapped to the current ITERATION span for the duration of each iteration (restored in a finally). Volatile
         * because parallel tools read it on worker threads.
         */
        volatile Tracer.Span activeSpan = Tracer.noop().startRoot("", SpanType.TURN, "uninitialized", Map.of());

        /**
         * Streaming-tool overlap (design §11.2): the per-iteration eager-tool scheduler, or {@code null} when
         * overlap is off (non-streaming, parallelism disabled, or the {@code streamingOverlap} opt-in unset). Assigned
         * before each iteration's LLM call and consumed by {@link #executeToolUses}; discarded on suspend / cancel. Not
         * shared across iterations — a fresh instance (or {@code null}) is installed each iteration.
         */
        StreamingToolScheduler streamingToolScheduler;

        /**
         * Interrupt-flag hygiene: latched once a finalisation point consumed a live thread interrupt (see
         * {@code consumeLingeringInterrupt}). {@code execute()}'s finally reads it to decide whether the caller's
         * cancellation request still has to be re-armed on the way out — without the latch, an earlier consumption in
         * {@code invokeOnStop} would make the flag look like it was never set. Volatile so the latch is visible
         * whichever thread finalised the turn.
         */
        volatile boolean interruptConsumed;

        ExecutionScope(OrcaAgentRuntime agentRuntime, OrcaAgentExecutionRequest executionRequest,
                TranscriptBuffer transcriptBuffer, SystemPromptParts systemPromptParts, LlmCallMetadata llmCallMetadata,
                BudgetTracker budgetTracker) {
            this.agentRuntime = agentRuntime;
            this.executionRequest = executionRequest;
            this.transcriptBuffer = transcriptBuffer;
            this.systemPromptParts = systemPromptParts;
            this.llmCallMetadata = llmCallMetadata;
            this.budgetTracker = budgetTracker;
        }

        Agent getAgent() {
            return agentRuntime.getAgent();
        }

        Principal getPrincipal() {
            return executionRequest.getPrincipal().orElse(null);
        }

        Map<String, Object> getExecutionAttributes() {
            return executionRequest.getExecutionAttributes();
        }

        HookRegistry getHookRegistry() {
            return agentRuntime.getHookRegistry();
        }

        Environment getEnvironment() {
            return agentRuntime.getEnvironment();
        }

        ToolRegistry getToolRegistry() {
            return agentRuntime.getToolRegistry();
        }
    }

}
