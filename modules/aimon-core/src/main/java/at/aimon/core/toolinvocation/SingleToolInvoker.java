package at.aimon.core.toolinvocation;

import static at.aimon.core.agent.tool.execution.ToolExecutionResultConverter.toToolUseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.ToolInput;
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
import at.aimon.core.tools.ToolContextKeys;

/**
 * Shared per-tool invocation pipeline used by both ReAct executors ({@code OrcaAgentExecutor} for the main agent and
 * {@code DefaultSubagentExecutor} for subagents).
 *
 * <p>
 * The two executors previously hand-cloned this delicate sequence, which is the highest parity/drift risk in the
 * codebase — a divergence in permission-hook ordering or interrupt-registrar lifecycle is security-relevant. Extracting
 * it here makes the pipeline a single, testable component; the callers differ only through {@link ToolInvocationSpec}
 * (invoker identity + permission allow-list).
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
     * @param spec
     *            the fully-populated invocation description (must not be null)
     * @return the tool use result (never null); errors are represented as {@link ToolUseResult#error} rather than
     *         thrown
     */
    public ToolUseResult invoke(ToolInvocationSpec spec) {
        Objects.requireNonNull(spec, "spec cannot be null");

        final ToolUse toolUse = spec.getToolUse();

        // Resolve the target tool's interrupt behaviour. Unknown tool names (e.g. a hallucinated name) fall through to
        // NON_INTERRUPTIBLE so the registrar allocation is skipped and the downstream toolExecutionManager produces the
        // canonical "tool not found" error on the normal path.
        final Optional<Tool> targetTool = spec.getSessionRegistry().findByName(toolUse.getName());
        final InterruptBehavior interruptBehavior = targetTool.map(Tool::getInterruptBehavior)
                .orElse(InterruptBehavior.NON_INTERRUPTIBLE);
        final boolean needsRegistrar = interruptBehavior == InterruptBehavior.THREAD_INTERRUPT
                || interruptBehavior == InterruptBehavior.EXTERNALLY_TERMINATED;

        // Advisory notes emitted by any of the three hook chains below, appended to the result this invocation returns.
        // Confined to this call — the invoker itself stays stateless under concurrent dispatch.
        final List<String> feedback = new ArrayList<>();

        final TerminatorRegistrar registrar = needsRegistrar ? spec.getCoordinator().newTerminatorRegistrar() : null;
        try {
            // Enrich tool context with current toolUseId and, when applicable, the per-tool terminator registrar.
            final ToolContext.Builder enrichedBuilder = ToolContext.builder().putAll(spec.getToolContext().getContext())
                    .put(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY, toolUse.getId());
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

            try {
                // Fire PermissionRequest hooks before PreTool. A deny short-circuits dispatch and notifies the
                // PermissionDenied advisory chain — PreTool/tool execution is skipped.
                final PermissionRequestContext permissionRequestContext = PermissionRequestContext.builder()
                        .invokerType(spec.getInvokerType()).invokerName(spec.getInvokerName())
                        .hookRegistry(spec.getHookRegistry()).environment(spec.getEnvironment())
                        .toolName(toolUse.getName()).toolInput(ToolInput.of(toolUse.getInput()))
                        .executionAttributes(spec.getExecutionAttributes()).build();
                final List<HookResult> permissionResults = hookExecutionManager
                        .executePermissionRequest(permissionRequestContext);
                feedback.addAll(HookFeedback.collectAdvisory(permissionResults));
                if (hookExecutionManager.hasBlockedResult(permissionResults)) {
                    return attachFeedback(denyByPermission(spec, permissionResults), feedback);
                }

                // Built-in side-effect approval. Runs after the permission chain on purpose: a hook that was going to
                // deny outright should not first cost the user a prompt. Unknown tool names skip it — there is no
                // declaration to judge, and the execution manager below emits the canonical "tool not found" error.
                if (approvalGate != null && targetTool.isPresent()) {
                    final Optional<String> refusal = approvalGate.denialReason(targetTool.get(), toolUse,
                            enrichedContext);
                    if (refusal.isPresent()) {
                        return attachFeedback(denyByApproval(spec, refusal.get()), feedback);
                    }
                }

                // Execute PreTool hooks
                final PreToolContext preToolContext = PreToolContext.builder().executorType(spec.getInvokerType())
                        .invokerName(spec.getInvokerName()).hookRegistry(spec.getHookRegistry())
                        .environment(spec.getEnvironment()).toolUse(toolUse).iterationCount(spec.getIterationCount())
                        .executionAttributes(spec.getExecutionAttributes()).build();
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

                return attachFeedback(applyPostToolHooks(spec, effectiveToolUse, toolUseResult, feedback), feedback);
            } catch (Exception e) {
                log.error("Error executing tool use: {}", toolUse.getName(), e);
                // Advisory notes already collected from the PermissionRequest / PreTool chains describe *this*
                // dispatch and are the only chance the model has to see them — there is no later call site that
                // replays them. Attach them to the error result rather than dropping them with the failure. The
                // result built here is fresh, so this cannot double-attach onto an already-decorated result.
                return attachFeedback(ToolUseResult.error(toolUse.getId(), "Tool execution error: " + e.getMessage()),
                        feedback);
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
                    .hookRegistry(spec.getHookRegistry()).environment(spec.getEnvironment()).toolName(toolUse.getName())
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
     *            accumulator the chain's advisory feedback is appended to; the caller attaches it to the returned
     *            result (this method cannot, because it returns early on the rewritten-output path)
     */
    private ToolUseResult applyPostToolHooks(ToolInvocationSpec spec, ToolUse effectiveToolUse,
            ToolUseResult toolUseResult, List<String> feedback) {
        try {
            final PostToolContext postToolContext = PostToolContext.builder().executorType(spec.getInvokerType())
                    .invokerName(spec.getInvokerName()).hookRegistry(spec.getHookRegistry())
                    .environment(spec.getEnvironment()).toolUse(effectiveToolUse).toolUseResult(toolUseResult)
                    .iterationCount(spec.getIterationCount()).executionAttributes(spec.getExecutionAttributes())
                    .build();
            final List<HookResult> postToolResults = hookExecutionManager.executePostTool(postToolContext);
            feedback.addAll(HookFeedback.collectAdvisory(postToolResults));
            // PostTool hooks may have rewritten the output (e.g. masking sensitive data). The LLM sees the accumulated,
            // post-hook output.
            final var maybeUpdated = hookExecutionManager.finalUpdatedOutput(postToolResults);
            if (maybeUpdated.isPresent()) {
                final var updated = maybeUpdated.get();
                return updated.isError()
                        ? ToolUseResult.error(effectiveToolUse.getId(), updated.getContent())
                        : ToolUseResult.success(effectiveToolUse.getId(), updated.getContent());
            }
        } catch (Exception e) {
            log.warn("PostTool hook failed for tool '{}', toolUseId={}: {}", effectiveToolUse.getName(),
                    effectiveToolUse.getId(), e.getMessage());
        }
        return toolUseResult;
    }

    /**
     * Returns {@code result} with the collected hook feedback appended as a {@code <system-reminder>} block, or
     * {@code result} unchanged when no hook had anything to say (or the block could not be rendered — feedback is
     * auxiliary and must never cost the model the tool result it is waiting on).
     */
    private static ToolUseResult attachFeedback(ToolUseResult result, List<String> feedback) {
        final Optional<String> block = HookFeedback.toReminderBlock(feedback);
        if (block.isEmpty()) {
            return result;
        }
        final String content = result.getContent();
        final String merged = content.isEmpty() ? block.get() : content + "\n\n" + block.get();
        final ToolUseResult rewritten = result.isError()
                ? ToolUseResult.error(result.getToolUseId(), merged)
                : ToolUseResult.success(result.getToolUseId(), merged);
        // Carry the sidecar across: it is invisible to the LLM but PostTool consumers and renderers rely on it.
        final Map<String, Object> renderPayload = result.getRenderPayload();
        return renderPayload == null ? rewritten : rewritten.withRenderPayload(renderPayload);
    }
}
