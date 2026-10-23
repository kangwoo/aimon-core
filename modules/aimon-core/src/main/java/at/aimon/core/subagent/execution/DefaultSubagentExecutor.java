package at.aimon.core.subagent.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.budget.BudgetDecision;
import at.aimon.core.agent.budget.BudgetTracker;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.budget.ExecutionDeadline;
import at.aimon.core.agent.budget.StalledIterationGuard;
import at.aimon.core.agent.budget.TruncatedResponses;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.NoOpCompactionGuard;
import at.aimon.core.agent.context.ContextCaller;
import at.aimon.core.agent.context.ContextDecision;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.ContextView;
import at.aimon.core.agent.context.DefaultContextEngine;
import at.aimon.core.agent.context.EnvironmentBlocks;
import at.aimon.core.agent.exception.ContextWindowExceededException;
import at.aimon.core.agent.exception.MaxIterationsExceededException;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.CancelledExecutionException;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.SignalBackedLlmCancellation;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.prompt.Staticness;
import at.aimon.core.agent.prompt.SystemPromptPart;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.prompt.SystemReminderFormatter;
import at.aimon.core.agent.quota.QuotaGuard;
import at.aimon.core.agent.quota.QuotaViolation;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.DispatchOptions;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ParallelToolDispatcher;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolContextEnrichmentInfo;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.search.ToolSearchCatalog;
import at.aimon.core.agent.tool.search.ToolSearchRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironments;
import at.aimon.core.environment.ForkDefinition;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.exception.ExecutionBlockedByHookException;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.cost.CostEstimator;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionPolicy;
import at.aimon.core.subagent.SubagentPolicyCapability;
import at.aimon.core.subagent.SubagentToolScope;
import at.aimon.core.subagent.internal.DeadlineScheduler;
import at.aimon.core.toolinvocation.SingleToolInvoker;
import at.aimon.core.toolinvocation.ToolCallTracing;
import at.aimon.core.toolinvocation.ToolInvocationSpec;
import at.aimon.core.toolinvocation.TracingCompactionObserver;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.core.tools.todo.TodoWriteTool;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Spans;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * Default implementation of {@link SubagentExecutor}.
 *
 * <p>
 * Executes subagents by:
 *
 * <ul>
 * <li>Creating conversation context and a per-execution {@link InterruptCoordinator}
 * <li>Executing through a ReAct loop routed via an {@link LlmCallGateway} (retry/fallback aware)
 * <li>Honouring cooperative cancellation (parent signal cascade + per-execution signal injection into the
 * {@link ToolContext})
 * <li>Applying optional AUTO compaction and execution-budget bounds
 * <li>Transforming results to {@link SubagentExecutionResult}
 * </ul>
 *
 * <p>
 * This executor mirrors the {@code OrcaAgentExecutor} ReAct loop for parity: tool execution carries the same
 * {@link ToolContext} keys (LLM call metadata, artifact collector, cancellation signal, per-tool
 * {@code toolUseId}), fires PermissionRequest/PreTool/PostTool hooks, and isolates PostTool hook failures so a hook
 * exception never discards a real tool result. A response the provider cut off at {@code max_tokens} gets the answers
 * that loop gives ({@link TruncatedResponses}): a final answer ends as {@link CompletionReason#TRUNCATED} with the
 * shared marker, and tool calls are refused rather than run. And it stops on the same stalled-iteration guard
 * ({@link StalledIterationGuard}): consecutive iterations whose tool calls all failed — refused or failing — end
 * the fork as {@link CompletionReason#ERROR}.
 *
 * <p>
 * Thread-safe if the supplied {@link LlmCallGateway}, {@link ToolExecutionManager}, {@link HookExecutionManager} and
 * {@link ContextEngine} are thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SubagentExecutor executor = new DefaultSubagentExecutor(llmClient, toolExecutionManager, hookExecutionManager);
 *
 *     SubagentExecutionContext context = SubagentExecutionContext.builder().subagent(codeReviewer)
 *             .agentRuntimeId(agentRuntimeId).defaultModel(model).toolRegistry(toolRegistry)
 *             .hookRegistry(hookRegistry).build();
 *
 *     SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-001")
 *             .goal("Review the authentication module").build();
 *
 *     SubagentExecutionResult result = executor.execute(context, request);
 * }
 * </pre>
 */
public class DefaultSubagentExecutor implements SubagentExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubagentExecutor.class);

    /** The message every interrupted fork reports, whenever the interrupt landed. */
    private static final String INTERRUPTED_MESSAGE = "Execution interrupted";

    /** The message of a fork whose deadline passed while it ran. */
    private static final String DEADLINE_MESSAGE = "Execution stopped: " + CompletionReason.WALL_CLOCK_EXCEEDED.name()
            + " — the fork's deadline passed";

    /** The message of a fork whose deadline passed before it started. */
    private static final String DEADLINE_BEFORE_START_MESSAGE = "Execution stopped: "
            + CompletionReason.WALL_CLOCK_EXCEEDED.name() + " — the fork's deadline passed before it started";

    /** {@code kind} label for the single system-prompt part backing the parts-aware gateway call. */
    private static final String SYSTEM_PROMPT_KIND = "subagent-instructions";

    /** Per-tool-result snippet cap (characters) in the streamed progress log; keeps the log readable. */
    private static final int STREAM_TOOL_RESULT_CAP = 4_000;

    /** The {@code <system-reminder>} key of the instruction that opens a grace round. */
    static final String GRACE_ROUND_REMINDER_KEY = "final-round";

    /**
     * What a fork is told before its grace round. Scoped to this one response: a background fork resumed later gets
     * a fresh iteration budget and its tools back, and this message is still in its history.
     */
    static final String GRACE_ROUND_INSTRUCTION = "You have reached this task's iteration limit. This is your final "
            + "response: no tool can be called in it, and any tool call you make will not be run. Write up your "
            + "results so far in the format your instructions require, and say what you did not get to.";

    private final LlmCallGateway<TranscriptBuffer> gateway;
    private final ToolExecutionManager toolExecutionManager;
    private final HookExecutionManager hookExecutionManager;
    private final ContextEngine contextEngine;

    /**
     * Shared per-tool invocation pipeline; identical logic drives the main-agent {@code OrcaAgentExecutor}.
     *
     * <p>
     * {@code volatile} and non-final because {@link #withApprovalGate(SideEffectApprovalGate)} replaces it after
     * construction, following the same post-construction injection pattern as {@link #parallelToolDispatcher}.
     */
    private volatile SingleToolInvoker singleToolInvoker;

    /**
     * PAR-04: dispatcher that may parallelise a batch of
     * {@link at.aimon.core.agent.tool.ConcurrencyBehavior#CONCURRENT_SAFE
     * CONCURRENT_SAFE} subagent tools. Defaults to sequential (parallel disabled); inject a parallel-enabled dispatcher
     * via {@link #withParallelToolDispatcher(ParallelToolDispatcher)}. The dispatcher is an executor field rather than
     * part of the immutable per-execution {@link LoopContext}, mirroring the main-agent executor.
     *
     * <p>
     * {@code volatile}: {@link #withParallelToolDispatcher} writes it after construction while {@code execute} (which
     * may run on background-spawned threads) reads it, so the write needs a happens-before guarantee for readers.
     */
    private volatile ParallelToolDispatcher parallelToolDispatcher = DefaultParallelToolDispatcher.sequential();

    /**
     * Cost estimator that prices each execution's LLM calls (workflow design §4.4). Defaults to
     * {@link CostEstimator#NOOP} (every call USD 0.00), so cost is inert until pricing is wired via
     * {@link #withCostEstimator(CostEstimator)}. {@code volatile} for the same reason as
     * {@link #parallelToolDispatcher}: set after construction, read on (possibly background) execution threads.
     */
    private volatile CostEstimator costEstimator = CostEstimator.NOOP;

    /**
     * TRACE-01: tracer that records this fork's ITERATION and TOOL spans under the span its caller's metadata points
     * at. Defaults to {@link Tracer#noop()}, so tracing is inert until wired via {@link #withTracer(Tracer)}.
     * {@code volatile} for the same reason as {@link #parallelToolDispatcher}.
     */
    private volatile Tracer tracer = Tracer.noop();

    /**
     * TRACE-02: how much tool-result content this fork's TOOL spans capture. Defaults to
     * {@link TracePayloadPolicy#summaryOnly()}. {@code volatile} for the same reason as
     * {@link #parallelToolDispatcher}.
     */
    private volatile TracePayloadPolicy tracePayloadPolicy = TracePayloadPolicy.summaryOnly();

    /**
     * Creates a new DefaultSubagentExecutor backed by a raw {@link LlmClient}.
     *
     * <p>
     * The client is auto-wrapped in a pass-through {@link LlmCallGateway} (default retry policy, no fallback, no
     * prompt-too-long handler) and compaction is disabled ({@link ContextEngine#passthrough()}). This preserves the
     * legacy
     * single-client construction path used by callers and tests.
     *
     * @param llmClient
     *            The LLM client (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultSubagentExecutor(LlmClient llmClient, ToolExecutionManager toolExecutionManager,
            HookExecutionManager hookExecutionManager) {
        this(LlmCallGateway.<TranscriptBuffer>withDefaultRetry(llmClient), toolExecutionManager, hookExecutionManager,
                ContextEngine.passthrough());
    }

    /**
     * Creates a new DefaultSubagentExecutor backed by a pre-configured {@link LlmCallGateway}.
     *
     * <p>
     * Compaction is disabled ({@link ContextEngine#passthrough()}).
     *
     * @param gateway
     *            The LLM call gateway wrapping the underlying client with retry/fallback semantics (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultSubagentExecutor(LlmCallGateway<TranscriptBuffer> gateway, ToolExecutionManager toolExecutionManager,
            HookExecutionManager hookExecutionManager) {
        this(gateway, toolExecutionManager, hookExecutionManager, ContextEngine.passthrough());
    }

    /**
     * Creates a DefaultSubagentExecutor whose AUTO compaction is decided by a bare {@link CompactionGuard}, wrapped in
     * a {@link DefaultContextEngine}.
     *
     * @param gateway
     *            The LLM call gateway wrapping the underlying client with retry/fallback semantics (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager (must not be null)
     * @param compactionGuard
     *            The AUTO compaction guard; use {@link NoOpCompactionGuard#instance()} to disable (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    @SuppressWarnings("deprecation") // the version-1 compaction SPI is carried through on purpose
    public DefaultSubagentExecutor(LlmCallGateway<TranscriptBuffer> gateway, ToolExecutionManager toolExecutionManager,
            HookExecutionManager hookExecutionManager, CompactionGuard compactionGuard) {
        this(gateway, toolExecutionManager, hookExecutionManager,
                DefaultContextEngine.builder()
                        .compactionGuard(Objects.requireNonNull(compactionGuard, "Compaction guard cannot be null"))
                        .writeFormat(SessionLogFormat.V1).build());
    }

    /**
     * Primary constructor.
     *
     * <p>
     * A fork has no session, so whatever view state the engine keeps lives on the fork's own buffer and is never
     * persisted.
     *
     * @param gateway
     *            The LLM call gateway wrapping the underlying client with retry/fallback semantics (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager (must not be null)
     * @param contextEngine
     *            The engine deciding what each of the fork's LLM calls is sent; use {@link ContextEngine#passthrough()}
     *            to disable compaction (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultSubagentExecutor(LlmCallGateway<TranscriptBuffer> gateway, ToolExecutionManager toolExecutionManager,
            HookExecutionManager hookExecutionManager, ContextEngine contextEngine) {
        this.gateway = Objects.requireNonNull(gateway, "gateway cannot be null");
        this.toolExecutionManager = Objects.requireNonNull(toolExecutionManager,
                "Tool execution manager cannot be null");
        this.hookExecutionManager = Objects.requireNonNull(hookExecutionManager,
                "Hook execution manager cannot be null");
        this.contextEngine = Objects.requireNonNull(contextEngine, "Context engine cannot be null");
        this.singleToolInvoker = new SingleToolInvoker(toolExecutionManager, hookExecutionManager);
    }

    /**
     * PAR-04/PAR-06: injects the dispatcher used to (optionally) parallelise batches of {@code CONCURRENT_SAFE} tools.
     * Returns {@code this} for fluent configuration. When unset, the executor keeps its default sequential dispatcher
     * and behaviour is unchanged.
     *
     * @param dispatcher
     *            the dispatcher (must not be null)
     * @return this executor
     */
    public DefaultSubagentExecutor withParallelToolDispatcher(ParallelToolDispatcher dispatcher) {
        this.parallelToolDispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        return this;
    }

    /**
     * Injects the gate that requires user approval before a tool declaring a side effect runs inside a fork.
     *
     * <p>
     * Pass the <em>same</em> gate instance the main executor uses. A fork has no {@code SessionId} of its own and
     * resolves approvals through the {@code invokingSessionId} it carries, so sharing the instance is what lets it
     * find what the user already answered in the session that launched it; a separate gate would re-ask, and a fork
     * has no channel to be asked through.
     *
     * @param approvalGate
     *            the gate (must not be null)
     * @return this executor
     * @throws NullPointerException
     *             if {@code approvalGate} is null
     */
    public DefaultSubagentExecutor withApprovalGate(SideEffectApprovalGate approvalGate) {
        Objects.requireNonNull(approvalGate, "approvalGate cannot be null");
        this.singleToolInvoker = new SingleToolInvoker(toolExecutionManager, hookExecutionManager, approvalGate);
        return this;
    }

    /**
     * Injects a cost estimator so each execution's estimated USD cost is surfaced on its result
     * ({@link SubagentExecutionResult#getCost()}). Default is {@link CostEstimator#NOOP} (zero cost).
     *
     * @param estimator
     *            the cost estimator (must not be null)
     * @return this executor
     */
    public DefaultSubagentExecutor withCostEstimator(CostEstimator estimator) {
        this.costEstimator = Objects.requireNonNull(estimator, "estimator cannot be null");
        return this;
    }

    /**
     * TRACE-01: injects the tracer that records this fork's ITERATION and TOOL spans. Pass the same instance the main
     * executor and its {@code TracingLlmClient} use, or the spans land in a different store from their parents. When
     * unset, the executor keeps {@link Tracer#noop()} and behaviour is unchanged.
     *
     * @param tracer
     *            the tracer (must not be null)
     * @return this executor
     */
    public DefaultSubagentExecutor withTracer(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer cannot be null");
        return this;
    }

    /**
     * TRACE-02: injects the policy deciding whether this fork's TOOL spans capture result content. When unset, the
     * executor keeps {@link TracePayloadPolicy#summaryOnly()}.
     *
     * @param policy
     *            the payload capture policy (must not be null)
     * @return this executor
     */
    public DefaultSubagentExecutor withTracePayloadPolicy(TracePayloadPolicy policy) {
        this.tracePayloadPolicy = Objects.requireNonNull(policy, "policy cannot be null");
        return this;
    }

    /**
     * Enforces the iteration and token caps through the fork's budget, the tool-call cap at dispatch, the deadline
     * through a timer that trips the fork's cancellation, and the context settings on every context request — the last
     * only when this executor's own engine compacts; with {@link ContextEngine#passthrough()} (how
     * {@code OrcaAgentExecutorFactory} builds it) they are left out, because they would do nothing.
     *
     * @return the enforced capabilities
     */
    @Override
    public Set<SubagentPolicyCapability> supportedPolicyCapabilities() {
        if (contextEngine != ContextEngine.passthrough()) {
            return EnumSet.allOf(SubagentPolicyCapability.class);
        }
        // Its own engine never compacts, so a compaction hint or instruction would do nothing — unless the fork runs
        // under an inherited engine, which the manager adds back for a policy that inherits one.
        final EnumSet<SubagentPolicyCapability> supported = EnumSet.allOf(SubagentPolicyCapability.class);
        supported.removeAll(SubagentPolicyCapability.contextAxes());
        return supported;
    }

    @Override
    public SubagentExecutionResult execute(SubagentExecutionContext context, SubagentExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final Subagent subagent = context.getSubagent();

        // A deadline anchored at launch can pass before the fork gets here (a background fork waiting for a worker).
        // Such a fork does not start at all: no environment, no OnStart hook, no LLM call.
        final ExecutionDeadline deadline = request.getDeadline().orElse(null);
        if (deadline != null && deadline.isExpired()) {
            log.info("Subagent '{}' not started: its deadline ({}) passed before it could run", subagent.getName(),
                    deadline.getBudget());
            return SubagentExecutionResult.failure(DEADLINE_BEFORE_START_MESSAGE,
                    SessionSnapshot.of(SessionId.generate()),
                    ExecutionMetadata.builder().iterationCount(0).tokenUsage(TokenUsage.empty())
                            .timestamps(Instant.now(), Instant.now()).build(),
                    CompletionReason.WALL_CLOCK_EXCEEDED, null);
        }

        // The fork's run identity. Deliberately not a SessionId: one of those means a durable record plus a
        // cluster-unique lease, and a fork is entitled to neither — it used to mint one anyway, which is the defect
        // this removes. A resumed run keeps the identity of the run it continues (the snapshot's label) so per-run
        // tool state — the todo list, for one — survives the suspend/resume boundary exactly as it did while that
        // label was a minted session id.
        final TranscriptBuffer resumedBuffer = request.getPreviousSnapshot().map(TranscriptBuffer::fromSnapshot)
                .orElse(null);
        final ExecutionId executionId = resumedBuffer != null
                ? ExecutionId.of(resumedBuffer.getSessionId().value())
                : ExecutionId.generate("subagent:" + subagent.getName());

        // Resolve the execution environment once, before the prompt is built and before any tool runs (design §5.1).
        // The parent environment rides along so the local provider can answer with it unchanged (§5.2); a fork that
        // was handed no provider gets an unavailable environment naming the wiring gap, never the host.
        final ExecutionEnvironment executionEnvironment = resolveExecutionEnvironment(context, request, executionId);

        // EE-81: a goal the spawner rendered (a fork-mode skill's body) names paths staged where the spawner runs. When
        // the fork was placed elsewhere it is rendered again here, in the fork's environment. A fork answered with its
        // parent (the local provider) keeps the spawner's text: same environment, same copy.
        final String goal;
        try {
            goal = forkGoal(context, request, executionEnvironment);
        } catch (RuntimeException e) {
            // Before onStart, so no onStop fires; the manager still fires subagentStop with this environment.
            log.warn("Fork '{}' could not render its goal in its own environment: {}", subagent.getName(),
                    e.getMessage());
            return SubagentExecutionResult
                    .emptyFailure("Failed to render the fork's goal in its own environment: " + e.getMessage(),
                            Instant.now())
                    .withExecutionId(executionId).withExecutionEnvironment(executionEnvironment);
        }

        // Build dynamic system prompt with environment information
        final String systemPrompt = buildDynamicSystemPrompt(subagent.getContent().getSystemPrompt(),
                executionEnvironment.descriptor());

        // Create conversation context.
        final TranscriptBuffer transcriptBuffer;
        if (resumedBuffer != null) {
            transcriptBuffer = resumedBuffer;
            transcriptBuffer.setSystemPrompt(systemPrompt);
        } else {
            transcriptBuffer = new TranscriptBuffer(forkTranscriptLabel(executionId), systemPrompt);
        }

        // CTX-05 parity: wrap the dynamic prompt as a single STATIC part so the gateway's parts-aware overload carries
        // LlmCallMetadata. concatenated() of a single part equals the prompt string stored in TranscriptBuffer.
        final SystemPromptParts systemPromptParts = SystemPromptParts.of(List.of(SystemPromptPart.builder()
                .content(systemPrompt).staticness(Staticness.STATIC).kind(SYSTEM_PROMPT_KIND).build()));

        // Resolve effective LLM call metadata (subagent attribution) — shared with the code-behavior path so the two
        // stay in lockstep.
        final LlmCallMetadata effectiveMetadata = SubagentLlmDefaults.effectiveMetadata(subagent.getName(),
                request.getLlmCallMetadata(), request.getPrincipal().orElse(null));

        final LlmModel modelConfig = buildModelConfig(subagent, context.getDefaultModel(),
                context.getModelOverride().orElse(null));
        final BudgetTracker budgetTracker = new BudgetTracker(
                request.getBudget().orElseGet(ExecutionBudget::unlimited));

        final SubagentExecutionPolicy policy = request.getExecutionPolicy().orElse(null);
        // The spawner's engine when the policy asks for it, else this executor's own. Shared, not copied: the engine
        // keys what it remembers about a conversation (its lock, its failure count) on this fork's own transcript.
        final ContextEngine engine = context.getContextEngine().orElse(contextEngine);

        // One coordinator per execute() call. AutoCloseable drops pending registrars on any exit path.
        try (InterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            // Cascade parent cancellation: if the parent's signal is already (or becomes) tripped, trip ours too. The
            // listener fires synchronously when the parent signal is already cancelled, covering the pre-cancelled
            // case. The registration is removed in the finally below so this per-subagent listener (which captures the
            // coordinator) does not accumulate on the longer-lived parent signal across many subagent invocations
            // within one parent execution. The parent is a turn only on the session path — a background fork
            // (DefaultSubagentExecutionManager) and a workflow fan-out (DefaultWorkflowRunner) each mint their own
            // coordinator, and those signals can outlive any single turn.
            final CancellationSignal.Registration parentCancelReg = context.getParentCancellationSignal()
                    .onCancel(() -> coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED));
            // The deadline trips this fork's own signal with BUDGET_EXCEEDED — the first reason a signal records is the
            // one it keeps, so a deadline that lands first is not later read as a parent's cancellation. Tripping the
            // signal aborts an in-flight LLM call and fires the terminators of a running tool, so the fork stops
            // mid-call instead of at the next iteration. Closed in the finally below, after which it can no longer
            // fire.
            final DeadlineScheduler.Handle deadlineTimer = deadline == null
                    ? null
                    : DeadlineScheduler.schedule(deadline.remaining(),
                            () -> coordinator.requestInterrupt(InterruptReason.BUDGET_EXCEEDED));
            try {
                final ToolRegistry sessionRegistry = createSessionRegistry(context.getToolRegistry());
                // Per-execution artifact collector so artifact-producing subagent tools have the context key (parity
                // with the main-agent executor). Surfacing collected artifacts on SubagentExecutionResult is a
                // follow-up.
                // Archived artifacts of this fork land under its own execution id (execution-environment §9.3).
                final ArtifactCollector artifactCollector = new ArtifactCollector(executionId.value());
                final ToolContext toolContext = createToolContext(context, request, sessionRegistry,
                        coordinator.getSignal(), effectiveMetadata, executionId, artifactCollector,
                        executionEnvironment, engine);

                final LoopContext lc = LoopContext.builder().context(context).transcriptBuffer(transcriptBuffer)
                        .executionId(executionId).systemPromptParts(systemPromptParts)
                        .effectiveMetadata(effectiveMetadata).modelConfig(modelConfig).budgetTracker(budgetTracker)
                        .stalledIterationGuard(new StalledIterationGuard()).quotaGuard(request.getQuotaGuard())
                        .startTime(Instant.now()).toolContext(toolContext).sessionRegistry(sessionRegistry)
                        .coordinator(coordinator).goal(goal).executionAttributes(request.getExecutionAttributes())
                        .forkDepth(request.getForkDepth()).contextEngine(engine).policy(policy).deadline(deadline)
                        .toolCalls(new ToolCallQuota(policy == null ? null : policy.getMaxToolCalls().orElse(null)))
                        // Snapshot the volatile tracing fields once, so one execution traces consistently.
                        .tracer(tracer).tracePayloadPolicy(tracePayloadPolicy).build();

                // Every exit of the loop passes here, so one stamp names the fork, and the environment it ran in, on
                // every result it can return.
                return runReActLoop(lc).withExecutionId(executionId).withExecutionEnvironment(executionEnvironment)
                        .withToolCallCount(lc.toolCalls.used());
            } finally {
                if (deadlineTimer != null) {
                    deadlineTimer.close();
                    // A terminator the deadline fired may have interrupted this thread after the loop's last
                    // checkpoint. The interrupt was this fork's; leaving it would break the next blocking call of
                    // whatever runs on this thread next. A parent's cancellation is left alone — it is not ours.
                    if (deadlineTimer.hasFired() && !context.getParentCancellationSignal().isCancelled()) {
                        Thread.interrupted();
                    }
                }
                parentCancelReg.remove();
            }
        }
    }

    /**
     * Runs the subagent ReAct loop: fires OnStart hooks, then iterates LLM call → tool execution until a final answer,
     * an exhausted budget/iteration bound, a cancellation, a stall ({@link StalledIterationGuard}), or an error. An
     * OnStart hook that blocks ends the fork before the first iteration ({@link #createBlockedResult}), and so does a
     * cancellation that lands while those hooks run ({@link #createInterruptedBeforeStartResult}).
     *
     * @param lc
     *            the immutable per-execution loop context (must not be null)
     * @return the execution result (never null)
     */
    private SubagentExecutionResult runReActLoop(LoopContext lc) {
        final CancellationSignal cancellationSignal = lc.coordinator.getSignal();
        // Cancellation design §7: bridge the execution-scoped signal to an LlmCancellation so a parent-cascaded
        // or tool-cooperative trip actively aborts the in-flight LLM HTTP call, promoting cooperative cancellation
        // to an in-flight abort rather than waiting for the next iteration boundary. Execution-scoped: this
        // registers exactly one signal listener for the whole loop. Each call swaps its per-call abort lever in
        // and clears it in a finally (see below).
        final SignalBackedLlmCancellation llmCancellation = new SignalBackedLlmCancellation(cancellationSignal);
        TokenUsage accumulatedTokens = TokenUsage.empty();
        int iterationCount = 0;
        // The input size of the last LLM call, for the policy's context target (0 before the first call).
        int lastPromptTokens = 0;

        try {
            // Goal, then OnStart hooks: a block, or a cancellation during them, ends the fork before its first LLM
            // call (see startFork).
            final Optional<SubagentExecutionResult> refused = startFork(lc);
            if (refused.isPresent()) {
                return refused.get();
            }

            while (iterationCount < lc.maxIterations()) {
                final BudgetDecision budgetDecision = lc.budgetTracker.check();
                final Optional<SubagentExecutionResult> stopped = stopBeforeIteration(lc, budgetDecision,
                        iterationCount, accumulatedTokens, llmCancellation);
                if (stopped.isPresent()) {
                    return stopped.get();
                }

                iterationCount++;
                lc.budgetTracker.recordIteration();
                stream(lc, "\n[iteration " + iterationCount + "]\n");

                // AUTO compaction gate, returning the view this iteration's call is sent; run before the ITERATION span
                // opens, as the main turn's is. Either soft hint (SHOULD_COMPACT, or the last input past the context
                // target) asks the engine to compact at its lower band; the engine still decides.
                final ContextView view = applyCompactionGate(lc, iterationCount,
                        budgetDecision == BudgetDecision.SHOULD_COMPACT || lc.contextTargetReached(lastPromptTokens));
                // TRACE-01: this iteration's LLM call and tool calls nest under its ITERATION span.
                final IterationTrace trace = IterationTrace.open(lc, iterationCount);
                try {
                    // Re-read every iteration so newly activated deferred tools are included. See the method for the
                    // two things it withholds and why.
                    final List<ToolDefinition> availableTools = availableToolDefinitions(lc, iterationCount);

                    // Send message to LLM via the gateway (retry/fallback aware, metadata-carrying parts overload). The
                    // LlmCancellation lets a trip abort the in-flight call; clear the per-call abort lever in a finally
                    // (hygiene) so a trip landing between calls (e.g. while tools run) never fires a completed call's
                    // stale abort.
                    final LlmResponse response;
                    try {
                        response = gateway.sendMessage(lc.systemPromptParts, view.getMessages(), availableTools,
                                lc.modelConfig, trace.metadata, llmCancellation);
                    } finally {
                        llmCancellation.clearAbort();
                    }

                    accumulatedTokens = accumulatedTokens.add(response.getTokenUsage());
                    lc.budgetTracker.recordTokens(response.getTokenUsage());
                    lastPromptTokens = response.getTokenUsage().getPromptTokens();

                    recordCallCost(lc, response);

                    // A response cut off at max_tokens is read once, here, and both shapes below branch on it: the
                    // same two answers OrcaAgentExecutor gives a turn (TruncatedResponses).
                    final boolean truncated = TruncatedResponses.isTruncated(response);

                    // If no tool uses, we have the final answer
                    if (!response.hasToolUses()) {
                        return finishWithAnswer(lc, response, truncated, iterationCount, accumulatedTokens);
                    }

                    // Add assistant response with tool uses to context
                    lc.transcriptBuffer.addMessage(Message.assistant(response.getTextContent(), response.getToolUses())
                            .withReasoningTraces(response.getReasoningTraces()));

                    // Stream the assistant's reasoning preamble (the text accompanying tool calls) for progress
                    // visibility.
                    final String preamble = response.getTextContent();
                    if (preamble != null && !preamble.isBlank()) {
                        stream(lc, preamble + "\n");
                    }

                    // Execute tools with PermissionRequest/PreTool/PostTool hooks per tool — unless the response was
                    // cut off at max_tokens, in which case none of them runs and each is answered with the refusal.
                    final List<ToolUseResult> toolUseResults = truncated
                            ? refuseTruncatedToolUses(lc, response, iterationCount)
                            : executeAdmittedToolUses(lc, trace.toolContext, response.getToolUses(), iterationCount);
                    lc.transcriptBuffer.addMessage(Message.toolUseResults(toolUseResults));

                    // Iteration-tail cancellation check (a tool or the parent may have tripped the signal), then
                    // the stall guard — in that order, so an iteration a cancellation cut short is never counted as
                    // a stall.
                    if (isCancelledOrInterrupted(cancellationSignal)) {
                        trace.interrupted();
                        return createInterruptedResult(lc, iterationCount, accumulatedTokens);
                    } else if (lc.toolCalls.isExceeded()) {
                        // Calls over a cap were answered, not run; stop before the next LLM call is spent.
                        return createToolCallBudgetResult(lc, iterationCount, accumulatedTokens);
                    } else if (lc.stalledIterationGuard.recordToolIteration(toolUseResults, truncated)) {
                        return createStalledResult(lc, iterationCount, accumulatedTokens);
                    }
                } catch (RuntimeException | Error e) {
                    trace.fail(e, cancellationSignal);
                    throw e;
                } finally {
                    trace.close();
                }
            }

            // Max iterations exceeded; with a grace round the fork gets one more call to write up what it has.
            return endAtOwnIterationLimit(lc, iterationCount, accumulatedTokens, llmCancellation);

        } catch (CancelledExecutionException e) {
            log.debug("Subagent ReAct loop cancelled: {}", e.getMessage());
            return createInterruptedResult(lc, iterationCount, accumulatedTokens);
        } catch (MaxIterationsExceededException e) {
            return createFailureResult(lc, e.getMessage(), iterationCount, accumulatedTokens,
                    CompletionReason.MAX_ITERATIONS);
        } catch (ContextWindowExceededException e) {
            // A deadline that broke the preCompact guard before its timer tripped the signal is the deadline's.
            return lc.deadlineExpired()
                    ? createDeadlineResult(lc, iterationCount, accumulatedTokens)
                    : createFailureResult(lc, "Context window exceeded: " + e.getMessage(), iterationCount,
                            accumulatedTokens, CompletionReason.ERROR);
        } catch (LlmCallCancelledException e) {
            // The gateway/provider actively aborted the in-flight LLM call because the execution's signal tripped.
            // This extends LlmClientException, so it MUST be caught BEFORE the generic handler below.
            log.debug("Subagent LLM call cancelled in-flight: {}", e.getMessage());
            return createInterruptedResult(lc, iterationCount, accumulatedTokens);
        } catch (LlmClientException e) {
            // A client that answers an abort with its own exception type: the fork was being stopped, so say that.
            if (isCancelledOrInterrupted(cancellationSignal) || lc.deadlineExpired()) {
                log.debug("Subagent LLM call failed after cancellation: {}", e.getMessage());
                return createInterruptedResult(lc, iterationCount, accumulatedTokens);
            }
            return createFailureResult(lc, "LLM client error: " + e.getMessage(), iterationCount, accumulatedTokens,
                    CompletionReason.ERROR);
        } catch (Exception e) {
            // A blocking call broken by a cancellation-driven thread interrupt surfaces here — map it back to a clean
            // interrupted result rather than a generic failure (or to the deadline's, when that is what broke it).
            if (isCancelledOrInterrupted(cancellationSignal) || lc.deadlineExpired()) {
                log.debug("Subagent execution unwound by cancellation: {}", e.getMessage());
                return createInterruptedResult(lc, iterationCount, accumulatedTokens);
            }
            log.error("Unexpected subagent execution error", e);
            return createFailureResult(lc, "Subagent execution failed: " + e.getMessage(), iterationCount,
                    accumulatedTokens, CompletionReason.ERROR);
        }
    }

    /**
     * The checks at an iteration boundary, before anything of the next iteration is spent, in the order they win:
     * a cancellation that has landed (the helper also clears the thread's interrupt flag, so a pooled background
     * thread does not carry it into its next task), the deadline (read here as well as by its timer, so a fork never
     * starts an iteration past it even if the timer has not run yet), the budget, and the spawning turn's quota — what
     * the turn, its other forks and the user's other sessions spent counts here too, so a fork is no way around a
     * quota.
     *
     * <p>
     * A budget that stops the fork for its iterations ends it through {@link #endAtIterationLimit} when the policy
     * asks for a grace round, so the policy's {@code maxIterations} gets one the way the subagent's own
     * {@code max-iterations} does.
     *
     * @param budgetDecision
     *            the budget tracker's decision for this boundary
     * @param llmCancellation
     *            the loop's LLM abort lever, for a grace round's call
     * @return the result that ends the fork here, or empty to run the iteration
     */
    private Optional<SubagentExecutionResult> stopBeforeIteration(LoopContext lc, BudgetDecision budgetDecision,
            int iterationCount, TokenUsage accumulatedTokens, SignalBackedLlmCancellation llmCancellation) {
        if (isCancelledOrInterrupted(lc.coordinator.getSignal())) {
            return Optional.of(createInterruptedResult(lc, iterationCount, accumulatedTokens));
        }
        if (lc.deadline != null && lc.deadline.isExpired()) {
            return Optional.of(createDeadlineResult(lc, iterationCount, accumulatedTokens));
        }
        if (budgetDecision == BudgetDecision.STOP) {
            if (lc.graceRoundEnabled() && lc.budgetTracker.getStopReason()
                    .filter(reason -> reason == CompletionReason.MAX_ITERATIONS).isPresent()) {
                return Optional.of(endAtIterationLimit(lc, iterationCount, accumulatedTokens,
                        "Execution stopped: " + CompletionReason.MAX_ITERATIONS.name(), llmCancellation));
            }
            return Optional.of(createBudgetStopResult(lc, iterationCount, accumulatedTokens));
        }
        final Optional<QuotaViolation> quotaViolation = lc.quotaGuard.check();
        return quotaViolation.map(violation -> createQuotaStopResult(lc, iterationCount, accumulatedTokens, violation));
    }

    /**
     * Ends a fork whose loop ran its subagent's own {@code max-iterations}: through {@link #endAtIterationLimit} under
     * a policy with a grace round, otherwise by throwing, as the loop always has.
     *
     * @throws MaxIterationsExceededException
     *             when the fork has no grace round
     */
    private SubagentExecutionResult endAtOwnIterationLimit(LoopContext lc, int iterationCount,
            TokenUsage accumulatedTokens, SignalBackedLlmCancellation llmCancellation) {
        final MaxIterationsExceededException limit = new MaxIterationsExceededException(lc.maxIterations());
        if (!lc.graceRoundEnabled()) {
            throw limit;
        }
        return endAtIterationLimit(lc, iterationCount, accumulatedTokens, limit.getMessage(), llmCancellation);
    }

    /**
     * Ends a fork that reached an iteration limit — its own {@code max-iterations} or its policy's — under a policy
     * that asks for a grace round: one more LLM call, opened by {@link #GRACE_ROUND_INSTRUCTION}, whose text becomes
     * the answer of a {@link CompletionReason#MAX_ITERATIONS_GRACE} result.
     *
     * <p>
     * <b>No tool runs in it.</b> The call is sent the same tool definitions as the iteration before it rather than
     * none: a provider may reject a request whose history holds tool calls but which defines no tools, and an
     * unchanged tool list keeps the prompt-cache prefix the earlier calls built. What the model is offered therefore
     * does not stop it asking for a tool; what this method does with the answer does — a tool call in it is dropped,
     * never dispatched, and only the text is kept.
     *
     * <p>
     * The grace round is skipped, and the fork ends {@link CompletionReason#MAX_ITERATIONS} as it would without one,
     * when the next call would be stopped anyway ({@link #graceRoundAllowed}). It also ends that way when the call
     * answers with no text, or fails with an error that is not a cancellation. A cancellation or a deadline during
     * the call is reported as for any other call. Every result but a grace answer hands back the transcript from before
     * the instruction ({@code LoopContext#beforeGrace}), so a resumed fork does not find an unanswered final-round
     * reminder.
     *
     * <p>
     * The call counts as an iteration in the result and is charged to the budget and the quota like any other, so a
     * grace answer can overshoot a token budget by this one call, as any call can.
     *
     * @param iterationCount
     *            the iterations run before the limit was reached
     * @param limitMessage
     *            the message of the {@code MAX_ITERATIONS} result the fork ends with when there is no grace answer
     * @return the result (never null)
     */
    private SubagentExecutionResult endAtIterationLimit(LoopContext lc, int iterationCount,
            TokenUsage accumulatedTokens, String limitMessage, SignalBackedLlmCancellation llmCancellation) {
        if (!graceRoundAllowed(lc)) {
            return createFailureResult(lc, limitMessage, iterationCount, accumulatedTokens,
                    CompletionReason.MAX_ITERATIONS);
        }
        final int graceIteration = iterationCount + 1;
        log.info("Subagent '{}' reached its iteration limit after {} iterations; running its grace round",
                lc.subagent().getName(), iterationCount);
        stream(lc, "\n[iteration " + graceIteration + ": final round, tool calls are not run]\n");
        // From here every result but a grace answer hands back the transcript as it was before the reminder
        // (createFailureResult reads it) — a cancellation, the deadline or an error included. Taken before the
        // compaction gate below, so such a result also leaves out a compaction the grace round itself triggered.
        lc.beforeGrace = lc.transcriptBuffer.toSnapshot();
        lc.transcriptBuffer.addMessage(
                Message.user(SystemReminderFormatter.wrap(GRACE_ROUND_REMINDER_KEY, GRACE_ROUND_INSTRUCTION)),
                LogOrigin.SYNTHETIC);
        final CancellationSignal cancellationSignal = lc.coordinator.getSignal();
        final ContextView view = applyCompactionGate(lc, graceIteration, false);
        final IterationTrace trace = IterationTrace.open(lc, graceIteration);
        final LlmResponse response;
        try {
            try {
                response = gateway.sendMessage(lc.systemPromptParts, view.getMessages(),
                        availableToolDefinitions(lc, graceIteration), lc.modelConfig, trace.metadata, llmCancellation);
            } finally {
                llmCancellation.clearAbort();
            }
        } catch (LlmClientException e) {
            trace.fail(e, cancellationSignal);
            trace.close();
            if (e instanceof LlmCallCancelledException || isCancelledOrInterrupted(cancellationSignal)
                    || lc.deadlineExpired()) {
                throw e;
            }
            log.warn("Subagent '{}' grace round failed; ending at its iteration limit: {}", lc.subagent().getName(),
                    e.getMessage());
            return createFailureResult(lc, limitMessage, iterationCount, accumulatedTokens,
                    CompletionReason.MAX_ITERATIONS);
        } catch (RuntimeException | Error e) {
            trace.fail(e, cancellationSignal);
            trace.close();
            throw e;
        }
        trace.close();

        final TokenUsage total = accumulatedTokens.add(response.getTokenUsage());
        lc.budgetTracker.recordTokens(response.getTokenUsage());
        recordCallCost(lc, response);
        if (response.hasToolUses()) {
            log.info("Subagent '{}' asked for {} tool call(s) in its grace round; none is run", lc.subagent().getName(),
                    response.getToolUses().size());
        }
        final String text = response.getTextContent();
        if (text == null || text.isBlank()) {
            log.warn("Subagent '{}' grace round produced no answer; ending at its iteration limit",
                    lc.subagent().getName());
            return createFailureResult(lc, limitMessage + " (the final round produced no answer)", graceIteration,
                    total, CompletionReason.MAX_ITERATIONS);
        }
        final String answer = TruncatedResponses.isTruncated(response)
                ? text + TruncatedResponses.TRUNCATION_MARKER
                : text;
        lc.transcriptBuffer.addMessage(Message.assistant(answer).withReasoningTraces(response.getReasoningTraces()));
        lc.beforeGrace = null;
        return createGraceResult(lc, answer, graceIteration, total);
    }

    /**
     * Whether a fork at its iteration limit may have its grace round: its policy asks for one, and nothing would stop
     * the call before it is made — a cancellation, the deadline, a token, cost or wall-clock budget spent, or a usage
     * quota. Each of those ends the fork {@code MAX_ITERATIONS} here, as it did before grace rounds existed: the limit
     * was reached first.
     */
    private static boolean graceRoundAllowed(LoopContext lc) {
        if (isCancelledOrInterrupted(lc.coordinator.getSignal()) || lc.deadlineExpired() || lc.tokenBudgetExhausted()) {
            return false;
        }
        final BudgetTracker tracker = lc.budgetTracker;
        final ExecutionBudget budget = tracker.getBudget();
        if (budget.getMaxTokens().filter(max -> tracker.tokens().getTotalTokens() >= max).isPresent()
                || budget.getMaxCostUsd().filter(max -> tracker.cost().compareTo(max) >= 0).isPresent()
                || budget.getMaxWallClockDuration().filter(max -> tracker.elapsed().compareTo(max) >= 0).isPresent()) {
            return false;
        }
        return lc.quotaGuard.check().isEmpty();
    }

    /**
     * Adds the goal as a user message and fires OnStart hooks; anything they say short of a block is fed back into the
     * conversation.
     *
     * <p>
     * The transcript is captured <b>before</b> the goal goes in, and that is what a blocked fork hands back, so a goal
     * a
     * guard refused is never persisted and replayed by a later resume. It is empty for a fresh fork, which therefore
     * saves nothing and stays unresumable; for a resume it is the restored conversation, unchanged.
     *
     * <p>
     * Once the chain is over — returned or blocked — the fork's cancellation is read, before either outcome is looked
     * at. A fork cancelled while its OnStart hooks ran was stopped, not refused: it ends {@code INTERRUPTED}, as a
     * turn does, whichever kind of hook was running (a shell guard answers its cancelled command with a block, a
     * programmatic hook with nothing) and whichever road the cancellation took. The read is the loop's own checkpoint
     * predicate, so it also takes a thread interrupt the hook executor re-armed off the thread; left there, it would
     * break the wait for every OnStop hook that fires next.
     *
     * @return the result that ends the fork here — blocked by a hook, or interrupted during the hooks — or empty to
     *         run the loop
     */
    private Optional<SubagentExecutionResult> startFork(LoopContext lc) {
        final SessionSnapshot beforeGoal = lc.transcriptBuffer.toSnapshot();
        lc.transcriptBuffer.addUserMessage(lc.goal);
        ExecutionBlockedByHookException blocked = null;
        try {
            checkOnStartHooks(lc);
        } catch (ExecutionBlockedByHookException e) {
            blocked = e;
        }
        if (isCancelledOrInterrupted(lc.coordinator.getSignal())) {
            if (blocked != null) {
                // A cancelled guard blocks; that block is the cancellation's, and a veto that came with it stops
                // nothing the cancellation has not stopped already.
                log.debug("Subagent '{}' cancelled during its OnStart hooks; block not reported: {}",
                        lc.subagent().getName(), blocked.getMessage());
            }
            return Optional.of(createInterruptedBeforeStartResult(lc, beforeGoal));
        }
        return blocked == null
                ? Optional.empty()
                : Optional.of(createBlockedResult(lc, blocked, beforeGoal, 0, TokenUsage.empty()));
    }

    /**
     * Fires OnStart hooks, throws if any hook blocks the fork, and otherwise appends any advisory hook feedback to the
     * conversation as a user message.
     *
     * <p>
     * A block is a hook that exited 2 or, unless it declares {@code failOpen: true}, one whose command could not be run
     * at all. Either way the fork does not start: {@link #startFork} turns the exception into
     * {@link #createBlockedResult}. The same rule for what counts as a block as
     * {@code OrcaAgentExecutor#checkOnStartHooks}, which stops the turn, and the same gate a code-behavior fork passes
     * ({@link SubagentOnStartGate}).
     *
     * <p>
     * A cancellation that lands while the chain runs is not this method's to report: a shell guard answers its
     * cancelled command with a block and this throws like for any other block. {@link #startFork} reads the fork's
     * cancellation after the chain and, when it has tripped, ends the fork {@code INTERRUPTED} before looking at the
     * block — the same order the main turn takes.
     *
     * <p>
     * The note is wrapped in a {@code <system-reminder>} block so the model does not read it as genuine user intent,
     * matching how {@code OrcaAgentExecutor} surfaces the same chain.
     *
     * @param lc
     *            the loop context
     * @throws ExecutionBlockedByHookException
     *             if any OnStart hook blocks the fork
     */
    private void checkOnStartHooks(LoopContext lc) {
        final List<HookResult> onStartResults = SubagentOnStartGate.check(hookExecutionManager,
                SubagentOnStartGate.context(lc.context, lc.goal, lc.executionAttributes, lc.executionEnvironment(),
                        lc.coordinator.getSignal()));
        HookFeedback.toReminderBlock(HookFeedback.collectAdvisory(onStartResults))
                .ifPresent(block -> lc.transcriptBuffer.addMessage(Message.user(block), LogOrigin.SYNTHETIC));
    }

    /**
     * Asks the context engine for the upcoming LLM call's view and applies its decision (BLOCK throws, COMPACT and WARN
     * log, NONE is a no-op).
     *
     * <p>
     * The request carries the fork's metadata without its call site — principal, group key and the tags inherited
     * from the caller — so the summary call reaches the provider with the same tags as the fork's own calls. With
     * tracing on, those tags include the fork's span position — the run root, since the gate runs before the
     * iteration span opens — and the request also carries a {@link TracingCompactionObserver} over it, so a compaction
     * opens a COMPACTION span there and its summary LLM span nests under that.
     *
     * @param lc
     *            the loop context
     * @param iterationCount
     *            the iteration the gate runs before (for logging and the COMPACTION span)
     * @return the view to send (never null)
     * @throws ContextWindowExceededException
     *             if the engine blocks the iteration
     * @throws CancelledExecutionException
     *             if the engine blocks the iteration of a fork that has been cancelled
     */
    private ContextView applyCompactionGate(LoopContext lc, int iterationCount, boolean budgetForced) {
        // Hand the engine this fork's run identity. Without it the compaction engine has only the transcript label to
        // go on, and that label is a SessionId wrapping this very execution id — so a PreCompact hook would be told the
        // fork had a session, and told an execution id as its name. The two are tied together at both entries into
        // execute(): a fresh fork derives the label from the id (forkTranscriptLabel), a resume derives the id back out
        // of the restored label (ExecutionId.of), which is why the round trip has to survive the snapshot.
        final ContextRequest.Builder request = ContextRequest.builder().transcriptBuffer(lc.transcriptBuffer)
                .model(lc.modelConfig).hookRegistry(lc.hookRegistry()).executionEnvironment(lc.executionEnvironment())
                .executionCancellation(lc.coordinator.getSignal())
                .caller(ContextCaller.builder().executionId(lc.executionId).build()).budgetForced(budgetForced)
                .compactionInstructions(lc.policy == null ? null : lc.policy.getCompactionInstructions().orElse(null));
        // The fork's metadata without its call site: the span position, the principal and the tags inherited from the
        // parent travel, so the summary call nests in the trace and reaches the provider with the same routing and
        // policy tags as the fork's own calls. Component and feature stay out: the engine merges with caller fields
        // winning, so handing them over would book the summary call under this fork's feature instead of COMPACTION.
        // The group key travels too, or the engine would fill it from the transcript label — this execution id
        // dressed as a session.
        final LlmCallMetadata callMetadata = lc.effectiveMetadata.withoutCallSite();
        if (!callMetadata.isEmpty()) {
            request.callMetadata(callMetadata);
        }
        Spans.traceCarrier(lc.effectiveMetadata)
                .flatMap(carrier -> TracingCompactionObserver.under(lc.tracer, carrier, lc.tracePayloadPolicy,
                        lc.coordinator.getSignal(), compactionSpanAttributes(lc, iterationCount, budgetForced)))
                .ifPresent(request::compactionObserver);
        final ContextDecision decision = lc.contextEngine.prepare(request.build());
        switch (decision.getAction()) {
            case BLOCK :
                // An interrupted preCompact guard blocks, the compaction is skipped, and a view over the blocking
                // limit then answers BLOCK. That window error was caused by the interrupt, so it is the interrupt:
                // unwind as a cancellation, which the loop turns into the fork's interrupted result.
                lc.coordinator.getSignal().checkpoint();
                log.error("Compaction guard blocked subagent iteration {}: {}", iterationCount, decision.getReason());
                throw new ContextWindowExceededException(decision.getEstimatedTokens(), decision.getBlockingLimit(),
                        decision.getReason());
            case COMPACT :
                log.info("Compaction performed before subagent iteration {}: {}", iterationCount, decision.getReason());
                break;
            case WARN :
                log.warn("Compaction guard warning at subagent iteration {}: {}", iterationCount, decision.getReason());
                break;
            case NONE :
            default :
                break;
        }
        return decision.getView();
    }

    /**
     * The attributes of a fork's COMPACTION span: the iteration it gates, the fork's run identity, and the budget hint
     * only when it forced.
     */
    private static Map<String, String> compactionSpanAttributes(LoopContext lc, int iterationCount,
            boolean budgetForced) {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("iteration", String.valueOf(iterationCount));
        attributes.put("invokerType", InvokerType.SUBAGENT.name());
        attributes.put("executionId", lc.executionId.value());
        if (budgetForced) {
            attributes.put("budgetForced", "true");
        }
        return attributes;
    }

    /**
     * Returns {@code true} if the execution-scoped signal is tripped or the current thread carries an interrupt.
     * Delegates to
     * {@link SubagentInterrupts#isCancelledOrInterrupted(CancellationSignal)} so the ReAct and code-behavior paths
     * share
     * one definition — including its interrupt-flag-clearing semantics — and cannot drift.
     *
     * @param cancellationSignal
     *            the execution-scoped cancellation signal (must not be null)
     * @return {@code true} if cancellation has been requested by signal or thread interrupt
     */
    private static boolean isCancelledOrInterrupted(CancellationSignal cancellationSignal) {
        return SubagentInterrupts.isCancelledOrInterrupted(cancellationSignal);
    }

    /**
     * Resolves the fork's execution environment through the provider forwarded from the spawning execution, passing
     * the spawner's environment as the parent and the fork's own definition, so a provider can place each subagent
     * differently (design §5.2).
     *
     * <p>
     * The request carries no {@link EnvironmentRequest#requestAttributes() request attributes}, on purpose (issue
     * #252): the request's execution attributes may still hold the parent turn's {@code environment.*} entries, and
     * they keep reaching the fork's tools, but a fork's placement is its parent environment's business. Forwarding
     * them would let a fork ask for a different isolation unit than its parent's.
     */
    private static ExecutionEnvironment resolveExecutionEnvironment(SubagentExecutionContext context,
            SubagentExecutionRequest request, ExecutionId executionId) {
        final EnvironmentRequest environmentRequest = EnvironmentRequest.builder()
                .agentRuntimeId(context.getAgentRuntimeId()).executionId(executionId)
                .invokingSessionId(request.getInvokingSessionId().orElse(null))
                .principal(request.getPrincipal().orElse(null)).parent(context.getExecutionEnvironment().orElse(null))
                .fork(ForkDefinition.builder().name(context.getSubagent().getName())
                        .attributes(context.getSubagent().getMetadata().getAttributes()).build())
                .build();
        if (context.getExecutionEnvironmentProvider().isEmpty()) {
            log.warn("No ExecutionEnvironmentProvider forwarded to fork '{}'; its file and shell tools will fail",
                    context.getSubagent().getName());
            return UnavailableExecutionEnvironment.of("no ExecutionEnvironmentProvider forwarded to this fork");
        }
        return ExecutionEnvironments.resolveOrUnavailable(context.getExecutionEnvironmentProvider().get(),
                environmentRequest);
    }

    /**
     * The fork's goal: the request's, or &mdash; when the request carries a {@link ForkGoalRenderer} and the fork's
     * environment is not the spawner's &mdash; that renderer's answer for the fork's environment (EE-81).
     *
     * @throws RuntimeException
     *             whatever the renderer throws (a staging failure, an unavailable environment)
     */
    private static String forkGoal(SubagentExecutionContext context, SubagentExecutionRequest request,
            ExecutionEnvironment forkEnvironment) {
        final Optional<ForkGoalRenderer> renderer = request.getForkGoalRenderer();
        if (renderer.isEmpty() || forkEnvironment == context.getExecutionEnvironment().orElse(null)) {
            return request.getGoal();
        }
        return Objects.requireNonNull(renderer.get().render(forkEnvironment), "the fork goal renderer returned null");
    }

    /**
     * Labels a fork's transcript buffer with the fork's run identity.
     *
     * <p>
     * {@link TranscriptBuffer} is typed on {@link SessionId} and a fork has no session, so one conversion is forced —
     * confined to this method, and harmless because the label is never a lookup key: a suspended fork's transcript is
     * filed in the snapshot store under the <em>task</em> id, not under this. Nothing resolves a session from it, no
     * lease is taken out on it, and the ids the fork shows its tools are {@link ToolContextKeys#EXECUTION_ID} and
     * {@link ToolContextKeys#INVOKING_SESSION_ID}.
     *
     * @param executionId
     *            the fork's run identity (must not be null)
     * @return the transcript label (never null)
     */
    private static SessionId forkTranscriptLabel(ExecutionId executionId) {
        return SessionId.of(executionId.value());
    }

    /**
     * Creates the tool execution context with the same keys the main-agent executor injects, so subagent tools run in
     * an equivalent execution environment (environment, principal, attributes, LLM metadata, artifact collector, todo
     * context id, cancellation signal, and the optional tool-search registry).
     *
     * @param context
     *            the subagent runtime
     * @param request
     *            the subagent execution request (carries the principal)
     * @param sessionRegistry
     *            the per-session tool registry (may be a {@link ToolSearchRegistry})
     * @param cancellationSignal
     *            the execution-scoped cancellation signal cooperative tools poll (must not be null)
     * @param effectiveMetadata
     *            the resolved LLM call metadata propagated to nested executions
     * @param executionId
     *            this fork's run identity (published to tools, and the key per-run state partitions on)
     * @param artifactCollector
     *            the per-execution artifact collector exposed to artifact-producing tools
     * @param executionEnvironment
     *            the fork's execution environment, published under the write-once key before the enrichers run
     * @param engine
     *            the context engine the fork runs under, published so a fork it spawns can inherit it
     * @return the tool context (never null)
     */
    // Every argument is a distinct per-execution value the context publishes; bundling them would only move the list.
    @SuppressWarnings("checkstyle:ParameterNumber")
    private ToolContext createToolContext(SubagentExecutionContext context, SubagentExecutionRequest request,
            ToolRegistry sessionRegistry, CancellationSignal cancellationSignal, LlmCallMetadata effectiveMetadata,
            ExecutionId executionId, ArtifactCollector artifactCollector, ExecutionEnvironment executionEnvironment,
            ContextEngine engine) {
        final ToolContext.Builder builder = ToolContext.builder();
        builder.put(ToolContextKeys.AGENT_RUNTIME_ID, context.getAgentRuntimeId());
        // No SESSION_ID: this run is not a session's turn. The fork used to publish the id it had minted for its own
        // transcript, which read to every tool exactly like a user session while granting none of what one implies.
        // Its run identity goes here instead — unique per fork, so per-run tool state stays partitioned rather than
        // bleeding into the parent's, and never forwarded, so a nested fork cannot mistake it for an inherited session.
        builder.put(ToolContextKeys.EXECUTION_ID, executionId);
        // How deep this fork is, so a fork it spawns is labelled one deeper. Read-only for the fork's tools.
        builder.put(ToolContextKeys.FORK_DEPTH, request.getForkDepth());
        // Which is why the invoking session is published beside it: an execution id names nobody and so grants
        // nothing, so a policy looking for the user's decision must ask under the id the user actually answered for.
        // Absent for runs nobody asked for (scheduled tasks), which inherit nothing — hence the guard,
        // ToolContext.Builder#put rejecting nulls.
        request.getInvokingSessionId().ifPresent(id -> builder.put(ToolContextKeys.INVOKING_SESSION_ID, id));

        request.getPrincipal().ifPresent(p -> builder.put(ToolContextKeys.PRINCIPAL, p));
        // Republished unchanged, so a fork this fork starts is charged to the same turn's keys.
        builder.put(ToolContextKeys.QUOTA_GUARD, request.getQuotaGuard());
        builder.put(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY, request.getExecutionAttributes());
        builder.put(ToolContextKeys.LLM_CALL_METADATA_KEY, effectiveMetadata);
        builder.put(ToolContextKeys.ARTIFACT_COLLECTOR, artifactCollector);
        builder.put(TodoWriteTool.CONTEXT_ID_KEY, executionId.value());

        // PAR-05 / execution-environment §7: inject a thread-safe, execution-scoped map of read stamps (parity with
        // the main-agent executor). A fresh map, not the parent's: a fork does not inherit its parent's reads.
        builder.put(ReadTool.FILE_STAMPS_KEY, new ConcurrentHashMap<>());

        // Publish the execution-scoped signal so cooperative subagent tools can poll it through
        // InterruptAccess#signalOf.
        builder.put(InterruptToolKeys.CANCELLATION_SIGNAL, cancellationSignal);

        // Inject the forwarded knowledge store + scope. The subagent reuses the PARENT's KnowledgeScope verbatim, so it
        // shares the parent's knowledge namespace: reads see the parent's knowledge and writes merge back into it. This
        // is deliberate — subagents are operator-defined helpers working within the parent.s agent runtime, so
        // sharing is more useful than an empty isolated namespace. The Orca path always pairs store+scope; if a store
        // is ever forwarded without a scope, inject the store anyway but log so the missing scope-aware filtering is
        // observable.
        context.getKnowledgeStore().ifPresent(ks -> {
            builder.put(ToolContextKeys.KNOWLEDGE_STORE, ks);
            final Optional<KnowledgeScope> scope = context.getKnowledgeScope();
            if (scope.isPresent()) {
                builder.put(ToolContextKeys.KNOWLEDGE_SCOPE, scope.get());
            } else {
                log.warn("Knowledge store forwarded to subagent '{}' without a knowledge scope; "
                        + "scope-aware filtering will be unavailable", context.getSubagent().getName());
            }
        });

        // Inject per-session ToolSearchRegistry if applicable
        if (sessionRegistry instanceof ToolSearchRegistry searchRegistry) {
            builder.put(ToolContextKeys.TOOL_SEARCH_REGISTRY, searchRegistry);
        }

        // The execution's environment and the provider it came from, before the enrichers: both keys are write-once,
        // so an enricher that tries to replace either fails (and is logged) instead of silently swapping the
        // filesystem and shell under the fork's tools.
        builder.put(ToolContextKeys.EXECUTION_ENVIRONMENT, executionEnvironment);
        // Likewise the registry this fork dispatches against, so the forks it spawns inherit it — inside a forked
        // skill that is the view carrying the skill's hooks. Write-once for the same reason as the environment.
        builder.put(ToolContextKeys.HOOK_REGISTRY, context.getHookRegistry());
        context.getExecutionEnvironmentProvider()
                .ifPresent(p -> builder.put(ToolContextKeys.EXECUTION_ENVIRONMENT_PROVIDER, p));
        // The engine this fork runs under, and its deadline when it has one: a fork it spawns inherits the first on
        // request and, when it runs under a policy of its own, is held to the second. Both write-once, so an enricher
        // cannot lift the deadline.
        builder.put(ToolContextKeys.CONTEXT_ENGINE, engine);
        request.getDeadline().ifPresent(d -> builder.put(ToolContextKeys.EXECUTION_DEADLINE, d));

        // Apply forwarded ToolContextEnrichers last, mirroring the main-agent executor, so module-supplied keys are
        // present for subagent tools.
        applyEnrichers(builder, context, request, executionId);

        return builder.build();
    }

    /**
     * Invokes the forwarded {@link ToolContextEnricher enrichers} against the builder, isolating each failure so a
     * misbehaving enricher cannot abort tool-context assembly. Mirrors the main-agent executor's enrichment step.
     *
     * <p>
     * The info object describes this run as what it is: an execution id and, when some session asked for the work, the
     * id of that session. No session id of its own, because there is none — an enricher that keys durable state on the
     * run's own identity would look up something that by construction was never written. See
     * {@link ToolContextEnrichmentInfo#getInvokingSessionId()} for which of the two an enricher should reach for.
     *
     * @param builder
     *            the tool-context builder being assembled
     * @param context
     *            the subagent runtime (supplies enrichers + agent runtime id)
     * @param request
     *            the execution request (supplies the invoking session id, absent for uninvoked runs)
     * @param executionId
     *            this fork's run identity
     */
    private static void applyEnrichers(ToolContext.Builder builder, SubagentExecutionContext context,
            SubagentExecutionRequest request, ExecutionId executionId) {
        final List<ToolContextEnricher> enrichers = context.getToolContextEnrichers();
        if (enrichers.isEmpty()) {
            return;
        }
        final ToolContextEnrichmentInfo.Builder infoBuilder = ToolContextEnrichmentInfo.builder()
                .executionId(executionId).agentRuntimeId(context.getAgentRuntimeId());
        request.getInvokingSessionId().ifPresent(infoBuilder::invokingSessionId);
        final ToolContextEnrichmentInfo info = infoBuilder.build();
        for (ToolContextEnricher enricher : enrichers) {
            try {
                enricher.enrich(builder, info);
            } catch (Exception e) {
                log.warn("ToolContextEnricher {} failed: {}", enricher.getClass().getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Builds the tool definitions offered to this fork's LLM, withholding whatever the tool execution manager would
     * refuse anyway — a fork shown a tool it cannot use spends an iteration picking it and reading the refusal.
     *
     * <p>
     * Two axes are withheld, and each reads its bound from the same place the refusal does, so a filter and a refusal
     * cannot disagree:
     *
     * <ul>
     * <li>The <b>side-effect ceiling</b>, read from the {@link ToolExecutionManager} rather than configured here.
     * <li>The <b>subagent's allow-list</b>, read through {@link SubagentToolScope} from the same
     * {@code getAllowedTools()} handed to that manager at dispatch. Only a tool whose <i>name</i> the allow-list never
     * mentions is withheld: a pattern entry such as {@code Bash(git:*)} keeps {@code Bash} on offer, because a tool
     * list cannot say which arguments are allowed, and an out-of-pattern call is still refused at dispatch.
     * </ul>
     *
     * <p>
     * <b>Only the definitions are narrowed; {@code lc.sessionRegistry} is left alone.</b> Not because dispatch
     * resolves against it — it does not, on either path: {@link SingleToolInvoker} passes the context's full registry
     * to the execution manager and reads the session registry only for interrupt behaviour, and the parallel
     * dispatcher reads it only to decide whether to parallelize before handing the call to that same invoker. The
     * reasons are that this registry may be the {@link ToolSearchRegistry} carrying the fork's activation state, which
     * a narrowed copy would discard along with the only route to a deferred tool; and that narrowing it would couple
     * an allow-list to two unrelated mechanisms, forcing any batch naming a withheld tool to run sequentially and
     * resolving that tool as {@code NON_INTERRUPTIBLE}.
     *
     * <p>
     * <b>An empty result is logged.</b> Either filter alone always left something; together they can leave nothing —
     * an allow-list of misspelled names, one naming only tools above the ceiling, or one omitting
     * {@code ToolSearch} in a deployment whose tools are all deferred. Every provider omits an empty {@code tools}
     * field rather than rejecting it, so the model answers from prose and the fork reports a clean success. The
     * warning is what makes that diagnosable. Logging on the first iteration is enough and not a sample:
     * {@code findAll()} only ever grows, and both bounds are fixed for the execution, so an offer empty here is empty
     * for every later iteration.
     *
     * @param lc
     *            the loop context of the running fork
     * @param iterationCount
     *            the 1-based iteration about to call the LLM
     * @return the definitions to send with the next LLM call
     */
    private List<ToolDefinition> availableToolDefinitions(LoopContext lc, int iterationCount) {
        final SideEffectLevel ceiling = toolExecutionManager.getMaxSideEffectLevel();
        final Subagent subagent = lc.subagent();
        final Predicate<Tool> admitted = SubagentToolScope.admissionFilter(subagent);
        final List<ToolDefinition> definitions = lc.sessionRegistry.findAll().stream()
                .filter(tool -> ceiling.permits(tool.getSideEffectLevel())).filter(admitted).map(Tool::getDefinition)
                .toList();
        if (definitions.isEmpty() && iterationCount == 1 && subagent.hasToolRestrictions()) {
            log.warn(
                    "Subagent '{}' is offered no tools: its allowed-tools names {} and the side-effect ceiling is {}, "
                            + "leaving nothing from the {} registered tool(s). It will answer without acting.",
                    subagent.getName(), subagent.getAllowedTools(), ceiling, lc.sessionRegistry.findAll().size());
        }
        return definitions;
    }

    /**
     * Creates a per-session tool registry. If the underlying registry is a {@link ToolSearchCatalog}, a new
     * {@link ToolSearchRegistry} is created for session-scoped tool activation. Otherwise the original registry is
     * returned unchanged.
     *
     * @param toolRegistry
     *            the execution context's tool registry
     * @return the per-session registry
     */
    private static ToolRegistry createSessionRegistry(ToolRegistry toolRegistry) {
        if (toolRegistry instanceof ToolSearchCatalog catalog) {
            return catalog.createRegistry();
        }
        return toolRegistry;
    }

    /**
     * Builds a dynamic system prompt by injecting the execution environment's description.
     *
     * @param baseSystemPrompt
     *            The base system prompt from Subagent configuration
     * @param descriptor
     *            The fork's execution environment descriptor
     * @return The system prompt with dynamically injected environment information
     */
    private String buildDynamicSystemPrompt(String baseSystemPrompt, EnvironmentDescriptor descriptor) {
        return baseSystemPrompt + "\n\n" + EnvironmentBlocks.render(descriptor);
    }

    /**
     * Builds {@link LlmModel} from subagent metadata, with a caller-supplied override taking priority and a fallback to
     * the default config.
     *
     * @param subagent
     *            The subagent definition
     * @param defaultConfig
     *            The default model config to use as fallback
     * @param modelOverride
     *            The per-invocation model name, sent as written (nullable/blank = ignored), highest priority when set
     * @return An LlmModel
     */
    private LlmModel buildModelConfig(Subagent subagent, LlmModel defaultConfig, String modelOverride) {
        return SubagentLlmDefaults.resolveModel(subagent, defaultConfig, modelOverride);
    }

    /**
     * Runs as many of {@code toolUses} as the fork's tool-call budget still allows, in order, and answers the rest
     * with an error result that says they were not run. Without a cap this is {@link #executeToolUses}. A fork under a
     * policy whose budget the carrying response exhausted runs none of them.
     *
     * <p>
     * Every call of a parallel batch counts as one. The calls that run are the first ones of the response, so a model
     * that lists them in the order it needs them loses the ones it needed least. The refused calls are not handed to
     * the dispatcher at all — no hook, no permission prompt, no execution — and they do not count as run.
     *
     * <p>
     * A call of a tool its policy no longer runs ({@link ToolFailureStreaks}) is answered first, the same way, and
     * takes none of the tool-call budget; the rest go through the budget as described.
     *
     * @return one result per tool use, in the response's order (never null)
     */
    private List<ToolUseResult> executeAdmittedToolUses(LoopContext lc, ToolContext toolContext, List<ToolUse> toolUses,
            int iterationCount) {
        // The response that carried these calls may itself have spent the last of the budget: its usage is known only
        // now. Then none of them runs; the next iteration boundary ends the fork with the budget's reason, before
        // another LLM call.
        // Only the token axis: the tracker's iteration count already includes this iteration, so asking it for STOP
        // would refuse the last allowed iteration's tools.
        if (lc.tokenBudgetExhausted()) {
            lc.toolCalls.refuseForTokens();
            log.warn("Subagent '{}' spent its token budget with the response of iteration {}; its {} tool call(s) are"
                    + " not run", lc.subagent().getName(), iterationCount, toolUses.size());
            final List<ToolUseResult> refused = new ArrayList<>(toolUses.size());
            for (ToolUse toolUse : toolUses) {
                refused.add(ToolUseResult.error(toolUse.getId(),
                        "Not run: this subagent's budget is used up. Finish with what you have."));
            }
            return refused;
        }
        if (lc.toolFailures.blocksAny(toolUses)) {
            return executeAroundBlockedTools(lc, toolContext, toolUses, iterationCount);
        }
        return executeWithinCallBudget(lc, toolContext, toolUses, iterationCount);
    }

    /**
     * Answers each call of a tool that failed too often in a row with {@link ToolFailureStreaks#refusal} — not run, no
     * hook, no permission prompt — and runs the others through {@link #executeWithinCallBudget}, keeping the
     * response's order in the results.
     */
    private List<ToolUseResult> executeAroundBlockedTools(LoopContext lc, ToolContext toolContext,
            List<ToolUse> toolUses, int iterationCount) {
        // Matched back by position, not by tool-use id: an id is not guaranteed unique within a response.
        final boolean[] blocked = new boolean[toolUses.size()];
        final List<ToolUse> runnable = new ArrayList<>(toolUses.size());
        for (int i = 0; i < toolUses.size(); i++) {
            blocked[i] = lc.toolFailures.isBlocked(toolUses.get(i).getName());
            if (!blocked[i]) {
                runnable.add(toolUses.get(i));
            }
        }
        final List<ToolUseResult> ran = runnable.isEmpty()
                ? List.of()
                : executeWithinCallBudget(lc, toolContext, runnable, iterationCount);
        final List<ToolUseResult> results = new ArrayList<>(toolUses.size());
        int next = 0;
        for (int i = 0; i < toolUses.size(); i++) {
            final ToolUse toolUse = toolUses.get(i);
            if (!blocked[i]) {
                results.add(ran.get(next++));
                continue;
            }
            log.info("Subagent '{}' called '{}' in iteration {} after {}; not run", lc.subagent().getName(),
                    toolUse.getName(), iterationCount, lc.toolFailures.blockCause(toolUse.getName()));
            stream(lc, "\n→ " + toolUse.getName() + "\n");
            final ToolUseResult refusal = lc.toolFailures.refusal(toolUse);
            stream(lc, formatStreamedToolResult(toolUse, refusal));
            results.add(refusal);
        }
        return results;
    }

    /**
     * Runs as many of {@code toolUses} as the fork's tool-call budget still allows, in order, and answers the rest
     * with an error result that says they were not run.
     *
     * @return one result per tool use, in the response's order (never null)
     */
    private List<ToolUseResult> executeWithinCallBudget(LoopContext lc, ToolContext toolContext, List<ToolUse> toolUses,
            int iterationCount) {
        final int admitted = lc.toolCalls.admit(toolUses.size());
        if (admitted == toolUses.size()) {
            return executeToolUses(lc, toolContext, toolUses, iterationCount);
        }
        log.warn(
                "Subagent '{}' asked for {} tool call(s) in iteration {} with {} left of its budget of {}; the other"
                        + " {} are not run",
                lc.subagent().getName(), toolUses.size(), iterationCount, admitted, lc.toolCalls.limit,
                toolUses.size() - admitted);
        final List<ToolUseResult> results = new ArrayList<>(toolUses.size());
        if (admitted > 0) {
            results.addAll(executeToolUses(lc, toolContext, toolUses.subList(0, admitted), iterationCount));
        }
        for (ToolUse toolUse : toolUses.subList(admitted, toolUses.size())) {
            stream(lc, "\n→ " + toolUse.getName() + "\n");
            final ToolUseResult refusal = ToolCallQuota.refusal(toolUse, lc.toolCalls.limit);
            stream(lc, formatStreamedToolResult(toolUse, refusal));
            results.add(refusal);
        }
        return results;
    }

    /**
     * Executes multiple tool uses and returns their results.
     *
     * @param lc
     *            the loop context (carries context, coordinator, registry, attributes)
     * @param toolContext
     *            this iteration's tool context — the loop context's, with its metadata pointing at the iteration span
     *            when tracing is on. Captured by the per-call lambda, so parallel workers receive their parent as data
     * @param toolUses
     *            The list of tool uses to execute
     * @param iterationCount
     *            The current iteration count
     * @return The list of tool use results (never null)
     */
    private List<ToolUseResult> executeToolUses(LoopContext lc, ToolContext toolContext, List<ToolUse> toolUses,
            int iterationCount) {
        // PAR-04: delegate to the dispatcher. The start/completed callbacks serialize per-tool progress into the output
        // sink (a no-op for foreground; a TaskOutputStore append for background tasks). onCompleted may fire on a
        // shared
        // worker thread under the parallel path, so the sink must be thread-safe — the store's append is. The gate uses
        // the per-session registry (the same source executeSingleTool reads interrupt behaviour from). With the
        // default sequential dispatcher this is 1:1 equivalent to the previous for-loop. The fork's depth goes along so
        // a dedicated-thread dispatcher can decide whether a fork this deep may fan out; any other dispatcher ignores
        // it.
        final List<ToolUseResult> results = parallelToolDispatcher.dispatch(toolUses, lc.sessionRegistry,
                DispatchOptions.builder().forkDepth(lc.forkDepth).build(),
                toolUse -> executeSingleTool(lc, toolContext, toolUse, iterationCount),
                toolUse -> stream(lc, "\n→ " + toolUse.getName() + "\n"),
                (toolUse, result) -> stream(lc, formatStreamedToolResult(toolUse, result)));
        // Back on the loop's thread: every call here was run, so each outcome counts for its tool's streak.
        for (int i = 0; i < toolUses.size(); i++) {
            lc.toolFailures.record(toolUses.get(i).getName(), results.get(i));
        }
        return results;
    }

    /**
     * Answers every tool call of a response the provider cut off at {@code max_tokens} with
     * {@link TruncatedResponses#refusal(ToolUse)} instead of running it — the answer {@code OrcaAgentExecutor} gives a
     * turn, for the same reason: a cut call's arguments did not arrive, and the response does not say which call was
     * cut.
     *
     * <p>
     * Nothing on the tool path runs for these calls: no PermissionRequest, PreTool or PostTool hook, and no execution.
     * The progress stream still shows each call and its error, as {@link #executeToolUses} would. A refused
     * iteration is all-error, so the stalled-iteration guard counts it, as on a turn: a fork whose every response is
     * cut ends as {@link CompletionReason#ERROR} after
     * {@link StalledIterationGuard#MAX_CONSECUTIVE_STALLED_ITERATIONS} of them, with a stop message that names
     * {@code max_tokens}.
     *
     * @param lc
     *            the loop context
     * @param response
     *            the cut response whose tool calls are refused
     * @param iterationCount
     *            the current iteration count
     * @return one error result per tool call, in the response's order (never null)
     */
    private List<ToolUseResult> refuseTruncatedToolUses(LoopContext lc, LlmResponse response, int iterationCount) {
        final List<ToolUse> toolUses = response.getToolUses();
        log.warn(
                "Subagent '{}' response truncated at max_tokens in iteration {} with tool calls {}: none of them is "
                        + "run; each is answered with an error result{}",
                lc.subagent().getName(), iterationCount, toolUses.stream().map(ToolUse::getName).toList(),
                TruncatedResponses.reasoningClause(response.getTokenUsage()));
        final List<ToolUseResult> results = new ArrayList<>(toolUses.size());
        for (ToolUse toolUse : toolUses) {
            stream(lc, "\n→ " + toolUse.getName() + "\n");
            final ToolUseResult result = TruncatedResponses.refusal(toolUse);
            stream(lc, formatStreamedToolResult(toolUse, result));
            results.add(result);
        }
        return results;
    }

    /**
     * Formats a single tool result for the progress stream: the tool name, its ok/error status, and a bounded snippet
     * of its content (long tool outputs are capped so the progress log stays readable — the authoritative artifact is
     * the streamed final answer).
     */
    private static String formatStreamedToolResult(ToolUse toolUse, ToolUseResult result) {
        final String status = result.isError() ? "error" : "ok";
        String content = result.getContent();
        if (content == null) {
            content = "";
        }
        if (content.length() > STREAM_TOOL_RESULT_CAP) {
            content = content.substring(0, STREAM_TOOL_RESULT_CAP) + "…(" + (content.length() - STREAM_TOOL_RESULT_CAP)
                    + " more chars)";
        }
        return "← " + toolUse.getName() + " [" + status + "]\n" + content + "\n";
    }

    /**
     * Appends progress text to the per-execution output sink, defensively: streaming is best-effort telemetry and a
     * sink failure must never abort the subagent's execution.
     */
    private static void stream(LoopContext lc, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            lc.outputSink().append(text);
        } catch (RuntimeException e) {
            log.debug("Subagent output streaming append failed (ignored): {}", e.getMessage());
        }
    }

    /**
     * Executes a single tool use, including PermissionRequest, PreTool and PostTool hooks.
     *
     * <p>
     * Mirrors {@code OrcaAgentExecutor}: the target's {@link Tool#getInterruptBehavior()} is read from the
     * per-session registry; tools declaring {@link InterruptBehavior#THREAD_INTERRUPT} or
     * {@link InterruptBehavior#EXTERNALLY_TERMINATED} receive a fresh {@link TerminatorRegistrar} (closed in a finally
     * block), and THREAD_INTERRUPT tools additionally get a {@code Thread.currentThread()::interrupt} terminator
     * pre-registered. Permission is enforced both via the PermissionRequest hook chain and the subagent's
     * {@code allowedTools} allow-list passed to the {@link ToolExecutionManager}. PostTool hook failures are isolated
     * so
     * a hook exception never discards the real tool result.
     *
     * <p>
     * TRACE-01: when {@code toolContext} carries a span parent, the call runs inside a {@code TOOL} span, and the tool
     * receives a context pointing at that span — so a {@code Task} this fork launches nests its SUBAGENT span under the
     * call, exactly as on the main agent. Without a parent, no span is created and the context is passed unchanged.
     *
     * @param lc
     *            the loop context
     * @param toolContext
     *            this iteration's tool context
     * @param toolUse
     *            the tool use request from the LLM
     * @param iterationCount
     *            the current iteration count
     * @return The tool use result (never null)
     */
    private ToolUseResult executeSingleTool(LoopContext lc, ToolContext toolContext, ToolUse toolUse,
            int iterationCount) {
        final Tracer.Span toolSpan = ToolCallTracing.open(lc.tracer, toolContext, toolUse, iterationCount,
                InvokerType.SUBAGENT);
        if (toolSpan == null) {
            return invokeTool(lc, toolContext, toolUse, iterationCount);
        }
        try {
            final ToolUseResult result = invokeTool(lc, ToolCallTracing.contextUnder(toolSpan, toolContext), toolUse,
                    iterationCount);
            ToolCallTracing.recordOutcome(toolSpan, result, lc.tracePayloadPolicy);
            return result;
        } catch (RuntimeException | Error e) {
            ToolCallTracing.recordFailure(toolSpan, e, toolContext);
            throw e;
        } finally {
            toolSpan.close();
        }
    }

    private ToolUseResult invokeTool(LoopContext lc, ToolContext toolContext, ToolUse toolUse, int iterationCount) {
        final Subagent subagent = lc.subagent();
        // Delegate the interrupt-registrar + PermissionRequest/PreTool/execute/PostTool sequence to the shared
        // pipeline. The subagent variance is the SUBAGENT invoker identity and its declared allow-list.
        final ToolInvocationSpec spec = ToolInvocationSpec.builder().invokerType(InvokerType.SUBAGENT)
                .invokerName(subagent.getName()).hookRegistry(lc.hookRegistry())
                .executionAttributes(lc.executionAttributes).toolRegistry(lc.context.getToolRegistry())
                .sessionRegistry(lc.sessionRegistry).allowedTools(subagent.getAllowedTools())
                .coordinator(lc.coordinator).toolContext(toolContext).toolUse(toolUse).iterationCount(iterationCount)
                .build();
        return singleToolInvoker.invoke(spec);
    }

    /**
     * Ends the fork on a response without tool calls: its text is the final answer. A response cut off at
     * {@code max_tokens} is not a clean answer — the partial text gets the shared marker and the fork ends as
     * {@link CompletionReason#TRUNCATED}, so a parent, a workflow judge or a task record can tell it from a
     * {@link CompletionReason#COMPLETED} one.
     */
    private SubagentExecutionResult finishWithAnswer(LoopContext lc, LlmResponse response, boolean truncated,
            int iterationCount, TokenUsage accumulatedTokens) {
        if (truncated) {
            final String flaggedAnswer = response.getTextContent() + TruncatedResponses.TRUNCATION_MARKER;
            lc.transcriptBuffer
                    .addMessage(Message.assistant(flaggedAnswer).withReasoningTraces(response.getReasoningTraces()));
            return createTruncatedResult(lc, flaggedAnswer, iterationCount, accumulatedTokens,
                    response.getTokenUsage());
        }
        lc.transcriptBuffer.addMessage(
                Message.assistant(response.getTextContent()).withReasoningTraces(response.getReasoningTraces()));
        return createSuccessResult(lc, response.getTextContent(), iterationCount, accumulatedTokens);
    }

    /**
     * Creates a success result, firing OnStop hooks with {@code success=true}.
     */
    private SubagentExecutionResult createSuccessResult(LoopContext lc, String finalAnswer, int iterationCount,
            TokenUsage accumulatedTokens) {
        final ExecutionMetadata metadata = buildMetadata(lc, iterationCount, accumulatedTokens);
        final OnStopContext onStopContext = OnStopContext.builder().executorType(InvokerType.SUBAGENT)
                .invokerName(lc.subagent().getName()).hookRegistry(lc.hookRegistry())
                .executionEnvironment(lc.executionEnvironment()).executionCancellation(lc.coordinator.getSignal())
                .success(true).finalAnswer(finalAnswer).metadata(metadata).executionAttributes(lc.executionAttributes)
                .build();
        hookExecutionManager.executeOnStop(onStopContext);
        // Stream the full final answer plus a terminal boundary so a background tail sees the complete result and knows
        // the task finished.
        stream(lc, "\n[final answer]\n" + (finalAnswer != null ? finalAnswer : "") + "\n[completed: SUCCESS after "
                + iterationCount + " iterations]\n");
        return SubagentExecutionResult.success(finalAnswer, lc.transcriptBuffer.toSnapshot(), metadata,
                CompletionReason.COMPLETED, estimateCost(lc, accumulatedTokens));
    }

    /**
     * Creates the result for a final answer the provider cut off at {@code max_tokens}, firing OnStop hooks with
     * {@code success=true}.
     *
     * <p>
     * Mirrors {@link #createSuccessResult} but carries {@link CompletionReason#TRUNCATED}, the shape
     * {@code OrcaAgentExecutor} gives a truncated turn. {@code success(...)} rather than {@code failure(...)} keeps the
     * partial text in {@link SubagentExecutionResult#getSummary()}, where a failure would put an error message instead;
     * the completion reason is what tells a parent, a workflow judge or a task record that the answer is incomplete.
     *
     * @param lc
     *            the loop context
     * @param flaggedAnswer
     *            the partial answer, already suffixed with {@link TruncatedResponses#TRUNCATION_MARKER}
     * @param iterationCount
     *            the number of iterations completed
     * @param accumulatedTokens
     *            the execution's accumulated token usage
     * @param responseUsage
     *            the cut response's own token usage, read for the WARN's reasoning clause
     * @return the truncated result (never null)
     */
    private SubagentExecutionResult createTruncatedResult(LoopContext lc, String flaggedAnswer, int iterationCount,
            TokenUsage accumulatedTokens, TokenUsage responseUsage) {
        log.warn(
                "Subagent '{}' final answer truncated at max_tokens after {} iterations; returning flagged partial "
                        + "answer{}",
                lc.subagent().getName(), iterationCount, TruncatedResponses.reasoningClause(responseUsage));
        final ExecutionMetadata metadata = buildMetadata(lc, iterationCount, accumulatedTokens);
        final OnStopContext onStopContext = OnStopContext.builder().executorType(InvokerType.SUBAGENT)
                .invokerName(lc.subagent().getName()).hookRegistry(lc.hookRegistry())
                .executionEnvironment(lc.executionEnvironment()).executionCancellation(lc.coordinator.getSignal())
                .success(true).finalAnswer(flaggedAnswer).metadata(metadata).executionAttributes(lc.executionAttributes)
                .build();
        hookExecutionManager.executeOnStop(onStopContext);
        // The same terminal boundary a success streams, naming why the answer is incomplete.
        stream(lc, "\n[final answer]\n" + flaggedAnswer + "\n[completed: TRUNCATED at max_tokens after "
                + iterationCount + " iterations]\n");
        return SubagentExecutionResult.success(flaggedAnswer, lc.transcriptBuffer.toSnapshot(), metadata,
                CompletionReason.TRUNCATED, estimateCost(lc, accumulatedTokens));
    }

    /**
     * Creates the result of a grace round's answer ({@link #endAtIterationLimit}), firing OnStop hooks with
     * {@code success=true}. The shape of {@link #createTruncatedResult}: a success, so the answer is the summary,
     * carrying {@link CompletionReason#MAX_ITERATIONS_GRACE}, which says it is not a finished one.
     */
    private SubagentExecutionResult createGraceResult(LoopContext lc, String answer, int iterationCount,
            TokenUsage accumulatedTokens) {
        final ExecutionMetadata metadata = buildMetadata(lc, iterationCount, accumulatedTokens);
        final OnStopContext onStopContext = OnStopContext.builder().executorType(InvokerType.SUBAGENT)
                .invokerName(lc.subagent().getName()).hookRegistry(lc.hookRegistry())
                .executionEnvironment(lc.executionEnvironment()).executionCancellation(lc.coordinator.getSignal())
                .success(true).finalAnswer(answer).metadata(metadata).executionAttributes(lc.executionAttributes)
                .build();
        hookExecutionManager.executeOnStop(onStopContext);
        stream(lc, "\n[final answer]\n" + answer + "\n[completed: " + CompletionReason.MAX_ITERATIONS_GRACE.name()
                + " after " + iterationCount + " iterations]\n");
        return SubagentExecutionResult.success(answer, lc.transcriptBuffer.toSnapshot(), metadata,
                CompletionReason.MAX_ITERATIONS_GRACE, estimateCost(lc, accumulatedTokens));
    }

    /**
     * Creates an interrupted result. Represented as a failed {@link SubagentExecutionResult} carrying the canonical
     * "Execution interrupted" message and {@link CompletionReason#INTERRUPTED}; OnStop hooks fire with
     * {@code success=false}.
     */
    private SubagentExecutionResult createInterruptedResult(LoopContext lc, int iterationCount,
            TokenUsage accumulatedTokens) {
        if (stoppedByDeadline(lc)) {
            return createDeadlineResult(lc, iterationCount, accumulatedTokens);
        }
        log.info("Subagent execution interrupted: iterations={}, tokens={}", iterationCount,
                accumulatedTokens.getTotalTokens());
        return createFailureResult(lc, INTERRUPTED_MESSAGE, iterationCount, accumulatedTokens,
                CompletionReason.INTERRUPTED);
    }

    /**
     * Creates the result of a fork cancelled while its OnStart hooks ran: the same interrupted result as
     * {@link #createInterruptedResult} — {@link CompletionReason#INTERRUPTED}, the canonical message, OnStop hooks
     * fired with {@code success=false} — except for the transcript it hands back.
     *
     * <p>
     * That is {@code beforeGoal}, as for a {@linkplain #createBlockedResult blocked} fork, not the live buffer. A
     * fork's goal is written by the parent model, which can also stop a background fork and resume it later; a goal
     * whose guard was cut off before it answered must not come back as conversation history on that resume, where the
     * OnStart hooks are shown only the new goal. No verdict, not persisted.
     */
    private SubagentExecutionResult createInterruptedBeforeStartResult(LoopContext lc, SessionSnapshot beforeGoal) {
        log.info("Subagent '{}' interrupted during its OnStart hooks; not started", lc.subagent().getName());
        if (stoppedByDeadline(lc)) {
            return createFailureResult(lc, DEADLINE_MESSAGE, 0, TokenUsage.empty(),
                    CompletionReason.WALL_CLOCK_EXCEEDED, beforeGoal);
        }
        return createFailureResult(lc, INTERRUPTED_MESSAGE, 0, TokenUsage.empty(), CompletionReason.INTERRUPTED,
                beforeGoal);
    }

    /**
     * Whether the fork's signal was tripped by its own deadline, read from the reason the signal recorded first — so a
     * deadline that landed after a parent's cancellation does not relabel it, and one that landed before is not
     * relabelled by the cancellation that cascades after it. A fork stopped by a thread interrupt alone — its signal
     * never tripped — was stopped by its deadline when that has passed: on a watched foreground run the manager
     * interrupts the fork's thread once the deadline has passed, which may land before this fork's own timer trips the
     * signal. And a fork cancelled by a parent whose own deadline tripped it was stopped by its deadline when that has
     * passed too: a child holding its parent's deadline shares its instant, and the parent's timer usually fires first.
     */
    private static boolean stoppedByDeadline(LoopContext lc) {
        if (lc.deadline == null) {
            return false;
        }
        final Optional<InterruptReason> reason = lc.coordinator.getSignal().getReason();
        if (reason.isEmpty()) {
            return lc.deadline.isExpired();
        }
        if (reason.get() == InterruptReason.BUDGET_EXCEEDED) {
            return true;
        }
        return reason.get() == InterruptReason.PARENT_CANCELLED && lc.deadline.isExpired()
                && lc.context.getParentCancellationSignal().getReason()
                        .filter(parent -> parent == InterruptReason.BUDGET_EXCEEDED).isPresent();
    }

    /**
     * Creates the result of a fork whose deadline passed: {@link CompletionReason#WALL_CLOCK_EXCEEDED}, whether the
     * deadline was seen at an iteration boundary or broke a call in flight. OnStop hooks fire with
     * {@code success=false}, as for a budget stop.
     */
    private SubagentExecutionResult createDeadlineResult(LoopContext lc, int iterationCount,
            TokenUsage accumulatedTokens) {
        log.info("Subagent '{}' stopped by its deadline ({}): iterations={}, tokens={}", lc.subagent().getName(),
                lc.deadline.getBudget(), iterationCount, accumulatedTokens.getTotalTokens());
        return createFailureResult(lc, DEADLINE_MESSAGE, iterationCount, accumulatedTokens,
                CompletionReason.WALL_CLOCK_EXCEEDED);
    }

    /**
     * Creates the result of a fork that asked for more tool calls than its policy allows:
     * {@link CompletionReason#TOOL_CALL_BUDGET_EXCEEDED} — or {@link CompletionReason#TOKEN_BUDGET_EXCEEDED} when the
     * batch was refused because the response carrying it spent the token budget. The calls over the cap were never run;
     * their error results
     * are in the transcript.
     */
    private SubagentExecutionResult createToolCallBudgetResult(LoopContext lc, int iterationCount,
            TokenUsage accumulatedTokens) {
        if (lc.toolCalls.tokensStopped) {
            // The batch was refused because its response spent the token budget: that budget is what stopped it.
            return createFailureResult(lc, "Execution stopped: " + CompletionReason.TOKEN_BUDGET_EXCEEDED.name(),
                    iterationCount, accumulatedTokens, CompletionReason.TOKEN_BUDGET_EXCEEDED);
        }
        log.info("Subagent '{}' stopped by its tool-call budget: ran {} of {} allowed, refused {}, iterations={}",
                lc.subagent().getName(), lc.toolCalls.used(), lc.toolCalls.limit, lc.toolCalls.refused(),
                iterationCount);
        return createFailureResult(lc,
                "Execution stopped: " + CompletionReason.TOOL_CALL_BUDGET_EXCEEDED.name() + " — ran "
                        + lc.toolCalls.used() + " tool call(s), the policy's limit; " + lc.toolCalls.refused()
                        + " more were not run",
                iterationCount, accumulatedTokens, CompletionReason.TOOL_CALL_BUDGET_EXCEEDED);
    }

    /**
     * Creates a budget-stop result carrying the {@link CompletionReason} that tripped the budget (falling back to
     * {@link CompletionReason#ABORTED} when the tracker reports none).
     */
    private SubagentExecutionResult createBudgetStopResult(LoopContext lc, int iterationCount,
            TokenUsage accumulatedTokens) {
        final CompletionReason reason = lc.budgetTracker.getStopReason().orElse(CompletionReason.ABORTED);
        log.info("Subagent execution stopped by budget: reason={}, iterations={}", reason, iterationCount);
        return createFailureResult(lc, "Execution stopped: " + reason.name(), iterationCount, accumulatedTokens,
                reason);
    }

    /**
     * Prices one LLM call and records it twice: on the tracker's cost axis, and on the spawning turn's quota keys.
     *
     * <p>
     * Cost mirror: when a cost estimator is wired, the call's price is folded into the tracker so a request-level
     * {@code ExecutionBudget.maxCostUsd} stops the loop in-flight — the result-time estimate alone would only report
     * the
     * total after the loop already exited. USD-only by contract, like {@code OrcaAgentExecutor}'s cost block: a non-USD
     * estimate is not enforced as a STOP. The quota charge goes to the turn's keys — the session's among them, though
     * the fork has none; an unpriced call charges tokens only.
     */
    private void recordCallCost(LoopContext lc, LlmResponse response) {
        Money callCost = null;
        if (costEstimator != CostEstimator.NOOP) {
            callCost = costEstimator.estimate(lc.modelConfig.getName().orElse(""), response.getTokenUsage());
            if (Money.USD.equals(callCost.getCurrency())) {
                lc.budgetTracker.recordCost(callCost);
            }
        }
        lc.quotaGuard.charge(lc.modelConfig.getName().orElse(null), response.getTokenUsage(), callCost);
    }

    /**
     * Creates the result of a fork a usage quota stopped: {@link CompletionReason#QUOTA_EXCEEDED}, with the violation
     * on the result and named in the message the parent reads. OnStop hooks fire with {@code success=false}, as for a
     * budget stop.
     */
    private SubagentExecutionResult createQuotaStopResult(LoopContext lc, int iterationCount,
            TokenUsage accumulatedTokens, QuotaViolation violation) {
        log.info("Subagent execution stopped by quota: {}, iterations={}", violation.describe(), iterationCount);
        return createFailureResult(lc,
                "Execution stopped: " + CompletionReason.QUOTA_EXCEEDED.name() + " — " + violation.describe(),
                iterationCount, accumulatedTokens, CompletionReason.QUOTA_EXCEEDED).withQuotaViolation(violation);
    }

    /**
     * Creates the result of a fork the stalled-iteration guard stopped: {@link CompletionReason#ERROR}, as a turn the
     * guard stops ends, with the guard's stop message ({@link StalledIterationGuard#stopMessage()}); OnStop hooks fire
     * with {@code success=false}.
     *
     * <p>
     * {@code ERROR} rather than a reason of its own: no reader of a fork's reason — a task record, a workflow step, a
     * fork-mode skill, the Task tool — branches differently on a stall, and the one reader that needs to tell a streak
     * of refused cut responses from a streak of failing tools, the parent model, reads it in the message. The WARN does
     * not name {@code max_tokens}, so each cut response keeps the one WARN that does.
     */
    private SubagentExecutionResult createStalledResult(LoopContext lc, int iterationCount,
            TokenUsage accumulatedTokens) {
        log.warn(
                "Subagent '{}' stalled-iteration guard tripped: consecutiveStalledIterations={}, iterations={}, "
                        + "tokens={}",
                lc.subagent().getName(), lc.stalledIterationGuard.getConsecutiveStalledIterations(), iterationCount,
                accumulatedTokens.getTotalTokens());
        return createFailureResult(lc, lc.stalledIterationGuard.stopMessage(), iterationCount, accumulatedTokens,
                CompletionReason.ERROR);
    }

    /**
     * Creates the result of a fork an OnStart hook blocked: {@link CompletionReason#BLOCKED} with the exception's
     * message, which names the hook event and carries the hooks' reasons, so every spawn path hands the parent the
     * refusal as it hands it any other failed fork ({@link SubagentOnStartGate#blockedResult}, shared with the
     * code-behavior path).
     *
     * <p>
     * OnStop hooks do <b>not</b> fire, which is why this does not go through {@link #createFailureResult}: the fork
     * never started, and a main execution an OnStart hook blocks fires none either.
     *
     * <p>
     * The result carries {@code beforeGoal}, the transcript as it stood before the refused goal was added, not the
     * live buffer: a background fork persists its result's snapshot for {@code Task(resume=...)}, and a goal a guard
     * refused must not come back as conversation history on the next resume. A fresh fork's {@code beforeGoal} is
     * empty, so nothing is saved and its task id stays unresumable, as for any fork in which no iteration ran.
     */
    private SubagentExecutionResult createBlockedResult(LoopContext lc, ExecutionBlockedByHookException e,
            SessionSnapshot beforeGoal, int iterationCount, TokenUsage accumulatedTokens) {
        log.warn("Subagent '{}' not started: {}", lc.subagent().getName(), e.getMessage());
        // Terminal boundary so a background tail observes that the task ended (and why).
        stream(lc, "\n[ended: " + e.getMessage() + "]\n");
        return SubagentOnStartGate.blockedResult(e, beforeGoal, buildMetadata(lc, iterationCount, accumulatedTokens),
                estimateCost(lc, accumulatedTokens));
    }

    /**
     * Creates a failure result carrying the given {@link CompletionReason} and fires OnStop hooks with
     * {@code success=false}.
     *
     * @return A failure {@link SubagentExecutionResult}
     */
    private SubagentExecutionResult createFailureResult(LoopContext lc, String errorMessage, int iterationCount,
            TokenUsage accumulatedTokens, CompletionReason completionReason) {
        return createFailureResult(lc, errorMessage, iterationCount, accumulatedTokens, completionReason, null);
    }

    /**
     * {@link #createFailureResult(LoopContext, String, int, TokenUsage, CompletionReason)} with the transcript to hand
     * back named by the caller.
     *
     * @param snapshot
     *            the transcript the result carries, or {@code null} for the live buffer as it stands once the OnStop
     *            hooks have run — or, once a grace round has begun, for the transcript from before it
     */
    private SubagentExecutionResult createFailureResult(LoopContext lc, String errorMessage, int iterationCount,
            TokenUsage accumulatedTokens, CompletionReason completionReason, SessionSnapshot snapshot) {
        final ExecutionMetadata metadata = buildMetadata(lc, iterationCount, accumulatedTokens);
        final OnStopContext onStopContext = OnStopContext.builder().executorType(InvokerType.SUBAGENT)
                .invokerName(lc.subagent().getName()).hookRegistry(lc.hookRegistry())
                .executionEnvironment(lc.executionEnvironment()).executionCancellation(lc.coordinator.getSignal())
                .success(false).finalAnswer(errorMessage).metadata(metadata).executionAttributes(lc.executionAttributes)
                .build();
        hookExecutionManager.executeOnStop(onStopContext);
        // Terminal boundary so a background tail observes that the task ended (and why).
        stream(lc, "\n[ended: " + errorMessage + "]\n");
        final SessionSnapshot handedBack;
        if (snapshot != null) {
            handedBack = snapshot;
        } else if (lc.beforeGrace != null) {
            handedBack = lc.beforeGrace;
        } else {
            handedBack = lc.transcriptBuffer.toSnapshot();
        }
        return SubagentExecutionResult.failure(errorMessage, handedBack, metadata, completionReason,
                estimateCost(lc, accumulatedTokens));
    }

    /** Prices the accumulated token usage with the configured estimator and this execution's resolved model. */
    private Money estimateCost(LoopContext lc, TokenUsage accumulatedTokens) {
        // An absent model name is passed through as "" — the estimator returns zeroUsd for an unpriceable model.
        return costEstimator.estimate(lc.modelConfig.getName().orElse(""), accumulatedTokens);
    }

    private static ExecutionMetadata buildMetadata(LoopContext lc, int iterationCount, TokenUsage accumulatedTokens) {
        return ExecutionMetadata.builder().iterationCount(iterationCount).tokenUsage(accumulatedTokens)
                .timestamps(lc.startTime, Instant.now()).build();
    }

    @Override
    public String toString() {
        return "DefaultSubagentExecutor{gateway=" + gateway + '}';
    }

    /**
     * TRACE-01: one iteration's {@code ITERATION} span, opened under the span the fork's metadata points at (the
     * SUBAGENT span when the manager launched it), with the LLM call metadata and tool context that point at it.
     *
     * <p>
     * When that metadata names no parent (tracing off, or a caller that never enriched it) there is no span, and
     * {@link #metadata} and {@link #toolContext} are the loop context's own instances — the tracing-off path
     * allocates nothing.
     */
    private static final class IterationTrace {
        /** The iteration span, or {@code null} when there is no parent to nest under. */
        private final Tracer.Span span;
        private final LlmCallMetadata metadata;
        private final ToolContext toolContext;

        private IterationTrace(Tracer.Span span, LlmCallMetadata metadata, ToolContext toolContext) {
            this.span = span;
            this.metadata = metadata;
            this.toolContext = toolContext;
        }

        private static IterationTrace open(LoopContext lc, int iterationCount) {
            final Tracer.Span span = Spans.startChildOf(lc.tracer, lc.effectiveMetadata, SpanType.ITERATION,
                    "iteration#" + iterationCount, null).orElse(null);
            if (span == null) {
                return new IterationTrace(null, lc.effectiveMetadata, lc.toolContext);
            }
            span.setAttribute("iteration", String.valueOf(iterationCount));
            span.setAttribute("invokerType", InvokerType.SUBAGENT.name());
            span.setAttribute("subagent", lc.subagent().getName());
            final LlmCallMetadata metadata = Spans.enrichSafely(span, lc.effectiveMetadata);
            final ToolContext toolContext = metadata == lc.effectiveMetadata
                    ? lc.toolContext
                    : ToolContext.builder().putAll(lc.toolContext.getContext())
                            .put(ToolContextKeys.LLM_CALL_METADATA_KEY, metadata).build();
            return new IterationTrace(span, metadata, toolContext);
        }

        /**
         * Marks the iteration as unwound by {@code t}. A cancellation ends it interrupted — the fork reports
         * {@link CompletionReason#INTERRUPTED} for those, so its iteration must not read as an error — anything else,
         * an {@link Error} included, ends it in error.
         */
        private void fail(Throwable t, CancellationSignal cancellationSignal) {
            if (span == null) {
                return;
            }
            if (t instanceof LlmCallCancelledException || t instanceof CancelledExecutionException
                    || isCancelledOrInterrupted(cancellationSignal)) {
                span.interrupted();
            } else {
                span.error(t);
            }
        }

        /**
         * Marks the iteration as ended by the tail cancellation check. Set explicitly at that return rather than
         * inferred from the signal at close, so a cancel landing after an iteration completed normally (a final answer,
         * a stall stop) does not relabel it.
         */
        private void interrupted() {
            if (span != null) {
                span.interrupted();
            }
        }

        private void close() {
            if (span != null) {
                span.close();
            }
        }
    }

    /**
     * One fork's tool-call budget: how many calls it may run, how many it has run, and whether it asked for more than
     * it had. Without a limit it only counts. Touched only by the loop's thread — a parallel batch is admitted before
     * it is dispatched — so it needs no synchronization.
     */
    private static final class ToolCallQuota {
        /** The cap, or {@code null} for none. */
        private final Integer limit;
        private int used;
        private int refused;
        /** Whether a batch was refused because the token budget ran out, which then names the fork's stop. */
        private boolean tokensStopped;

        private ToolCallQuota(Integer limit) {
            this.limit = limit;
        }

        /** Admits up to {@code requested} calls and returns how many may run; the rest count as refused. */
        private int admit(int requested) {
            final int admitted = limit == null ? requested : Math.max(0, Math.min(requested, limit - used));
            used += admitted;
            refused += requested - admitted;
            return admitted;
        }

        private int used() {
            return used;
        }

        private int refused() {
            return refused;
        }

        /** Whether a call was refused for a cap — the fork asked for more than it may run. */
        private boolean isExceeded() {
            return refused > 0 || tokensStopped;
        }

        /** Records a batch refused because the response carrying it spent the token budget. */
        private void refuseForTokens() {
            tokensStopped = true;
        }

        /** The answer to a call the cap refused: an error result, so the transcript stays well formed and honest. */
        private static ToolUseResult refusal(ToolUse toolUse, Integer limit) {
            return ToolUseResult.error(toolUse.getId(), "Not run: this subagent's tool-call budget (" + limit
                    + " call(s)) is used up. Finish with what you have.");
        }
    }

    /**
     * One fork's consecutive failures per tool, which {@code SubagentExecutionPolicy#getMaxConsecutiveToolFailures}
     * bounds. A call that was run and came back an error adds one to its tool's count; one that came back a success
     * resets it. A tool whose count has reached the limit is not run again in this fork, so its count never resets
     * after
     * that. Calls answered without being run — refused for a budget, a cut response or by this class — count for
     * nothing. A permission denial does count: the call reached the tool pipeline and failed there.
     *
     * <p>
     * A failure the tool reported as not retryable ({@link ToolUseResult#isRetryable()}, set by
     * {@code ToolResult#notRetryable()}) blocks its tool at once, whatever the limit, and for the rest of the fork: the
     * block is sticky, so no success of the same tool — not even one later in the same batch — lifts it, and the
     * outcome does not depend on the order of a batch. It is per tool, not per input. Without a limit the mark is
     * inert, like the count.
     *
     * <p>
     * A refusal is an error result, so an iteration whose calls were all refused counts for the
     * {@link StalledIterationGuard}: a model that keeps calling a blocked tool ends {@code ERROR} once the guard trips.
     *
     * <p>
     * Without a limit it records nothing. Touched only by the loop's thread — outcomes are recorded after a batch has
     * been dispatched and collected — so it needs no synchronization.
     */
    private static final class ToolFailureStreaks {
        /** The limit, or {@code null} for none. */
        private final Integer limit;
        private final Map<String, Integer> consecutiveFailures = new HashMap<>();
        /** The tools that reported a failure as not retryable in this fork; never shrinks. */
        private final Set<String> notRetryable = new HashSet<>();

        private ToolFailureStreaks(Integer limit) {
            this.limit = limit;
        }

        private void record(String toolName, ToolUseResult result) {
            if (limit == null) {
                return;
            }
            if (result.isError()) {
                consecutiveFailures.merge(toolName, 1, Integer::sum);
                if (!result.isRetryable()) {
                    notRetryable.add(toolName);
                }
            } else {
                consecutiveFailures.remove(toolName);
            }
        }

        private boolean isBlocked(String toolName) {
            return limit != null
                    && (notRetryable.contains(toolName) || consecutiveFailures.getOrDefault(toolName, 0) >= limit);
        }

        private boolean blocksAny(List<ToolUse> toolUses) {
            if (limit == null || consecutiveFailures.isEmpty() && notRetryable.isEmpty()) {
                return false;
            }
            for (ToolUse toolUse : toolUses) {
                if (isBlocked(toolUse.getName())) {
                    return true;
                }
            }
            return false;
        }

        /** Why a blocked tool is blocked, for the log line. */
        private String blockCause(String toolName) {
            return notRetryable.contains(toolName)
                    ? "a failure it reported as not retryable"
                    : limit + " consecutive failure(s)";
        }

        /**
         * The answer to a call of a blocked tool: an error result, so the transcript stays well formed, that says why
         * the tool is not run.
         */
        private ToolUseResult refusal(ToolUse toolUse) {
            final String why = notRetryable.contains(toolUse.getName())
                    ? " reported its failure as not retryable in this task"
                    : " failed " + limit + " time(s) in a row in this task";
            return ToolUseResult.error(toolUse.getId(), "Not run: " + toolUse.getName() + why
                    + " and will not be run again. Continue without it, or finish with what you have.");
        }
    }

    /**
     * Immutable per-execution loop context. Bundles the values shared across the ReAct loop and tool execution so the
     * loop/tool helpers stay within the project's parameter-count budget. Built once per {@link #execute} call.
     */
    private static final class LoopContext {
        private final SubagentExecutionContext context;
        private final TranscriptBuffer transcriptBuffer;
        /** This fork's run identity — the honest answer to "who is running?" for a run that has no session. */
        private final ExecutionId executionId;
        private final SystemPromptParts systemPromptParts;
        private final LlmCallMetadata effectiveMetadata;
        private final LlmModel modelConfig;
        private final BudgetTracker budgetTracker;
        /**
         * This fork's own {@link StalledIterationGuard}: one instance per execution, like {@link #budgetTracker}, of
         * the guard the turn and a skill's loop use too.
         */
        private final StalledIterationGuard stalledIterationGuard;
        /**
         * The spawning turn's usage-quota guard, shared — not this fork's own. Every fork of one turn, nested or
         * parallel, holds the same instance.
         */
        private final QuotaGuard quotaGuard;
        private final Instant startTime;
        private final ToolContext toolContext;
        private final ToolRegistry sessionRegistry;
        private final InterruptCoordinator coordinator;
        private final Map<String, Object> executionAttributes;
        private final String goal;
        /** How many forks deep this execution is (from the request); handed to the tool dispatcher. */
        private final int forkDepth;
        /** The executor's tracer, snapshotted once per execution. */
        private final Tracer tracer;
        /** The executor's payload capture policy, snapshotted once per execution. */
        private final TracePayloadPolicy tracePayloadPolicy;
        /** The context engine this fork runs under: the spawner's when its policy inherits it, else the executor's. */
        private final ContextEngine contextEngine;
        /** The fork's execution policy, or {@code null} when it runs without one. */
        private final SubagentExecutionPolicy policy;
        /** The deadline the fork must be over by, or {@code null}. */
        private final ExecutionDeadline deadline;
        /** The fork's tool-call budget and the count of calls run — the one mutable part, like the budget tracker. */
        private final ToolCallQuota toolCalls;
        /** The fork's per-tool failure streaks, mutable like {@link #toolCalls}. */
        private final ToolFailureStreaks toolFailures;
        /**
         * The transcript from before the grace round's reminder, while the round runs and has not produced its answer;
         * {@code null} otherwise. A failure result hands it back instead of the live buffer. Written only on the loop's
         * thread.
         */
        private SessionSnapshot beforeGrace;

        private LoopContext(Builder b) {
            this.context = b.context;
            this.transcriptBuffer = b.transcriptBuffer;
            this.executionId = b.executionId;
            this.systemPromptParts = b.systemPromptParts;
            this.effectiveMetadata = b.effectiveMetadata;
            this.modelConfig = b.modelConfig;
            this.budgetTracker = b.budgetTracker;
            this.stalledIterationGuard = b.stalledIterationGuard;
            this.quotaGuard = b.quotaGuard;
            this.startTime = b.startTime;
            this.toolContext = b.toolContext;
            this.sessionRegistry = b.sessionRegistry;
            this.coordinator = b.coordinator;
            this.executionAttributes = b.executionAttributes;
            this.goal = b.goal;
            this.forkDepth = b.forkDepth;
            this.tracer = b.tracer;
            this.tracePayloadPolicy = b.tracePayloadPolicy;
            this.contextEngine = b.contextEngine;
            this.policy = b.policy;
            this.deadline = b.deadline;
            this.toolCalls = b.toolCalls;
            this.toolFailures = new ToolFailureStreaks(
                    b.policy == null ? null : b.policy.getMaxConsecutiveToolFailures().orElse(null));
        }

        /** Whether the fork's policy caps tokens and the fork has spent them. */
        private boolean tokenBudgetExhausted() {
            return policy != null
                    && policy.getMaxTokens().filter(max -> budgetTracker.tokens().getTotalTokens() >= max).isPresent();
        }

        /** Whether the fork's policy asks for a grace round at an iteration limit. */
        private boolean graceRoundEnabled() {
            return policy != null && policy.isGraceRound();
        }

        /** Whether the fork has a deadline and it has passed. */
        private boolean deadlineExpired() {
            return deadline != null && deadline.isExpired();
        }

        /** Whether the last call's input reached the policy's context target. */
        private boolean contextTargetReached(int lastPromptTokens) {
            return policy != null && lastPromptTokens > 0
                    && policy.getContextTargetTokens().filter(target -> lastPromptTokens >= target).isPresent();
        }

        private Subagent subagent() {
            return context.getSubagent();
        }

        private HookRegistry hookRegistry() {
            return context.getHookRegistry();
        }

        /**
         * The fork's own execution environment — the instance its tools reach, read back from the tool context it was
         * published in so the hooks and the tools cannot be handed different ones.
         */
        private ExecutionEnvironment executionEnvironment() {
            return toolContext.get(ToolContextKeys.EXECUTION_ENVIRONMENT).orElse(null);
        }

        private SubagentOutputSink outputSink() {
            return context.getOutputSink();
        }

        private int maxIterations() {
            return context.getSubagent().getMaxIterations();
        }

        private static Builder builder() {
            return new Builder();
        }

        private static final class Builder {
            private SubagentExecutionContext context;
            private TranscriptBuffer transcriptBuffer;
            private ExecutionId executionId;
            private SystemPromptParts systemPromptParts;
            private LlmCallMetadata effectiveMetadata;
            private LlmModel modelConfig;
            private BudgetTracker budgetTracker;
            private StalledIterationGuard stalledIterationGuard;
            private QuotaGuard quotaGuard;
            private Instant startTime;
            private ToolContext toolContext;
            private ToolRegistry sessionRegistry;
            private InterruptCoordinator coordinator;
            private Map<String, Object> executionAttributes;
            private String goal;
            private int forkDepth = 1;
            private Tracer tracer;
            private TracePayloadPolicy tracePayloadPolicy;
            private ContextEngine contextEngine;
            private SubagentExecutionPolicy policy;
            private ExecutionDeadline deadline;
            private ToolCallQuota toolCalls;

            private Builder contextEngine(ContextEngine contextEngine) {
                this.contextEngine = contextEngine;
                return this;
            }

            private Builder policy(SubagentExecutionPolicy policy) {
                this.policy = policy;
                return this;
            }

            private Builder deadline(ExecutionDeadline deadline) {
                this.deadline = deadline;
                return this;
            }

            private Builder toolCalls(ToolCallQuota toolCalls) {
                this.toolCalls = toolCalls;
                return this;
            }

            private Builder context(SubagentExecutionContext context) {
                this.context = context;
                return this;
            }

            private Builder transcriptBuffer(TranscriptBuffer transcriptBuffer) {
                this.transcriptBuffer = transcriptBuffer;
                return this;
            }

            private Builder executionId(ExecutionId executionId) {
                this.executionId = executionId;
                return this;
            }

            private Builder systemPromptParts(SystemPromptParts systemPromptParts) {
                this.systemPromptParts = systemPromptParts;
                return this;
            }

            private Builder effectiveMetadata(LlmCallMetadata effectiveMetadata) {
                this.effectiveMetadata = effectiveMetadata;
                return this;
            }

            private Builder modelConfig(LlmModel modelConfig) {
                this.modelConfig = modelConfig;
                return this;
            }

            private Builder budgetTracker(BudgetTracker budgetTracker) {
                this.budgetTracker = budgetTracker;
                return this;
            }

            private Builder stalledIterationGuard(StalledIterationGuard stalledIterationGuard) {
                this.stalledIterationGuard = stalledIterationGuard;
                return this;
            }

            private Builder quotaGuard(QuotaGuard quotaGuard) {
                this.quotaGuard = quotaGuard;
                return this;
            }

            private Builder startTime(Instant startTime) {
                this.startTime = startTime;
                return this;
            }

            private Builder toolContext(ToolContext toolContext) {
                this.toolContext = toolContext;
                return this;
            }

            private Builder sessionRegistry(ToolRegistry sessionRegistry) {
                this.sessionRegistry = sessionRegistry;
                return this;
            }

            private Builder coordinator(InterruptCoordinator coordinator) {
                this.coordinator = coordinator;
                return this;
            }

            private Builder executionAttributes(Map<String, Object> executionAttributes) {
                this.executionAttributes = executionAttributes;
                return this;
            }

            private Builder goal(String goal) {
                this.goal = goal;
                return this;
            }

            private Builder forkDepth(int forkDepth) {
                this.forkDepth = forkDepth;
                return this;
            }

            private Builder tracer(Tracer tracer) {
                this.tracer = tracer;
                return this;
            }

            private Builder tracePayloadPolicy(TracePayloadPolicy tracePayloadPolicy) {
                this.tracePayloadPolicy = tracePayloadPolicy;
                return this;
            }

            private LoopContext build() {
                return new LoopContext(this);
            }
        }
    }
}
