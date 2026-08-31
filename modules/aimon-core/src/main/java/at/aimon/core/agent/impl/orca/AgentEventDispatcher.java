package at.aimon.core.agent.impl.orca;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantMessageReceived;
import at.aimon.core.agent.stream.AssistantTextDelta;
import at.aimon.core.agent.stream.AssistantTextStreamCompleted;
import at.aimon.core.agent.stream.AssistantTextStreamReset;
import at.aimon.core.agent.stream.CompactBoundary;
import at.aimon.core.agent.stream.ExecutionCompleted;
import at.aimon.core.agent.stream.ExecutionError;
import at.aimon.core.agent.stream.IterationCompleted;
import at.aimon.core.agent.stream.IterationStarted;
import at.aimon.core.agent.stream.SkillTurnSuspendedEvent;
import at.aimon.core.agent.stream.ToolResultReady;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.skill.policy.pending.PendingTurn;

/**
 * Per-turn emitter of {@link AgentExecutionEvent}s. Extracted from {@link OrcaAgentExecutor} (a god class) so the
 * ReAct loop delegates all event construction and fan-out to a single cohesive collaborator.
 *
 * <p>
 * Each event is delivered to <b>both</b> sinks (H1 isolation model):
 * <ul>
 * <li>the executor-wide shared emitter — global observers registered via {@code addEventListener}, which opt into
 * seeing every execution's events;
 * <li>this turn's own {@code perExecutionSink} — the streaming subscriber for THIS execution only, so an agent-scoped
 * executor shared across concurrent sessions never fans one turn's events out to another turn's subscriber.
 * </ul>
 *
 * <p>
 * Every helper short-circuits via {@link #hasNoListeners()} when neither sink has a listener, so a zero-listener turn
 * pays only two {@code isEmpty()} reads per event. Package-private: an implementation detail of the Orca executor.
 */
final class AgentEventDispatcher {

    private final EventEmitter sharedEmitter;
    private final AgentRuntimeId agentRuntimeId;
    private final Instant startTime;
    private final EventEmitter perExecutionSink;

    /**
     * Creates a dispatcher for one turn.
     *
     * @param sharedEmitter
     *            the executor-wide emitter for global observers (must not be null)
     * @param agentRuntimeId
     *            the agent runtime id stamped onto every event (must not be null)
     * @param startTime
     *            the turn start, used to compute elapsed time on completion (must not be null)
     * @param perExecutionSink
     *            this turn's own sink for its streaming subscriber (must not be null)
     */
    AgentEventDispatcher(EventEmitter sharedEmitter, AgentRuntimeId agentRuntimeId, Instant startTime,
            EventEmitter perExecutionSink) {
        this.sharedEmitter = Objects.requireNonNull(sharedEmitter, "sharedEmitter cannot be null");
        this.agentRuntimeId = Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        this.startTime = Objects.requireNonNull(startTime, "startTime cannot be null");
        this.perExecutionSink = Objects.requireNonNull(perExecutionSink, "perExecutionSink cannot be null");
    }

    /** True when neither the shared emitter nor this turn's sink has a listener, so emission can short-circuit. */
    private boolean hasNoListeners() {
        return sharedEmitter.isEmpty() && perExecutionSink.isEmpty();
    }

    /**
     * Dispatches an already-built {@code event} to both the shared emitter (global observers) and this turn's own
     * sink. Package-visible so the executor can wire it as the tool-facing {@code AGENT_EVENT_SINK} (e.g. a background
     * subagent forwarding a completion event); the {@code emit*} helpers use it internally.
     */
    void dispatch(AgentExecutionEvent event) {
        sharedEmitter.emit(event);
        perExecutionSink.emit(event);
    }

