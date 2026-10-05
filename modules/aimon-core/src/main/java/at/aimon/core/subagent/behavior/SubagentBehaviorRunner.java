package at.aimon.core.subagent.behavior;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.hook.exception.ExecutionBlockedByHookException;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentOnStartGate;

/**
 * Runs a {@link SubagentBehavior} in place of the ReAct loop, owning the per-execution lifecycle the executor would
 * otherwise own.
 *
 * <p>
 * The runner mirrors the executor's <em>result-shaping and error-isolation</em> posture: it checks the cancellation
 * signal before invoking the behavior, maps a {@code null} return and any thrown exception to a failure result (the
 * behavior never escapes as an exception), clears any lingering thread interrupt before returning the worker to the
 * shared subagent pool, and hands the behavior a {@link SubagentBehaviorSupport} so results are shaped consistently.
 *
 * <p>
 * Unlike the ReAct path, the runner wires NO per-tool interrupt terminator and does NOT enforce the execution budget:
 * cancellation is cooperative-only (a long or looping behavior must poll
 * {@link SubagentBehaviorSupport#isCancelledOrInterrupted()}), and a behavior that calls the model is responsible for
 * bounding its own token use.
 *
 * <p>
 * Like the ReAct path it fires the fork's {@code onStart} hooks first, through the gate the two paths share
 * ({@link SubagentOnStartGate}): a hook that blocks ends the fork before the behavior is invoked, with the same failed
 * result and no {@code onStop}. Three things differ, because a behavior is code rather than a model conversation.
 * The hooks receive the environment the behavior itself is handed
 * ({@link SubagentExecutionContext#getExecutionEnvironment()}, the spawning execution's) — the runner resolves none
 * for the fork. Feedback from a hook that does not block is discarded, because there is no transcript to append it to.
 * And {@code onStop} never fires, blocked or not: the runner has no loop to stop. A runner built without a
 * {@link HookExecutionManager} fires nothing.
 *
 * <p>
 * The surrounding {@code SubagentStart}/{@code SubagentStop} hooks and the manager's error shaping are applied by
 * {@code DefaultSubagentExecutionManager} around the call into this runner; the runner only replaces the LLM loop body.
 * Stateless and thread-safe.
 */
public final class SubagentBehaviorRunner {

    private static final Logger log = LoggerFactory.getLogger(SubagentBehaviorRunner.class);

    /**
     * Retry/fallback-aware gateway exposed to behaviors via {@link SubagentBehaviorSupport#llmGateway()}. Null when no
     * {@link LlmClient} was supplied — behaviors then observe an empty {@code llmGateway()}.
     */
    private final LlmCallGateway<TranscriptBuffer> llmGateway;

    /** Fires the fork's {@code onStart} hooks; null when the runner was built without one (nothing is fired). */
    private final HookExecutionManager hookExecutionManager;

    /** Creates a runner with no LLM access ({@code llmGateway()} is empty) that fires no {@code onStart} hooks. */
    public SubagentBehaviorRunner() {
        this(null, null);
    }

    /**
     * Creates a runner whose behaviors can call the model through a gateway built from {@code llmClient}, configured
     * exactly like the subagent ReAct path's gateway (default retry policy, no fallback). It fires no {@code onStart}
     * hooks; use {@link #SubagentBehaviorRunner(LlmClient, HookExecutionManager)} for a runner an {@code onStart}
     * guard reaches.
     *
     * @param llmClient
     *            the client to wrap (nullable; null disables LLM access for behaviors)
     */
    public SubagentBehaviorRunner(LlmClient llmClient) {
        this(llmClient, null);
    }

    /**
     * Creates a runner that fires each fork's {@code onStart} hooks before invoking its behavior.
     *
     * @param llmClient
     *            the client to wrap (nullable; null disables LLM access for behaviors)
     * @param hookExecutionManager
     *            the manager that runs the {@code onStart} chain (nullable; null fires nothing, so no hook can block a
     *            behavior)
     */
    public SubagentBehaviorRunner(LlmClient llmClient, HookExecutionManager hookExecutionManager) {
        this.llmGateway = llmClient != null ? LlmCallGateway.<TranscriptBuffer>withDefaultRetry(llmClient) : null;
        this.hookExecutionManager = hookExecutionManager;
    }

