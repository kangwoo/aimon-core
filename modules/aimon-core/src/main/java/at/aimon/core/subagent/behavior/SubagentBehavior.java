package at.aimon.core.subagent.behavior;

import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

/**
 * Strategy that implements a subagent's behavior in Java code instead of the LLM ReAct loop.
 *
 * <p>
 * A code-behavior subagent is a <b>pair</b> registered under one name: a {@link at.aimon.core.subagent.Subagent} data
 * entry (for discovery, description and the tool allow-list — registered via the code
 * {@link at.aimon.core.subagent.SubagentRegistry}) and a {@code SubagentBehavior} (for execution — registered in a
 * {@link SubagentBehaviorRegistry}). When the execution manager dispatches a subagent whose name has a registered
 * behavior, it runs the behavior instead of the ReAct loop; otherwise the data subagent runs the unchanged loop.
 *
 * <p>
 * <b>Contract parity with the ReAct path.</b> An implementation receives the SAME immutable
 * {@link SubagentExecutionContext} (subagent value object, tool/hook registries, environment, parent cancellation
 * signal, knowledge store/scope, tool-context enrichers) and {@link SubagentExecutionRequest} (goal, principal,
 * attributes, LLM metadata, budget) that the {@link at.aimon.core.subagent.execution.SubagentExecutor} receives, and
 * MUST return a {@link SubagentExecutionResult} — the same value object the ReAct path returns. For the result fields
 * consumers actually read ({@code TaskTool} and background dispatch use status, summary and metadata) the two paths are
 * interchangeable; note, however, that a code behavior's result carries an EMPTY session snapshot (see
 * {@link SubagentBehaviorSupport#success(String)}), so unlike a ReAct result it is not usable for conversation resume
 * or transcript persistence.
 *
 * <p>
 * An implementation is free to use the context's collaborators — {@code context.getToolRegistry()},
 * {@code context.getEnvironment()}, {@code context.getDefaultModel()} — but the default expectation is deterministic
 * Java logic. To call the model, the {@code support} facade exposes the retry/fallback-aware
 * {@link SubagentBehaviorSupport#llmGateway()} (configured like the ReAct path) and
 * {@link SubagentBehaviorSupport#effectiveLlmCallMetadata()} for subagent usage attribution. The facade also provides
 * the
 * cancellation signal and correctly-shaped result builders so the body need not reconstruct session snapshots or
 * execution metadata.
 *
 * <p>
 * <b>Lifecycle.</b> The dispatcher applies {@code SubagentStart}/{@code SubagentStop} hooks AROUND this call and shapes
 * any thrown exception into a failure result — so an implementation only replaces the LLM loop body. Note that the
 * loop-internal {@code OnStart}/{@code OnStop} hooks do NOT fire on the code path (they are tied to the ReAct
 * conversation; their feedback would have nowhere to go). Like {@code Tool.execute()}, implementations SHOULD return a
 * failure via {@link SubagentBehaviorSupport#failure(String)} rather than throwing, though the runner maps any thrown
 * exception to a failure result as a safety net.
 *
 * @see SubagentBehaviorRegistry
 * @see SubagentBehaviorSupport
 */
@FunctionalInterface
public interface SubagentBehavior {

    /**
     * Executes the subagent's behavior.
     *
     * @param context
     *            the immutable execution context (subagent, registries, environment, cancellation, knowledge)
     * @param request
     *            the immutable execution request (goal, principal, attributes, metadata, budget)
     * @param support
     *            per-execution support: cancellation signal and result builders
     * @return the execution result (must not be null; a null return is mapped to a failure result by the runner)
     */
    SubagentExecutionResult execute(SubagentExecutionContext context, SubagentExecutionRequest request,
            SubagentBehaviorSupport support);
}