    /**
     * Emits an {@link IterationStarted} event. All emission helpers short-circuit when no listeners are registered.
     */
    void emitIterationStarted(int iteration) {
        if (hasNoListeners()) {
            return;
        }
        dispatch(IterationStarted.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId).iteration(iteration)
                .plannedIteration(iteration).build());
    }

    void emitAssistantMessageReceived(int iteration, LlmResponse response) {
        if (hasNoListeners()) {
            return;
        }
        final String summary = response.getTextContent() == null ? "" : response.getTextContent();
        dispatch(AssistantMessageReceived.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId)
                .iteration(iteration).messageSummary(summary).tokenUsage(response.getTokenUsage()).build());
    }

    void emitToolUseStarted(int iteration, ToolUse toolUse) {
        if (hasNoListeners()) {
            return;
        }
        final Map<String, Object> inputSummary = toolUse.getInput() == null
                ? Map.of()
                : new HashMap<>(toolUse.getInput());
        dispatch(ToolUseStarted.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId).iteration(iteration)
                .toolName(toolUse.getName()).toolUseId(toolUse.getId()).inputSummary(inputSummary).build());
    }

    void emitToolResultReady(int iteration, ToolUse toolUse, ToolUseResult result) {
        if (hasNoListeners()) {
            return;
        }
        final boolean success = !result.isError();
        final ToolResultReady.Builder builder = ToolResultReady.builder().timestamp(Instant.now())
                .agentRuntimeId(agentRuntimeId).iteration(iteration).toolName(toolUse.getName())
                .toolUseId(toolUse.getId()).success(success);
        if (!success) {
            // Tool-layer contract guarantees a non-empty content for errors; fall back to a short marker to satisfy
            // the ToolResultReady invariant (errorMessage required iff !success).
            final String content = result.getContent();
            builder.errorMessage(content == null || content.isEmpty() ? "tool execution error" : content);
        }
        if (result.getContent() != null) {
            builder.resultPreviewLength(result.getContent().length());
        }
        dispatch(builder.build());
    }

    void emitIterationCompleted(int iteration, boolean willContinue) {
        if (hasNoListeners()) {
            return;
        }
        dispatch(IterationCompleted.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId)
                .iteration(iteration).completedIteration(iteration).willContinue(willContinue).build());
    }

    /**
     * Emits a {@link CompactBoundary} event when a compaction step ran before {@code iteration}.
     *
     * <p>
     * {@code messagesAfter} is clamped to {@code messagesBefore} defensively: L3 summarisation normally collapses many
     * messages into a marker-plus-summary pair, so {@code after < before} holds, but the {@link CompactBoundary}
     * constructor forbids {@code after > before} and a degenerate compaction on a tiny conversation must never surface
     * that invariant violation as a thrown exception inside the ReAct loop.
     */
    void emitCompactBoundary(int iteration, int messagesBefore, int messagesAfter) {
        if (hasNoListeners()) {
            return;
        }
        final int clampedAfter = Math.min(messagesAfter, messagesBefore);
        dispatch(CompactBoundary.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId).iteration(iteration)
                .strategyName(OrcaAgentExecutor.COMPACTION_STRATEGY_NAME).messagesBefore(messagesBefore)
                .messagesAfter(clampedAfter).build());
    }

    void emitExecutionCompleted(int totalIterations, CompletionReason reason) {
        if (hasNoListeners()) {
            return;
        }
        dispatch(ExecutionCompleted.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId).iteration(0)
                .completionReason(reason).totalIterations(totalIterations)
                .elapsed(Duration.between(startTime, Instant.now())).build());
    }

    void emitExecutionError(Exception exception, String errorMessage) {
        if (hasNoListeners()) {
            return;
        }
        dispatch(ExecutionError.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId).iteration(0)
                .errorMessage(errorMessage).cause(exception).completionReason(CompletionReason.ERROR).build());
    }

    void emitSkillTurnSuspended(int iteration, PendingTurn turn) {
        if (hasNoListeners()) {
            return;
        }
        dispatch(SkillTurnSuspendedEvent.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId)
                .iteration(iteration).pendingTurnId(turn.getId()).pendingSkills(turn.getPendingSkills()).build());
    }

    /**
     * Emits an {@link AssistantTextDelta} event for one streaming chunk. Listener-gated: a zero-listener turn pays
     * only an {@code isEmpty()} read per TEXT_DELTA chunk.
     */
    void emitAssistantTextDelta(int iteration, String delta, int chunkIndex) {
        if (hasNoListeners()) {
            return;
        }
        dispatch(AssistantTextDelta.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId)
                .iteration(iteration).delta(delta).chunkIndex(chunkIndex).build());
    }

    /**
     * Emits an {@link AssistantTextStreamReset} event when the gateway discards a streaming attempt (retry or
     * fallback) and the caller must clear any partial text already rendered.
     */
    void emitAssistantTextStreamReset(int iteration, int previousAttemptIndex, int nextAttemptIndex, String reason) {
        if (hasNoListeners()) {
            return;
        }
        dispatch(AssistantTextStreamReset.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId)
                .iteration(iteration).previousAttemptIndex(previousAttemptIndex).nextAttemptIndex(nextAttemptIndex)
                .reason(reason).build());
    }

    /**
     * Emits an {@link AssistantTextStreamCompleted} event carrying the final accumulated length, optional token
     * usage, and optional finish reason.
     */
    void emitAssistantTextStreamCompleted(int iteration, int totalLength, TokenUsage tokenUsage, String finishReason) {
        if (hasNoListeners()) {
            return;
        }
        dispatch(AssistantTextStreamCompleted.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId)
                .iteration(iteration).totalLength(totalLength).tokenUsage(tokenUsage).finishReason(finishReason)
                .build());
    }
}
