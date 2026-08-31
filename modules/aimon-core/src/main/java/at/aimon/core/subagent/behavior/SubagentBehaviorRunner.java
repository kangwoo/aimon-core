package at.aimon.core.subagent.behavior;

import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

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

    /** Creates a runner with no LLM access ({@code llmGateway()} is empty). */
    public SubagentBehaviorRunner() {
        this(null);
    }

    /**
     * Creates a runner whose behaviors can call the model through a gateway built from {@code llmClient}, configured
     * exactly like the subagent ReAct path's gateway (default retry policy, no fallback).
     *
     * @param llmClient
     *            the client to wrap (nullable; null disables LLM access for behaviors)
     */
    public SubagentBehaviorRunner(LlmClient llmClient) {
        this.llmGateway = llmClient != null ? LlmCallGateway.<TranscriptBuffer>withDefaultRetry(llmClient) : null;
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
     * @return the behavior's result, or a shaped failure result on cancellation, null-return, or thrown exception
     */
    public SubagentExecutionResult run(SubagentBehavior behavior, SubagentExecutionContext context,
            SubagentExecutionRequest request) {
        Objects.requireNonNull(behavior, "behavior cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(request, "request cannot be null");

        final String name = context.getSubagent().getName();
        final DefaultSubagentBehaviorSupport support = new DefaultSubagentBehaviorSupport(context, request,
                Instant.now(), llmGateway);

        try {
            if (support.isCancelledOrInterrupted()) {
                return support.failure("Code-behavior subagent '" + name + "' interrupted before execution");
            }
            final SubagentExecutionResult result = behavior.execute(context, request, support);
            if (result == null) {
                log.warn("Code-behavior subagent '{}' returned a null result", name);
                return support.failure("Code-behavior subagent '" + name + "' returned a null result");
            }
            return result;
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
}
