package at.aimon.core.subagent.behavior;

import java.util.Optional;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

/**
 * Per-execution support handed to a {@link SubagentBehavior} by the {@link SubagentBehaviorRunner}.
 *
 * <p>
 * Provides the cancellation signal (the parent's, so a parent cancel trips it) and result builders that produce
 * correctly-shaped {@link SubagentExecutionResult} value objects — an empty session snapshot and zero-cost
 * execution metadata ({@code iterationCount=0}, no tokens) with real timestamps — so a behavior author never has to
 * reconstruct those internals.
 *
 * <p>
 * <b>Cancellation is cooperative-only.</b> Unlike the ReAct path, the runner wires no per-tool interrupt terminator and
 * does not enforce the execution budget. A long or looping behavior must poll {@link #isCancelledOrInterrupted()} (or
 * {@link #cancellationSignal()}) itself and bound its own token use; it is not forcibly unwound the way the ReAct
 * loop's tools can be.
 *
 * <p>
 * <b>ReAct-parity inputs.</b> A behavior gets the same resolved inputs the ReAct path uses:
 * <ul>
 * <li>{@link #resolvedModel()} — the subagent's {@code model} alias merged with the default (NOT the raw
 * {@code context.getDefaultModel()}).
 * <li>{@link #scopedToolRegistry()} — the registry filtered to the subagent's allow-list (exposed, not enforced).
 * <li>{@link #effectiveLlmCallMetadata()} — subagent usage-attribution metadata.
 * <li>{@link #llmGateway()} — the retry/fallback-aware gateway configured exactly like the ReAct path.
 * </ul>
 *
 * <pre>
 * {@code
 * var gw = support.llmGateway().orElseThrow();
 * var model = support.resolvedModel();                 // subagent-resolved model
 * List<ToolDefinition> tools = support.scopedToolRegistry().findAll().stream()
 *         .map(Tool::getDefinition).toList();           // allow-list-scoped
 * // simple (resilient, NOT usage-attributed):
 * LlmResponse a = gw.sendMessage(systemPrompt, messages, tools, model);
 * // attributed (resilient + subagent usage attribution) via the parts overload:
 * SystemPromptParts parts = SystemPromptParts.of(List.of(SystemPromptPart.builder()
 *         .content(systemPrompt).staticness(Staticness.STATIC).kind("system").build()));
 * LlmResponse b = gw.sendMessage(parts, messages, tools, model, support.effectiveLlmCallMetadata());
 * }
 * </pre>
 *
 * @see SubagentBehavior
 */
public interface SubagentBehaviorSupport {

    /**
     * Returns the cancellation signal for this execution (the parent's signal; never null, defaults to a
     * never-cancelled signal). Poll it cooperatively in long-running behaviors. Prefer this non-clearing signal over
     * {@link #isCancelledOrInterrupted()} for a repeated loop condition, since that predicate consumes the thread
     * interrupt flag on each call.
     *
     * @return the cancellation signal (never null)
     */
    CancellationSignal cancellationSignal();

    /**
     * Convenience check mirroring the ReAct executor: {@code true} if the signal is cancelled or the current thread is
     * interrupted. Like the ReAct executor, this CONSUMES (clears) the thread interrupt flag when set, so a pooled
     * worker does not leak the interrupt into a later task; call it for control flow, not as a repeatable predicate.
     *
     * @return {@code true} if execution should stop
     */
    boolean isCancelledOrInterrupted();

    /**
     * Builds a success result with the given final answer (empty session snapshot, zero metadata, real
     * timestamps).
     *
     * @param finalAnswer
     *            the final answer (must not be null)
     * @return a success {@link SubagentExecutionResult}
     */
    SubagentExecutionResult success(String finalAnswer);

    /**
     * Builds a failure result with the given error message (empty session snapshot, zero metadata, real
     * timestamps).
     *
     * @param errorMessage
     *            the error message (must not be null)
     * @return a failure {@link SubagentExecutionResult}
     */
    SubagentExecutionResult failure(String errorMessage);

    /**
     * Returns the retry/fallback-aware {@link LlmCallGateway} for calling the model, configured exactly like the
     * subagent ReAct path's gateway (default retry policy, no fallback). Empty when the execution manager was built
     * without an {@link at.aimon.core.llm.LlmClient} (e.g. a custom {@code SubagentExecutor}).
     *
     * <p>
     * The gateway's {@code <TranscriptBuffer>} type parameter only matters for prompt-too-long recovery (unused by
     * the
     * default gateway); a behavior calls {@code sendMessage(...)} without constructing any {@code TranscriptBuffer}.
     *
     * @return the gateway, or empty if no LLM client is available
     */
    Optional<LlmCallGateway<TranscriptBuffer>> llmGateway();

    /**
     * Returns the LLM call metadata that attributes usage to THIS subagent (component = subagent name, feature =
     * {@code "subagent"}, parent component preserved), identical to what the ReAct path emits. Pass it to the gateway's
     * metadata-carrying {@code sendMessage} overload so a behavior's LLM calls are attributed to the subagent.
     *
     * @return the subagent-attributed metadata (never null)
     */
    LlmCallMetadata effectiveLlmCallMetadata();

    /**
     * Returns the model the subagent would run on — its own {@code model} alias merged with the default — identical to
     * what the ReAct path resolves. Prefer this over {@code context.getDefaultModel()} (the raw default), which does
     * NOT
     * honor the subagent's {@code model}.
     *
     * @return the subagent-resolved model (never null)
     */
    LlmModel resolvedModel();

    /**
     * Returns a {@link ToolRegistry} filtered to the subagent's allow-list (by tool name), or the full registry when
     * the
     * subagent declares no tool restrictions — the same allow-list scope the ReAct path enforces at dispatch.
     *
     * <p>
     * This is <b>exposed, not enforced</b>: a behavior is trusted Java code and can still reach every tool via
     * {@code context.getToolRegistry()}. Use this registry to match the ReAct subagent's tool scope.
     *
     * @return the allow-list-scoped registry (never null)
     */
    ToolRegistry scopedToolRegistry();
}