    /**
     * Runs the behavior for the given context/request and returns its (or a shaped failure) result.
     *
     * @param behavior
     *            the code behavior (must not be null)
     * @param context
     *            the execution context (must not be null)
     * @param request
     *            the execution request (must not be null)
     * @return the behavior's result, or a shaped failure result on cancellation, an {@code onStart} block, null-return,
     *         or thrown exception
     */
    public SubagentExecutionResult run(SubagentBehavior behavior, SubagentExecutionContext context,
            SubagentExecutionRequest request) {
        Objects.requireNonNull(behavior, "behavior cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(request, "request cannot be null");

        final String name = context.getSubagent().getName();
        final Instant startTime = Instant.now();
        final DefaultSubagentBehaviorSupport support = new DefaultSubagentBehaviorSupport(context, request, startTime,
                llmGateway);

        try {
            if (support.isCancelledOrInterrupted()) {
                return support.failure("Code-behavior subagent '" + name + "' interrupted before execution");
            }
            checkOnStartHooks(context, request);
            final SubagentExecutionResult result = behavior.execute(context, request, support);
            if (result == null) {
                log.warn("Code-behavior subagent '{}' returned a null result", name);
                return support.failure("Code-behavior subagent '" + name + "' returned a null result");
            }
            return result;
        } catch (ExecutionBlockedByHookException e) {
            // Not an error of the behavior: a guard refused the fork before it ran. No stack trace, as the ReAct path.
            log.warn("Code-behavior subagent '{}' not started: {}", name, e.getMessage());
            streamEnded(context, e.getMessage());
            return SubagentOnStartGate.blockedResult(e, startTime);
        } catch (Exception e) {
            log.error("Code-behavior subagent '{}' failed: {}", name, e.getMessage(), e);
            return support.failure("Code-behavior execution failed: " + e.getMessage());
        } finally {
            // Backstop against thread-interrupt leakage into the shared subagent pool: a behavior that caught
            // InterruptedException without restoring the flag (or left it set after a cooperative stop) must not return
            // a poisoned worker, where the next task's pre-flight isCancelledOrInterrupted() would read a stale
            // interrupt. Clearing here mirrors the main ReAct loop, which consumes the flag through the shared
            // CancellationSignals check at every loop checkpoint and sweeps it once more at turn finalisation; the
            // subagent ReAct path (DefaultSubagentExecutor) consumes it at its checkpoints only and has no
            // execution-end sweep of its own, so do not treat this backstop as redundant with one.
            // support.isCancelledOrInterrupted() also clears on entry; this covers the post-invocation window.
            Thread.interrupted();
        }
    }

    /**
     * Fires the fork's {@code onStart} hooks through the gate the ReAct path uses and throws if one blocks.
     *
     * <p>
     * The hooks are handed the environment the behavior is handed, which is the spawning execution's: the runner
     * resolves none for the fork, and a behavior that wants one of its own asks the provider itself. A hook that needs
     * a shell and finds no environment reports that and blocks, as it does anywhere else.
     *
     * @throws ExecutionBlockedByHookException
     *             if any {@code onStart} hook blocks the fork
     */
    private void checkOnStartHooks(SubagentExecutionContext context, SubagentExecutionRequest request) {
        if (hookExecutionManager == null) {
            return;
        }
        final List<HookResult> results = SubagentOnStartGate.check(hookExecutionManager,
                SubagentOnStartGate.context(context, request.getGoal(), request.getExecutionAttributes(),
                        context.getExecutionEnvironment().orElse(null)));
        // A behavior has no transcript, so advice that is not a block has no reader here.
        final List<String> advisory = HookFeedback.collectAdvisory(results);
        if (!advisory.isEmpty()) {
            log.debug(
                    "Code-behavior subagent '{}' discards {} onStart feedback message(s): no transcript to carry them",
                    context.getSubagent().getName(), advisory.size());
        }
    }

    /** Writes the terminal boundary a background tail reads to learn that the task ended, and why. */
    private static void streamEnded(SubagentExecutionContext context, String reason) {
        try {
            context.getOutputSink().append("\n[ended: " + reason + "]\n");
        } catch (RuntimeException e) {
            log.debug("Subagent output streaming append failed (ignored): {}", e.getMessage());
        }
    }
}
