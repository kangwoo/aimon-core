package at.aimon.core.subagent.execution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.exception.ExecutionBlockedByHookException;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.Money;

/**
 * The {@code onStart} gate every fork passes before it does any work, shared by the two ways a fork runs: the ReAct
 * loop ({@link DefaultSubagentExecutor}) and a registered code behavior
 * ({@code at.aimon.core.subagent.behavior.SubagentBehaviorRunner}). One definition of what is fired, what counts as a
 * block and what a blocked fork hands its parent, so the two paths cannot drift.
 *
 * <p>
 * A block is a hook that exited 2 or, unless it declares {@code failOpen: true}, one whose command could not be run at
 * all. The same rule as {@code OrcaAgentExecutor#checkOnStartHooks}, which stops the turn.
 *
 * <p>
 * Stateless; all methods are static.
 */
public final class SubagentOnStartGate {

    private static final String HOOK_EVENT = "OnStart";

    private SubagentOnStartGate() {
    }

    /**
     * Builds the context a fork's {@code onStart} hooks receive.
     *
     * @param context
     *            the fork's execution context (must not be null)
     * @param goal
     *            the goal the fork was given, reported as the user message (must not be null)
     * @param executionAttributes
     *            the fork's execution attributes (nullable)
     * @param executionEnvironment
     *            the environment the fork runs in (nullable — a shell hook then reports that it has none)
     * @return the context (never null)
     */
    public static OnStartContext context(SubagentExecutionContext context, String goal,
            Map<String, Object> executionAttributes, ExecutionEnvironment executionEnvironment) {
        return context(context, goal, executionAttributes, executionEnvironment, null);
    }

    /**
     * Builds the context a fork's {@code onStart} hooks receive, carrying the fork's cancellation signal so a hook's
     * shell command stops when the execution is interrupted (EE-80).
     *
     * @param context
     *            the fork's execution context (must not be null)
     * @param goal
     *            the goal the fork was given, reported as the user message (must not be null)
     * @param executionAttributes
     *            the fork's execution attributes (nullable)
     * @param executionEnvironment
     *            the environment the fork runs in (nullable — a shell hook then reports that it has none)
     * @param executionCancellation
     *            the execution's cancellation signal (nullable — a fork that has none, such as a code-behavior fork,
     *            leaves its hook commands bounded by their own timeout)
     * @return the context (never null)
     */
    public static OnStartContext context(SubagentExecutionContext context, String goal,
            Map<String, Object> executionAttributes, ExecutionEnvironment executionEnvironment,
            CancellationSignal executionCancellation) {
        Objects.requireNonNull(context, "context cannot be null");
        return OnStartContext.builder().executorType(InvokerType.SUBAGENT).invokerName(context.getSubagent().getName())
                .hookRegistry(context.getHookRegistry()).userLocale(context.getUserLocale())
                .executionEnvironment(executionEnvironment).executionCancellation(executionCancellation)
                .userMessage(goal).executionAttributes(executionAttributes).build();
    }

    /**
     * Fires the {@code onStart} hooks and throws if any of them blocks the fork.
     *
     * @param hookExecutionManager
     *            the manager that runs the chain (must not be null)
     * @param onStartContext
     *            the context from {@link #context} (must not be null)
     * @return every hook's result when none blocked, for the caller to read advisory feedback from (never null)
     * @throws ExecutionBlockedByHookException
     *             if any hook blocks the fork; its message names the event and carries the hooks' reasons
     */
    public static List<HookResult> check(HookExecutionManager hookExecutionManager, OnStartContext onStartContext) {
        Objects.requireNonNull(hookExecutionManager, "hookExecutionManager cannot be null");
        Objects.requireNonNull(onStartContext, "onStartContext cannot be null");
        final List<HookResult> results = hookExecutionManager.executeOnStart(onStartContext);
        if (hookExecutionManager.hasBlockedResult(results)) {
            throw new ExecutionBlockedByHookException(InvokerType.SUBAGENT, onStartContext.getInvokerName(), HOOK_EVENT,
                    hookExecutionManager.collectBlockedReasons(results));
        }
        return results;
    }

    /**
     * Creates the result of a fork an {@code onStart} hook blocked: a failure with {@link CompletionReason#BLOCKED} and
     * the exception's message, which names the hook event and carries the hooks' reasons, so every spawn path hands
     * the parent the refusal as it hands it any other failed fork.
     *
     * <p>
     * {@code BLOCKED} rather than {@code ERROR} so a caller that branches on the reason — a workflow script deciding
     * whether to retry a step, a dashboard counting refusals — can tell a guard's refusal from a fault without parsing
     * the message. The parent model still reads the message.
     *
     * @param e
     *            the block (must not be null)
     * @param snapshot
     *            the transcript to hand back — as it stood <b>before</b> the refused goal was added, so a goal a guard
     *            refused is never persisted and replayed by a later resume (must not be null)
     * @param metadata
     *            the execution metadata (must not be null)
     * @param cost
     *            the estimated cost (nullable — zero)
     * @return the blocked result (never null)
     */
    public static SubagentExecutionResult blockedResult(ExecutionBlockedByHookException e, SessionSnapshot snapshot,
            ExecutionMetadata metadata, Money cost) {
        Objects.requireNonNull(e, "e cannot be null");
        return SubagentExecutionResult.failure(e.getMessage(), snapshot, metadata, CompletionReason.BLOCKED, cost);
    }

    /**
     * Creates the blocked result of a fork that keeps no transcript: an empty snapshot, no iterations, no tokens and no
     * cost, timestamped from {@code startTime} to now.
     *
     * @param e
     *            the block (must not be null)
     * @param startTime
     *            when the fork started (must not be null)
     * @return the blocked result (never null)
     * @see #blockedResult(ExecutionBlockedByHookException, SessionSnapshot, ExecutionMetadata, Money)
     */
    public static SubagentExecutionResult blockedResult(ExecutionBlockedByHookException e, Instant startTime) {
        Objects.requireNonNull(startTime, "startTime cannot be null");
        return blockedResult(e, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata.builder().iterationCount(0)
                .tokenUsage(TokenUsage.empty()).timestamps(startTime, Instant.now()).build(), Money.zeroUsd());
    }
}
