package at.aimon.core.subagent.execution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.budget.BudgetDecision;
import at.aimon.core.agent.budget.BudgetTracker;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.NoOpCompactionGuard;
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
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
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
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
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
import at.aimon.core.toolinvocation.SingleToolInvoker;
import at.aimon.core.toolinvocation.ToolInvocationSpec;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.core.tools.todo.TodoWriteTool;

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
 * {@link ToolContext} keys (environment, LLM call metadata, artifact collector, cancellation signal, per-tool
 * {@code toolUseId}), fires PermissionRequest/PreTool/PostTool hooks, and isolates PostTool hook failures so a hook
 * exception never discards a real tool result.
 *
 * <p>
 * Thread-safe if the supplied {@link LlmCallGateway}, {@link ToolExecutionManager}, {@link HookExecutionManager} and
 * {@link CompactionGuard} are thread-safe.
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
 *             .hookRegistry(hookRegistry).environment(environment).build();
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

    /** {@code kind} label for the single system-prompt part backing the parts-aware gateway call. */
    private static final String SYSTEM_PROMPT_KIND = "subagent-instructions";

    /** Per-tool-result snippet cap (characters) in the streamed progress log; keeps the log readable. */
    private static final int STREAM_TOOL_RESULT_CAP = 4_000;

    private final LlmCallGateway<TranscriptBuffer> gateway;
    private final ToolExecutionManager toolExecutionManager;
    private final HookExecutionManager hookExecutionManager;
    private final CompactionGuard compactionGuard;

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
     * Creates a new DefaultSubagentExecutor backed by a raw {@link LlmClient}.
     *
     * <p>
     * The client is auto-wrapped in a pass-through {@link LlmCallGateway} (default retry policy, no fallback, no
     * prompt-too-long handler) and compaction is disabled ({@link NoOpCompactionGuard}). This preserves the legacy
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
                NoOpCompactionGuard.instance());
    }

    /**
     * Creates a new DefaultSubagentExecutor backed by a pre-configured {@link LlmCallGateway}.
     *
     * <p>
     * Compaction is disabled ({@link NoOpCompactionGuard}).
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
        this(gateway, toolExecutionManager, hookExecutionManager, NoOpCompactionGuard.instance());
    }

    /**
     * Primary constructor.
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
    public DefaultSubagentExecutor(LlmCallGateway<TranscriptBuffer> gateway, ToolExecutionManager toolExecutionManager,
            HookExecutionManager hookExecutionManager, CompactionGuard compactionGuard) {
        this.gateway = Objects.requireNonNull(gateway, "gateway cannot be null");
        this.toolExecutionManager = Objects.requireNonNull(toolExecutionManager,
                "Tool execution manager cannot be null");
        this.hookExecutionManager = Objects.requireNonNull(hookExecutionManager,
                "Hook execution manager cannot be null");
        this.compactionGuard = Objects.requireNonNull(compactionGuard, "Compaction guard cannot be null");
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

    @Override
    public SubagentExecutionResult execute(SubagentExecutionContext context, SubagentExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final Subagent subagent = context.getSubagent();

        // Build dynamic system prompt with environment information
        final String systemPrompt = buildDynamicSystemPrompt(subagent.getContent().getSystemPrompt(),
                context.getEnvironment());

        // Create conversation context, and with it the fork's run identity. Deliberately not a SessionId: one of those
        // means a durable record plus a cluster-unique lease, and a fork is entitled to neither — it used to mint one
        // anyway, which is the defect this removes. A resumed run keeps the identity of the run it continues (the
        // snapshot's label) so per-run tool state — the todo list, for one — survives the suspend/resume boundary
        // exactly as it did while that label was a minted session id.
        final TranscriptBuffer transcriptBuffer;
        final ExecutionId executionId;
        if (request.getPreviousSnapshot().isPresent()) {
            transcriptBuffer = TranscriptBuffer.fromSnapshot(request.getPreviousSnapshot().get());
            transcriptBuffer.setSystemPrompt(systemPrompt);
            executionId = ExecutionId.of(transcriptBuffer.getSessionId().value());
        } else {
            executionId = ExecutionId.generate("subagent:" + subagent.getName());
            transcriptBuffer = new TranscriptBuffer(forkTranscriptLabel(executionId), systemPrompt);
        }

        // CTX-05 parity: wrap the dynamic prompt as a single STATIC part so the gateway's parts-aware overload carries
        // LlmCallMetadata. concatenated() of a single part equals the prompt string stored in TranscriptBuffer.
        final SystemPromptParts systemPromptParts = SystemPromptParts.of(List.of(SystemPromptPart.builder()
                .content(systemPrompt).staticness(Staticness.STATIC).kind(SYSTEM_PROMPT_KIND).build()));

        // Resolve effective LLM call metadata (subagent attribution) — shared with the code-behavior path so the two
        // stay in lockstep.
        final LlmCallMetadata effectiveMetadata = SubagentLlmDefaults.effectiveMetadata(subagent.getName(),
                request.getLlmCallMetadata());

        final LlmModel modelConfig = buildModelConfig(subagent, context.getDefaultModel(),
                context.getModelOverride().orElse(null));
        final BudgetTracker budgetTracker = new BudgetTracker(
                request.getBudget().orElseGet(ExecutionBudget::unlimited));

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
            try {
                final ToolRegistry sessionRegistry = createSessionRegistry(context.getToolRegistry());
                // Per-execution artifact collector so artifact-producing subagent tools have the context key (parity
                // with the main-agent executor). Surfacing collected artifacts on SubagentExecutionResult is a
                // follow-up.
                final ArtifactCollector artifactCollector = new ArtifactCollector();
                final ToolContext toolContext = createToolContext(context, request, sessionRegistry,
                        coordinator.getSignal(), effectiveMetadata, executionId, artifactCollector);

                final LoopContext lc = LoopContext.builder().context(context).transcriptBuffer(transcriptBuffer)
                        .executionId(executionId).systemPromptParts(systemPromptParts)
                        .effectiveMetadata(effectiveMetadata).modelConfig(modelConfig).budgetTracker(budgetTracker)
                        .startTime(Instant.now()).toolContext(toolContext).sessionRegistry(sessionRegistry)
                        .coordinator(coordinator).goal(request.getGoal())
                        .executionAttributes(request.getExecutionAttributes()).build();

                return runReActLoop(lc);
            } finally {
                parentCancelReg.remove();
            }
        }
    }

    /**
     * Runs the subagent ReAct loop: fires OnStart hooks, then iterates LLM call → tool execution until a final answer,
     * an exhausted budget/iteration bound, a cancellation, or an error.
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

        try {
            // Add goal as user message, then fire OnStart hooks and feed any hook feedback back into the conversation.
            lc.transcriptBuffer.addUserMessage(lc.goal);
            fireOnStart(lc);

            while (iterationCount < lc.maxIterations()) {
                // Honour a cancellation that landed before this iteration starts. The helper also clears the thread
                // interrupt flag so a pooled background thread does not carry it into its next task.
                if (isCancelledOrInterrupted(cancellationSignal)) {
                    return createInterruptedResult(lc, iterationCount, accumulatedTokens);
                }

                // Consult the budget BEFORE starting a new iteration so an exhausted budget does not incur an LLM call.
                if (lc.budgetTracker.check() == BudgetDecision.STOP) {
                    return createBudgetStopResult(lc, iterationCount, accumulatedTokens);
                }

                iterationCount++;
                lc.budgetTracker.recordIteration();
                stream(lc, "\n[iteration " + iterationCount + "]\n");

                // AUTO compaction gate. NoOpCompactionGuard returns NONE, leaving behavior unchanged.
                applyCompactionGate(lc, iterationCount);

                // Query available tools each iteration so newly activated deferred tools are included, and withhold
                // whatever the tool execution manager would refuse anyway — a fork shown a tool above the ceiling
                // would spend an iteration picking it and reading the refusal. The ceiling is read from the manager
                // rather than configured here on purpose: the filter and the refusal then cannot disagree.
                final SideEffectLevel ceiling = toolExecutionManager.getMaxSideEffectLevel();
                final List<ToolDefinition> availableTools = lc.sessionRegistry.findAll().stream()
                        .filter(tool -> ceiling.permits(tool.getSideEffectLevel())).map(Tool::getDefinition).toList();

                // Send message to LLM via the gateway (retry/fallback aware, metadata-carrying parts overload). The
                // LlmCancellation lets a trip abort the in-flight call; clear the per-call abort lever in a finally
                // (hygiene) so a trip landing between calls (e.g. while tools run) never fires a completed call's
                // stale abort.
                final LlmResponse response;
                try {
                    response = gateway.sendMessage(lc.systemPromptParts, lc.transcriptBuffer.getMessages(),
                            availableTools, lc.modelConfig, lc.effectiveMetadata, llmCancellation);
                } finally {
                    llmCancellation.clearAbort();
                }

                accumulatedTokens = accumulatedTokens.add(response.getTokenUsage());
                lc.budgetTracker.recordTokens(response.getTokenUsage());

                // Cost mirror: when a cost estimator is wired, price THIS call and fold it into the tracker's
                // cost axis so a request-level ExecutionBudget.maxCostUsd stops the loop in-flight — the result-time
                // estimate alone would only report the total after the loop already exited. USD-only by contract,
                // like OrcaAgentExecutor's cost block: a non-USD estimate is not enforced as a STOP.
                if (costEstimator != CostEstimator.NOOP) {
                    final Money callCost = costEstimator.estimate(lc.modelConfig.getName().orElse(""),
                            response.getTokenUsage());
                    if (Money.USD.equals(callCost.getCurrency())) {
                        lc.budgetTracker.recordCost(callCost);
                    }
                }

                // If no tool uses, we have the final answer
                if (!response.hasToolUses()) {
                    lc.transcriptBuffer.addMessage(Message.assistant(response.getTextContent()));
                    return createSuccessResult(lc, response.getTextContent(), iterationCount, accumulatedTokens);
                }

                // Add assistant response with tool uses to context
                lc.transcriptBuffer.addMessage(Message.assistant(response.getTextContent(), response.getToolUses()));

                // Stream the assistant's reasoning preamble (the text accompanying tool calls) for progress visibility.
                final String preamble = response.getTextContent();
                if (preamble != null && !preamble.isBlank()) {
                    stream(lc, preamble + "\n");
                }

                // Execute tools with PermissionRequest/PreTool/PostTool hooks per tool
                final List<ToolUseResult> toolUseResults = executeToolUses(lc, response.getToolUses(), iterationCount);
                lc.transcriptBuffer.addMessage(Message.toolUseResults(toolUseResults));

                // Iteration-tail cancellation check: a tool may have cooperatively tripped the signal, or the parent
                // may have cancelled between tool return and the next LLM call.
                if (isCancelledOrInterrupted(cancellationSignal)) {
                    return createInterruptedResult(lc, iterationCount, accumulatedTokens);
                }
            }

            // Max iterations exceeded
            throw new MaxIterationsExceededException(lc.maxIterations());

        } catch (CancelledExecutionException e) {
            log.debug("Subagent ReAct loop cancelled: {}", e.getMessage());
            return createInterruptedResult(lc, iterationCount, accumulatedTokens);
        } catch (MaxIterationsExceededException e) {
            return createFailureResult(lc, e.getMessage(), iterationCount, accumulatedTokens,
                    CompletionReason.MAX_ITERATIONS);
        } catch (ContextWindowExceededException e) {
            return createFailureResult(lc, "Context window exceeded: " + e.getMessage(), iterationCount,
                    accumulatedTokens, CompletionReason.ERROR);
        } catch (LlmCallCancelledException e) {
            // The gateway/provider actively aborted the in-flight LLM call because the execution's signal tripped.
            // This extends LlmClientException, so it MUST be caught BEFORE the generic LlmClientException handler —
            // otherwise a cancellation would be misreported as an LLM failure. Route it to the interrupted result like
            // every other cancellation.
            log.debug("Subagent LLM call cancelled in-flight: {}", e.getMessage());
            return createInterruptedResult(lc, iterationCount, accumulatedTokens);
        } catch (LlmClientException e) {
            return createFailureResult(lc, "LLM client error: " + e.getMessage(), iterationCount, accumulatedTokens,
                    CompletionReason.ERROR);
        } catch (Exception e) {
            // A blocking call broken by a cancellation-driven thread interrupt surfaces here — map it back to a clean
            // interrupted result rather than a generic failure.
            if (isCancelledOrInterrupted(cancellationSignal)) {
                log.debug("Subagent execution unwound by cancellation: {}", e.getMessage());
                return createInterruptedResult(lc, iterationCount, accumulatedTokens);
            }
            log.error("Unexpected subagent execution error", e);
            return createFailureResult(lc, "Subagent execution failed: " + e.getMessage(), iterationCount,
                    accumulatedTokens, CompletionReason.ERROR);
        }
    }

    /**
     * Fires OnStart hooks and appends any advisory hook feedback to the conversation as a user message.
     *
     * <p>
     * The note is wrapped in a {@code <system-reminder>} block so the model does not read it as genuine user intent,
     * matching how {@code OrcaAgentExecutor} surfaces the same chain.
     *
     * @param lc
     *            the loop context
     */
    private void fireOnStart(LoopContext lc) {
        final OnStartContext onStartContext = OnStartContext.builder().executorType(InvokerType.SUBAGENT)
                .invokerName(lc.subagent().getName()).hookRegistry(lc.hookRegistry()).environment(lc.environment())
                .userMessage(lc.goal).executionAttributes(lc.executionAttributes).build();
        final List<HookResult> onStartResults = hookExecutionManager.executeOnStart(onStartContext);
        HookFeedback.toReminderBlock(HookFeedback.collectAdvisory(onStartResults))
                .ifPresent(lc.transcriptBuffer::addUserMessage);
    }

    /**
     * Evaluates the AUTO compaction guard for the upcoming LLM call and applies its decision (BLOCK throws, COMPACT and
     * WARN log, NONE is a no-op).
     *
     * @param lc
     *            the loop context
     * @param iterationCount
     *            the current iteration count (for logging)
     * @throws ContextWindowExceededException
     *             if the guard blocks the iteration
     */
    private void applyCompactionGate(LoopContext lc, int iterationCount) {
        // Hand the guard this fork's run identity. Without it the compaction engine has only the transcript label to
        // go on, and that label is a SessionId wrapping this very execution id — so a PreCompact hook would be told the
        // fork had a session, and told an execution id as its name. The two are tied together at both entries into
        // execute(): a fresh fork derives the label from the id (forkTranscriptLabel), a resume derives the id back out
        // of the restored label (ExecutionId.of), which is why the round trip has to survive the snapshot.
        final CompactionDecision decision = compactionGuard.maybeCompact(lc.transcriptBuffer, lc.modelConfig,
                lc.hookRegistry(), lc.environment(), lc.executionId);
        switch (decision.getAction()) {
            case BLOCK :
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
     * @return the tool context (never null)
     */
    private ToolContext createToolContext(SubagentExecutionContext context, SubagentExecutionRequest request,
            ToolRegistry sessionRegistry, CancellationSignal cancellationSignal, LlmCallMetadata effectiveMetadata,
            ExecutionId executionId, ArtifactCollector artifactCollector) {
        final ToolContext.Builder builder = ToolContext.builder();
        builder.put(ToolContextKeys.AGENT_RUNTIME_ID, context.getAgentRuntimeId());
        // No SESSION_ID: this run is not a session's turn. The fork used to publish the id it had minted for its own
        // transcript, which read to every tool exactly like a user session while granting none of what one implies.
        // Its run identity goes here instead — unique per fork, so per-run tool state stays partitioned rather than
        // bleeding into the parent's, and never forwarded, so a nested fork cannot mistake it for an inherited session.
        builder.put(ToolContextKeys.EXECUTION_ID, executionId);
        // Which is why the invoking session is published beside it: an execution id names nobody and so grants
        // nothing, so a policy looking for the user's decision must ask under the id the user actually answered for.
        // Absent for runs nobody asked for (scheduled tasks), which inherit nothing — hence the guard,
        // ToolContext.Builder#put rejecting nulls.
        request.getInvokingSessionId().ifPresent(id -> builder.put(ToolContextKeys.INVOKING_SESSION_ID, id));

        final Environment environment = context.getEnvironment();
        if (environment != null) {
            builder.put(ToolContextKeys.ENVIRONMENT_KEY, environment);
        }
        request.getPrincipal().ifPresent(p -> builder.put(ToolContextKeys.PRINCIPAL, p));
        builder.put(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY, request.getExecutionAttributes());
        builder.put(ToolContextKeys.LLM_CALL_METADATA_KEY, effectiveMetadata);
        builder.put(ToolContextKeys.ARTIFACT_COLLECTOR, artifactCollector);
        builder.put(TodoWriteTool.CONTEXT_ID_KEY, executionId.value());

        // PAR-05: inject a thread-safe, execution-scoped read-tracking set (parity with the main-agent executor). Backs
        // EditTool's read-before-edit guard and lets parallel CONCURRENT_SAFE Read tools record reads without racing.
        builder.put(ReadTool.READ_FILES_KEY, ConcurrentHashMap.newKeySet());

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
     * Builds a dynamic system prompt by injecting environment details.
     *
     * @param baseSystemPrompt
     *            The base system prompt from Subagent configuration
     * @param environment
     *            The runtime environment (may be null)
     * @return The system prompt with dynamically injected environment information
     */
    private String buildDynamicSystemPrompt(String baseSystemPrompt, Environment environment) {
        final StringBuilder promptBuilder = new StringBuilder(baseSystemPrompt);

        if (environment != null) {
            promptBuilder.append("\n\n");
            promptBuilder.append("Here is useful information about the environment you are running in:\n\n");
            promptBuilder.append("**Environment:**\n");
            promptBuilder.append("```\n");
            promptBuilder.append("Working directory: ").append(environment.getWorkingDirectory()).append('\n');
            promptBuilder.append("Platform: ").append(environment.getPlatform()).append('\n');
            promptBuilder.append("OS Version: ").append(environment.getOsVersion()).append('\n');
            promptBuilder.append("```");
        }

        return promptBuilder.toString();
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
     *            The per-invocation model alias (nullable/blank = ignored), highest priority when present
     * @return An LlmModel
     */
    private LlmModel buildModelConfig(Subagent subagent, LlmModel defaultConfig, String modelOverride) {
        return SubagentLlmDefaults.resolveModel(subagent, defaultConfig, modelOverride);
    }

    /**
     * Executes multiple tool uses and returns their results.
     *
     * @param lc
     *            the loop context (carries context, tool context, coordinator, registry, attributes)
     * @param toolUses
     *            The list of tool uses to execute
     * @param iterationCount
     *            The current iteration count
     * @return The list of tool use results (never null)
     */
    private List<ToolUseResult> executeToolUses(LoopContext lc, List<ToolUse> toolUses, int iterationCount) {
        // PAR-04: delegate to the dispatcher. The start/completed callbacks serialize per-tool progress into the output
        // sink (a no-op for foreground; a TaskOutputStore append for background tasks). onCompleted may fire on a
        // shared
        // worker thread under the parallel path, so the sink must be thread-safe — the store's append is. The gate uses
        // the per-session registry (the same source executeSingleTool reads interrupt behaviour from). With the
        // default sequential dispatcher this is 1:1 equivalent to the previous for-loop.
        return parallelToolDispatcher.dispatch(toolUses, lc.sessionRegistry,
                toolUse -> executeSingleTool(lc, toolUse, iterationCount),
                toolUse -> stream(lc, "\n→ " + toolUse.getName() + "\n"),
                (toolUse, result) -> stream(lc, formatStreamedToolResult(toolUse, result)));
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
     * @param lc
     *            the loop context
     * @param toolUse
     *            the tool use request from the LLM
     * @param iterationCount
     *            the current iteration count
     * @return The tool use result (never null)
     */
    private ToolUseResult executeSingleTool(LoopContext lc, ToolUse toolUse, int iterationCount) {
        final Subagent subagent = lc.subagent();
        // Delegate the interrupt-registrar + PermissionRequest/PreTool/execute/PostTool sequence to the shared
        // pipeline. The subagent variance is the SUBAGENT invoker identity and its declared allow-list.
        final ToolInvocationSpec spec = ToolInvocationSpec.builder().invokerType(InvokerType.SUBAGENT)
                .invokerName(subagent.getName()).hookRegistry(lc.hookRegistry()).environment(lc.environment())
                .executionAttributes(lc.executionAttributes).toolRegistry(lc.context.getToolRegistry())
                .sessionRegistry(lc.sessionRegistry).allowedTools(subagent.getAllowedTools())
                .coordinator(lc.coordinator).toolContext(lc.toolContext).toolUse(toolUse).iterationCount(iterationCount)
                .build();
        return singleToolInvoker.invoke(spec);
    }

    /**
     * Creates a success result, firing OnStop hooks with {@code success=true}.
     */
    private SubagentExecutionResult createSuccessResult(LoopContext lc, String finalAnswer, int iterationCount,
            TokenUsage accumulatedTokens) {
        final ExecutionMetadata metadata = buildMetadata(lc, iterationCount, accumulatedTokens);
        final OnStopContext onStopContext = OnStopContext.builder().executorType(InvokerType.SUBAGENT)
                .invokerName(lc.subagent().getName()).hookRegistry(lc.hookRegistry()).environment(lc.environment())
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
     * Creates an interrupted result. Represented as a failed {@link SubagentExecutionResult} carrying the canonical
     * "Execution interrupted" message and {@link CompletionReason#INTERRUPTED}; OnStop hooks fire with
     * {@code success=false}.
     */
    private SubagentExecutionResult createInterruptedResult(LoopContext lc, int iterationCount,
            TokenUsage accumulatedTokens) {
        log.info("Subagent execution interrupted: iterations={}, tokens={}", iterationCount,
                accumulatedTokens.getTotalTokens());
        return createFailureResult(lc, "Execution interrupted", iterationCount, accumulatedTokens,
                CompletionReason.INTERRUPTED);
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
     * Creates a failure result carrying the given {@link CompletionReason} and fires OnStop hooks with
     * {@code success=false}.
     *
     * @return A failure {@link SubagentExecutionResult}
     */
    private SubagentExecutionResult createFailureResult(LoopContext lc, String errorMessage, int iterationCount,
            TokenUsage accumulatedTokens, CompletionReason completionReason) {
        final ExecutionMetadata metadata = buildMetadata(lc, iterationCount, accumulatedTokens);
        final OnStopContext onStopContext = OnStopContext.builder().executorType(InvokerType.SUBAGENT)
                .invokerName(lc.subagent().getName()).hookRegistry(lc.hookRegistry()).environment(lc.environment())
                .success(false).finalAnswer(errorMessage).metadata(metadata).executionAttributes(lc.executionAttributes)
                .build();
        hookExecutionManager.executeOnStop(onStopContext);
        // Terminal boundary so a background tail observes that the task ended (and why).
        stream(lc, "\n[ended: " + errorMessage + "]\n");
        return SubagentExecutionResult.failure(errorMessage, lc.transcriptBuffer.toSnapshot(), metadata,
                completionReason, estimateCost(lc, accumulatedTokens));
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
        private final Instant startTime;
        private final ToolContext toolContext;
        private final ToolRegistry sessionRegistry;
        private final InterruptCoordinator coordinator;
        private final Map<String, Object> executionAttributes;
        private final String goal;

        private LoopContext(Builder b) {
            this.context = b.context;
            this.transcriptBuffer = b.transcriptBuffer;
            this.executionId = b.executionId;
            this.systemPromptParts = b.systemPromptParts;
            this.effectiveMetadata = b.effectiveMetadata;
            this.modelConfig = b.modelConfig;
            this.budgetTracker = b.budgetTracker;
            this.startTime = b.startTime;
            this.toolContext = b.toolContext;
            this.sessionRegistry = b.sessionRegistry;
            this.coordinator = b.coordinator;
            this.executionAttributes = b.executionAttributes;
            this.goal = b.goal;
        }

        private Subagent subagent() {
            return context.getSubagent();
        }

        private HookRegistry hookRegistry() {
            return context.getHookRegistry();
        }

        private Environment environment() {
            return context.getEnvironment();
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
            private Instant startTime;
            private ToolContext toolContext;
            private ToolRegistry sessionRegistry;
            private InterruptCoordinator coordinator;
            private Map<String, Object> executionAttributes;
            private String goal;

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

            private LoopContext build() {
                return new LoopContext(this);
            }
        }
    }
}
