package at.aimon.core.toolinvocation;

import static at.aimon.core.agent.tool.execution.ToolExecutionResultConverter.toToolUseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolCallPath;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.hook.event.PermissionDeniedContext;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tools.EnvironmentNotices;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Shared per-tool invocation pipeline behind {@link DefaultToolCallDispatcher}, the gated
 * {@link at.aimon.core.agent.tool.ToolCallDispatcher ToolCallDispatcher} every gated loop dispatches through — the
 * main agent ({@code OrcaAgentExecutor}), a fork ({@code DefaultSubagentExecutor}) and a slash-command skill
 * ({@code LlmSkillExecutor}).
 *
 * <p>
 * A divergence in permission-hook ordering or interrupt-registrar lifecycle is security-relevant, so the sequence lives
 * in one testable component; the callers differ only through {@link ToolInvocationSpec} (invoker identity, permission
 * allow-list, and the path the call arrived by).
 *
 * <p>
 * The pipeline, per {@link ToolUse}:
 * <ol>
 * <li>Resolve the target tool's {@link InterruptBehavior} from the tool registry. Tools declaring
 * {@link InterruptBehavior#THREAD_INTERRUPT} or {@link InterruptBehavior#EXTERNALLY_TERMINATED} receive a fresh
 * {@link TerminatorRegistrar} from the coordinator injected into the enriched context; THREAD_INTERRUPT tools also get
 * a
 * {@code Thread.currentThread()::interrupt} terminator pre-registered. The registrar is always closed in a finally
 * block
 * so terminators cannot leak into the next tool call. Unknown tool names fall through to NON_INTERRUPTIBLE so the
 * registrar allocation is skipped and the downstream manager produces the canonical "tool not found" error.
 * <li>Fire PermissionRequest hooks; a deny short-circuits dispatch and notifies the PermissionDenied advisory chain.
 * <li>Consult the {@link SideEffectApprovalGate}, when one is configured, so a tool that declares a side effect is not
 * run until the user has approved it. Placed after the hook chain deliberately: a hook that would deny outright should
 * not first cost the user a prompt. A denial here notifies the same PermissionDenied chain.
 * <li>Fire PreTool hooks (which may rewrite the input) then execute the tool against the spec's registry and
 * allow-list,
 * unless a hook blocked it.
 * <li>Fire PostTool hooks (isolated so a hook failure never discards the real tool result), applying any rewritten
 * output.
 * <li>Drain the execution environment's {@linkplain ExecutionEnvironment#drainNotices() queued notices} once and hand
 * them on beside the result; a model-facing loop prepends them as {@code [environment]} lines
 * ({@link ToolDispatchResults#toToolUseResult}). This runs on every exit path,
 * denials and the catch-all included, because each of them hands a result to the model; and it runs last, so no hook
 * sees the notices or can rewrite them, while a notice a hook's own shell command caused still rides out here.
 * </ol>
 *
 * <p>
 * <b>Advisory feedback.</b> A hook that neither denies nor rewrites anything may still return
 * {@link HookResult#withFeedback(String) feedback} — a note meant for the model ("this path is deprecated", "the lint
 * run flagged 3 warnings"). Feedback emitted by the permission / PreTool / PostTool chains is appended to <em>that
 * tool's</em> result inside a {@code <system-reminder key="hook-feedback">} block rather than injected as a standalone
 * user message: the note is about one specific dispatch, so keeping it attached is what makes it attributable when a
 * batch runs several tools in parallel, and it avoids inserting a user turn between an assistant {@code tool_use} and
 * its {@code tool_result} (which providers reject). This holds on every exit path of the pipeline, including the
 * catch-all: a note collected before the tool blew up still rides out on the error result, because no later call site
 * replays it. Feedback carried by a <em>blocked</em> result is skipped here — that string is the deny reason and is
 * already surfaced verbatim in the error result.
 *
 * <p>
 * Chains outside this pipeline do not all deliver: {@code OnStart} renders its feedback as a user-role message
 * ({@code OrcaAgentExecutor}, {@code DefaultSubagentExecutor}), {@code PreCompact} routes its into the summarization
 * system prompt instead of the conversation, and the remaining firing sites — {@code PermissionDenied} (fired below),
 * {@code OnStop}, {@code OnSessionStart}, {@code OnSessionEnd}, {@code SubagentStart}, {@code SubagentStop},
 * {@code PostCompact} and {@code OnConfigReload} — discard their results, so feedback returned there currently reaches
 * nobody.
 *
 * <p>
 * This component is stateless and thread-safe: it holds only the two collaborators and derives everything else from the
 * per-invocation {@link ToolInvocationSpec}, so a single instance may serve concurrent tool dispatches.
 */
public final class SingleToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(SingleToolInvoker.class);

    private final ToolExecutionManager toolExecutionManager;
    private final HookExecutionManager hookExecutionManager;
    private final SideEffectApprovalGate approvalGate;

    /**
     * Creates an invoker with no side-effect approval gate: tool calls are governed by the hook chains and the
     * execution manager's allow-list only.
     *
     * @param toolExecutionManager
     *            the manager that looks up and runs the tool (must not be null)
     * @param hookExecutionManager
     *            the manager that fires permission / pre / post tool hooks (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public SingleToolInvoker(ToolExecutionManager toolExecutionManager, HookExecutionManager hookExecutionManager) {
        this(toolExecutionManager, hookExecutionManager, null);
    }

    /**
     * Creates an invoker that requires user approval for tool calls the gate does not exempt.
     *
     * @param toolExecutionManager
     *            the manager that looks up and runs the tool (must not be null)
     * @param hookExecutionManager
     *            the manager that fires permission / pre / post tool hooks (must not be null)
     * @param approvalGate
     *            the side-effect approval gate, or {@code null} to disable approval entirely
     * @throws NullPointerException
     *             if either manager is null
     */
    public SingleToolInvoker(ToolExecutionManager toolExecutionManager, HookExecutionManager hookExecutionManager,
            SideEffectApprovalGate approvalGate) {
        this.toolExecutionManager = Objects.requireNonNull(toolExecutionManager,
                "Tool execution manager cannot be null");
        this.hookExecutionManager = Objects.requireNonNull(hookExecutionManager,
                "Hook execution manager cannot be null");
        this.approvalGate = approvalGate;
    }

    /**
     * Runs the shared per-tool pipeline for the invocation described by {@code spec}.
     *
     * <p>
     * The hooks' advisory feedback and the environment's notices come back as lists beside the text rather than
     * spliced into it; {@link ToolDispatchResults#toToolUseResult} splices them for a loop that records the result for
     * the model.
     *
     * @param spec
     *            the fully-populated invocation description (must not be null)
     * @return the dispatch result (never null); errors are represented as error results rather than thrown
     */
    public ToolDispatchResult invoke(ToolInvocationSpec spec) {
        Objects.requireNonNull(spec, "spec cannot be null");
        // Advisory notes emitted by any of the three hook chains, collected on every exit path of dispatch. Confined
        // to this call — the invoker itself stays stateless under concurrent dispatch.
        final List<String> feedback = new ArrayList<>();
        final ToolUseResult result = dispatch(spec, feedback);
        // Drained from the one value every exit path of dispatch returns, so no branch can forget it.
        final List<String> notices = EnvironmentNotices.drain(environmentOf(spec), spec.getToolUse().getName(),
                result.getToolUseId());
        return ToolDispatchResults.of(result, notices, feedback);
    }

    /** Steps 1-5 of the pipeline; {@link #invoke} adds the environment's notices to what this returns. */
    private ToolUseResult dispatch(ToolInvocationSpec spec, List<String> feedback) {
        final ToolUse toolUse = spec.getToolUse();

        // Resolve the target tool's interrupt behaviour. Unknown tool names (e.g. a hallucinated name) fall through to
        // NON_INTERRUPTIBLE so the registrar allocation is skipped and the downstream toolExecutionManager produces the
        // canonical "tool not found" error on the normal path.
        final Optional<Tool> targetTool = spec.getSessionRegistry().findByName(toolUse.getName());
        final InterruptBehavior interruptBehavior = targetTool.map(Tool::getInterruptBehavior)
                .orElse(InterruptBehavior.NON_INTERRUPTIBLE);
        final boolean needsRegistrar = interruptBehavior == InterruptBehavior.THREAD_INTERRUPT
                || interruptBehavior == InterruptBehavior.EXTERNALLY_TERMINATED;

        final TerminatorRegistrar registrar = needsRegistrar ? spec.getCoordinator().newTerminatorRegistrar() : null;
        try {
            // Enrich tool context with current toolUseId, the allow-list this invocation is held to, and, when
            // applicable, the per-tool terminator registrar. The allow-list is published from the same value handed
            // to the execution manager below, so a tool that spawns work (Task, the workflow tools, a skill fork)
            // can bound that work by what its own caller may do without a second, drifting source for it. The
            // programmatic settings travel the same way: the loop's own, so a fork it spawns is ceilinged by them.
            final ToolContext.Builder enrichedBuilder = ToolContext.builder().putAll(spec.getToolContext().getContext())
                    .put(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY, toolUse.getId())
                    .put(ToolContextKeys.CALLER_ALLOWED_TOOLS, spec.getAllowedTools())
                    .put(ToolContextKeys.CALLER_PROGRAMMATIC, spec.getProgrammatic());
            if (registrar != null) {
                enrichedBuilder.put(InterruptToolKeys.TERMINATOR_REGISTRAR, registrar);
            }
            final ToolContext enrichedContext = enrichedBuilder.build();

            // THREAD_INTERRUPT: pre-register the current-thread interrupt terminator so a trip during the tool's
            // blocking call lands as Thread.interrupt(). Registration on an already-tripped coordinator fires the
            // terminator immediately on this thread per the registrar contract.
            if (registrar != null && interruptBehavior == InterruptBehavior.THREAD_INTERRUPT) {
                final Thread toolThread = Thread.currentThread();
                registrar.register(toolThread::interrupt);
            }

            // A tool the agent opens only to code (programmatic-only) is withheld from the direct offer, but its
            // name is in ExecuteCode's description, so the model can still ask for it directly. The refusal reads
            // the same source as the offer filter — the iteration's offer context — so the two cannot disagree.
            if (spec.getOrigin().getPath() == ToolCallPath.DIRECT && targetTool.isPresent()) {
                final ToolOfferContext offer = ToolOfferContext.of(enrichedContext);
                if (offer.getProgrammatic().getCodeOnly().contains(toolUse.getName())
                        && offer.isCodeOnly(targetTool.get().describe(offer))) {
                    log.warn("Direct call to code-only tool refused. toolUseId={}, tool={}", toolUse.getId(),
                            toolUse.getName());
                    return ToolUseResult.error(toolUse.getId(), "Tool '" + toolUse.getName()
                            + "' is offered only to code: call it from a program run by ExecuteCode");
                }
            }

            try {
                // Fire PermissionRequest hooks before PreTool. A deny short-circuits dispatch and notifies the
                // PermissionDenied advisory chain — PreTool/tool execution is skipped.
                final PermissionRequestContext permissionRequestContext = PermissionRequestContext.builder()
                        .invokerType(spec.getInvokerType()).invokerName(spec.getInvokerName()).origin(spec.getOrigin())
                        .hookRegistry(spec.getHookRegistry()).executionEnvironment(environmentOf(spec))
                        .executionCancellation(cancellationOf(spec)).toolName(toolUse.getName())
                        .toolInput(ToolInput.of(toolUse.getInput())).executionAttributes(spec.getExecutionAttributes())
                        .build();
                final List<HookResult> permissionResults = hookExecutionManager
                        .executePermissionRequest(permissionRequestContext);
                feedback.addAll(HookFeedback.collectAdvisory(permissionResults));
                if (hookExecutionManager.hasBlockedResult(permissionResults)) {
                    return denyByPermission(spec, permissionResults);
                }

                // Built-in side-effect approval. Runs after the permission chain on purpose: a hook that was going to
                // deny outright should not first cost the user a prompt. Unknown tool names skip it — there is no
                // declaration to judge, and the execution manager below emits the canonical "tool not found" error.
                // The gate judges the descriptor of the iteration's offer — the one the offer filter and the ceiling
                // read — and the path the call came by: a call from code is never put to the user.
                if (approvalGate != null && targetTool.isPresent()) {
                    final ToolDescriptor descriptor = targetTool.get().describe(ToolOfferContext.of(enrichedContext));
                    final Optional<String> refusal = approvalGate.denialReason(descriptor, toolUse, spec.getOrigin(),
                            enrichedContext);
                    if (refusal.isPresent()) {
                        return denyByApproval(spec, refusal.get());
                    }
                }

                // Execute PreTool hooks
                final PreToolContext preToolContext = PreToolContext.builder().executorType(spec.getInvokerType())
                        .invokerName(spec.getInvokerName()).origin(spec.getOrigin())
                        .hookRegistry(spec.getHookRegistry()).executionEnvironment(environmentOf(spec))
                        .executionCancellation(cancellationOf(spec)).toolUse(toolUse)
                        .iterationCount(spec.getIterationCount()).executionAttributes(spec.getExecutionAttributes())
                        .build();
                final List<HookResult> preToolResults = hookExecutionManager.executePreTool(preToolContext);
                feedback.addAll(HookFeedback.collectAdvisory(preToolResults));

                // PreTool hooks may have rewritten the input (e.g. redacting secrets). Use the accumulated input for
                // the
                // actual dispatch so the tool sees the post-hook view, while keeping toolUse.getId() stable.
                final ToolUse effectiveToolUse = hookExecutionManager.finalUpdatedInput(preToolResults)
                        .map(updated -> ToolUse.of(toolUse.getId(), toolUse.getName(), updated.toMap()))
                        .orElse(toolUse);

                // Check if tool is blocked by hooks
                ToolUseResult toolUseResult;
                if (hookExecutionManager.hasBlockedResult(preToolResults)) {
                    final List<String> blockReasons = hookExecutionManager.collectBlockedReasons(preToolResults);
                    final String combinedReason = String.join("; ", blockReasons);
                    log.warn("Tool use blocked by hook. toolUseId={}, reasons={}", toolUse.getId(), combinedReason);
                    toolUseResult = ToolUseResult.error(toolUse.getId(), "Tool blocked by hook: " + combinedReason);
                } else {
                    // Execute tool with enriched context; the allow-list is empty for the main agent (no restriction)
                    // and the subagent's declared allow-list otherwise.
                    final var toolExecutionResult = toolExecutionManager.execute(effectiveToolUse, enrichedContext,
                            spec.getToolRegistry(), spec.getAllowedTools());
                    toolUseResult = toToolUseResult(toolExecutionResult);
                }

                return applyPostToolHooks(spec, effectiveToolUse, toolUseResult, feedback);
            } catch (Exception e) {
                log.error("Error executing tool use: {}", toolUse.getName(), e);
                // Advisory notes already collected from the PermissionRequest / PreTool chains describe *this*
                // dispatch and are the only chance the model has to see them — there is no later call site that
                // replays them. They stay in the accumulator and ride out on the error result rather than being
                // dropped with the failure.
                return ToolUseResult.error(toolUse.getId(), "Tool execution error: " + e.getMessage());
            }
        } finally {
            // Close the per-tool registrar so the current-thread interrupt terminator (and any tool-registered
            // terminators) cannot leak into the next tool invocation. No-op when we never allocated one.
            if (registrar != null) {
                registrar.close();
            }
        }
    }

    /**
     * The execution environment the tool runs in, for the four tool-scoped hook chains (hook-environment design §4) and
     * the notice
     * drain. Null when the tool context carries none — the hooks then see an empty environment rather than the host,
     * and there is nothing to drain.
     */
    private static ExecutionEnvironment environmentOf(ToolInvocationSpec spec) {
        return ExecutionEnvironmentAccess.of(spec.getToolContext()).orElse(null);
    }

    /**
     * The execution's cancellation signal, as the tool itself would read it — so a hook's shell command stops on the
     * same interrupt the tool does. Null when the tool context carries none (the hook then runs untied, as before).
     * Passed as is to all four chains: the two that report what already happened (PostTool, PermissionDenied) stop
     * handing it out once it has tripped, in the context's own getter, so the rule is applied per hook.
     */
    private static CancellationSignal cancellationOf(ToolInvocationSpec spec) {
        return spec.getToolContext().get(InterruptToolKeys.CANCELLATION_SIGNAL).orElse(null);
    }

    /**
     * Records a permission denial: logs it, notifies the PermissionDenied advisory chain (isolated), and returns the
     * deny tool result.
     */
    private ToolUseResult denyByPermission(ToolInvocationSpec spec, List<HookResult> permissionResults) {
        final ToolUse toolUse = spec.getToolUse();
        final List<String> denyReasons = hookExecutionManager.collectBlockedReasons(permissionResults);
        final String combinedReason = String.join("; ", denyReasons);
        log.warn("Tool use denied by permission hook. toolUseId={}, reasons={}", toolUse.getId(), combinedReason);
        firePermissionDenied(spec, combinedReason);
        return ToolUseResult.error(toolUse.getId(), "Tool denied by permission hook: " + combinedReason);
    }

    /**
     * Records a denial by the side-effect approval gate. Notifies the same PermissionDenied advisory chain — a
     * declined approval is an authorization outcome, and a listener auditing denials must see both — but the error
     * carries the gate's own wording rather than the "permission hook" phrasing, which would misattribute it.
     */
    private ToolUseResult denyByApproval(ToolInvocationSpec spec, String reason) {
        final ToolUse toolUse = spec.getToolUse();
        log.warn("Tool use denied by side-effect approval. toolUseId={}, tool={}", toolUse.getId(), toolUse.getName());
        firePermissionDenied(spec, reason);
        return ToolUseResult.error(toolUse.getId(), reason);
    }

    /** Fires the PermissionDenied advisory chain, isolated so a listener failure never masks the denial itself. */
    private void firePermissionDenied(ToolInvocationSpec spec, String combinedReason) {
        final ToolUse toolUse = spec.getToolUse();
        try {
            final PermissionDeniedContext deniedContext = PermissionDeniedContext.builder()
                    .invokerType(spec.getInvokerType()).invokerName(spec.getInvokerName())
                    .hookRegistry(spec.getHookRegistry()).executionEnvironment(environmentOf(spec))
                    .executionCancellation(cancellationOf(spec)).toolName(toolUse.getName())
                    .toolInput(ToolInput.of(toolUse.getInput())).denyReason(combinedReason)
                    .executionAttributes(spec.getExecutionAttributes()).build();
            hookExecutionManager.executePermissionDenied(deniedContext);
        } catch (Exception e) {
            log.warn("PermissionDenied hook failed for tool '{}', toolUseId={}: {}", toolUse.getName(), toolUse.getId(),
                    e.getMessage());
        }
    }

    /**
     * Fires PostTool hooks and applies any rewritten output. Isolated so a hook failure never discards the real tool
     * result.
     *
     * @param feedback
     *            accumulator the chain's advisory feedback is appended to; {@link #invoke} hands it on beside the
     *            returned result
     */
    private ToolUseResult applyPostToolHooks(ToolInvocationSpec spec, ToolUse effectiveToolUse,
            ToolUseResult toolUseResult, List<String> feedback) {
        try {
            final PostToolContext postToolContext = PostToolContext.builder().executorType(spec.getInvokerType())
                    .invokerName(spec.getInvokerName()).origin(spec.getOrigin()).hookRegistry(spec.getHookRegistry())
                    .executionEnvironment(environmentOf(spec)).executionCancellation(cancellationOf(spec))
                    .toolUse(effectiveToolUse).toolUseResult(toolUseResult).iterationCount(spec.getIterationCount())
                    .executionAttributes(spec.getExecutionAttributes()).build();
            final List<HookResult> postToolResults = hookExecutionManager.executePostTool(postToolContext);
            feedback.addAll(HookFeedback.collectAdvisory(postToolResults));
            // PostTool hooks may have rewritten the output (e.g. masking sensitive data). The LLM sees the accumulated,
            // post-hook output.
            final var maybeUpdated = hookExecutionManager.finalUpdatedOutput(postToolResults);
            if (maybeUpdated.isPresent()) {
                // A hook rewrites what the model reads, not whether the failure is worth repeating: an error the tool
                // marked not retryable stays so (notRetryable is a no-op on a success), and a hook may mark one
                // itself. It cannot clear the tool's mark.
                final ToolUseResult rewritten = maybeUpdated.get().toToolUseResult(effectiveToolUse.getId());
                return toolUseResult.isRetryable() ? rewritten : rewritten.notRetryable();
            }
        } catch (Exception e) {
            log.warn("PostTool hook failed for tool '{}', toolUseId={}: {}", effectiveToolUse.getName(),
                    effectiveToolUse.getId(), e.getMessage());
        }
        return toolUseResult;
    }
}
