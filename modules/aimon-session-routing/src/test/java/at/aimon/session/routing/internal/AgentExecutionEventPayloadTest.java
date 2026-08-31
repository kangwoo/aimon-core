package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantMessageReceived;
import at.aimon.core.agent.stream.AssistantTextDelta;
import at.aimon.core.agent.stream.AssistantTextStreamCompleted;
import at.aimon.core.agent.stream.AssistantTextStreamReset;
import at.aimon.core.agent.stream.CompactBoundary;
import at.aimon.core.agent.stream.ExecutionCompleted;
import at.aimon.core.agent.stream.ExecutionError;
import at.aimon.core.agent.stream.InterruptedAt;
import at.aimon.core.agent.stream.IterationCompleted;
import at.aimon.core.agent.stream.IterationStarted;
import at.aimon.core.agent.stream.RejectReason;
import at.aimon.core.agent.stream.RejectedAt;
import at.aimon.core.agent.stream.SkillTurnSuspendedEvent;
import at.aimon.core.agent.stream.SubagentTaskCompleted;
import at.aimon.core.agent.stream.ToolResultReady;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurnId;

/**
 * Cross-node round-trip contract for {@link AgentExecutionEventPayload}. Covers every {@link AgentExecutionEvent}
 * subtype because the bug this codec fixes (typed-object-over-the-bus) failed silently and uniformly — a per-type
 * assertion is the only thing that proves each one actually reconstructs over a real bus.
 */
@DisplayName("AgentExecutionEventPayload flat-map round-trip")
class AgentExecutionEventPayloadTest {

    private static final AgentRuntimeId CTX = AgentRuntimeId.fromName("alpha");
    private static final Instant TS = Instant.ofEpochMilli(1_700_000_000_123L);
    private static final TurnId TURN = TurnId.of("turn-7");

    @Test
    @DisplayName("every event subtype round-trips field-for-field")
    void roundTripsEveryEventTypeDirectly() {
        for (AgentExecutionEvent event : samples()) {
            assertThat(AgentExecutionEventPayload.fromPayload(AgentExecutionEventPayload.toPayload(event, TURN)))
                    .as("direct round-trip of %s", event.getClass().getSimpleName()).contains(event);
        }
    }

    @Test
    @DisplayName("every event subtype survives the JSON codec's int/long number normalization (cross-bus contract)")
    void survivesJsonCodecNumberNormalization() {
        for (AgentExecutionEvent event : samples()) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> normalized = (Map<String, Object>) normalizeLikeJsonCodec(
                    AgentExecutionEventPayload.toPayload(event, TURN));
            assertThat(AgentExecutionEventPayload.fromPayload(normalized))
                    .as("normalized round-trip of %s", event.getClass().getSimpleName()).contains(event);
        }
    }

    @Test
    @DisplayName("ExecutionError carries the cause message (lossily) and preserves the renderable fields")
    void executionErrorCarriesCauseMessageLossily() {
        final ExecutionError original = ExecutionError.builder().timestamp(TS).agentRuntimeId(CTX).iteration(0)
                .errorMessage("turn failed").cause(new IllegalStateException("boom"))
                .completionReason(CompletionReason.ERROR).build();

        final ExecutionError decoded = (ExecutionError) AgentExecutionEventPayload
                .fromPayload(AgentExecutionEventPayload.toPayload(original, TURN)).orElseThrow();

        assertThat(decoded.getErrorMessage()).isEqualTo("turn failed");
        assertThat(decoded.getCompletionReason()).contains(CompletionReason.ERROR);
        assertThat(decoded.getCause()).isPresent();
        assertThat(decoded.getCause().get().getMessage()).isEqualTo("boom");
        // The original IllegalStateException type/stacktrace is intentionally not preserved across the bus.
        assertThat(decoded.getCause().get()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("an unknown future event type decodes to empty (forward-compatible rolling deploy)")
    void unknownTypeDecodesToEmpty() {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AgentExecutionEventPayload.KEY_TYPE, "SomeFutureEvent");
        payload.put(AgentExecutionEventPayload.KEY_TIMESTAMP, TS.toEpochMilli());
        payload.put(AgentExecutionEventPayload.KEY_CONTEXT, CTX.value());
        payload.put(AgentExecutionEventPayload.KEY_ITERATION, 1);

        assertThat(AgentExecutionEventPayload.fromPayload(payload)).isEmpty();
    }

    @Test
    @DisplayName("null and malformed payloads decode to empty rather than throwing")
    void nullAndMalformedPayloadDecodeToEmpty() {
        assertThat(AgentExecutionEventPayload.fromPayload(null)).isEmpty();

        final Map<String, Object> missingType = new LinkedHashMap<>();
        missingType.put(AgentExecutionEventPayload.KEY_TIMESTAMP, TS.toEpochMilli());
        missingType.put(AgentExecutionEventPayload.KEY_CONTEXT, CTX.value());
        missingType.put(AgentExecutionEventPayload.KEY_ITERATION, 1);
        assertThat(AgentExecutionEventPayload.fromPayload(missingType)).isEmpty();
    }

    @Test
    @DisplayName("every event subtype carries the turn stamp, and the stamp reads back")
    void everyEventCarriesTheTurnStamp() {
        for (AgentExecutionEvent event : samples()) {
            final Map<String, Object> payload = AgentExecutionEventPayload.toPayload(event, TURN);
            assertThat(payload).as("turn stamp on %s", event.getClass().getSimpleName())
                    .containsEntry(AgentExecutionEventPayload.KEY_TURN, TURN.value());
            assertThat(AgentExecutionEventPayload.turnIdOf(payload)).contains(TURN);
        }
    }

    @Test
    @DisplayName("the turn stamp does not disturb the event round-trip")
    void turnStampIsEnvelopeOnly() {
        final AgentExecutionEvent event = samples().get(0);
        final Map<String, Object> stampedForAnotherTurn = AgentExecutionEventPayload.toPayload(event,
                TurnId.of("turn-8"));

        assertThat(AgentExecutionEventPayload.fromPayload(stampedForAnotherTurn)).contains(event);
    }

    @Test
    @DisplayName("an unstamped payload reads as no turn, so a peer on an older build degrades to conversation-wide")
    void missingTurnStampReadsAsEmpty() {
        final Map<String, Object> unstamped = AgentExecutionEventPayload.toPayload(samples().get(0), TURN);
        unstamped.remove(AgentExecutionEventPayload.KEY_TURN);

        assertThat(AgentExecutionEventPayload.turnIdOf(unstamped)).isEmpty();
        assertThat(AgentExecutionEventPayload.turnIdOf(null)).isEmpty();
    }

    @Test
    @DisplayName("a deliberately unstamped payload names no turn and is otherwise the same frame")
    void unstampedPayloadIsTheStampedOneWithoutTheStamp() {
        for (AgentExecutionEvent event : samples()) {
            final Map<String, Object> unstamped = AgentExecutionEventPayload.toUnstampedPayload(event);
            final String subtype = event.getClass().getSimpleName();

            // Absent, not blank or null: a receiver that filters on the key must see nothing to filter on.
            assertThat(unstamped).as("stamp on %s", subtype).doesNotContainKey(AgentExecutionEventPayload.KEY_TURN);
            assertThat(AgentExecutionEventPayload.turnIdOf(unstamped)).isEmpty();
            assertThat(AgentExecutionEventPayload.fromPayload(unstamped)).as("round-trip of unstamped %s", subtype)
                    .contains(event);

            // The two shapes come off one projection, so the stamp must be the only difference — a subtype branch can
            // never be present in one and missing from the other.
            final Map<String, Object> stamped = AgentExecutionEventPayload.toPayload(event, TURN);
            stamped.remove(AgentExecutionEventPayload.KEY_TURN);
            assertThat(unstamped).as("unstamped %s must differ from the stamped frame in nothing else", subtype)
                    .isEqualTo(stamped);
        }
    }

    @Test
    @DisplayName("a blank turn stamp reads as no turn rather than throwing on TurnId validation")
    void blankTurnStampReadsAsEmpty() {
        final Map<String, Object> blank = new LinkedHashMap<>();
        blank.put(AgentExecutionEventPayload.KEY_TURN, "   ");

        assertThat(AgentExecutionEventPayload.turnIdOf(blank)).isEmpty();
    }

    private static List<AgentExecutionEvent> samples() {
        final TokenUsage tokens = TokenUsage.of(120, 30, 150);
        final Map<String, Object> inputSummary = new LinkedHashMap<>();
        inputSummary.put("path", "/etc/hosts");
        inputSummary.put("limit", 200);

        final List<AgentExecutionEvent> events = new ArrayList<>();
        events.add(
                IterationStarted.builder().timestamp(TS).agentRuntimeId(CTX).iteration(1).plannedIteration(1).build());
        events.add(IterationCompleted.builder().timestamp(TS).agentRuntimeId(CTX).iteration(2).completedIteration(2)
                .willContinue(true).build());
        events.add(AssistantMessageReceived.builder().timestamp(TS).agentRuntimeId(CTX).iteration(1)
                .messageSummary("planning next step").tokenUsage(tokens).build());
        events.add(AssistantTextDelta.builder().timestamp(TS).agentRuntimeId(CTX).iteration(1).delta("hello ")
                .chunkIndex(3).build());
        events.add(AssistantTextStreamReset.builder().timestamp(TS).agentRuntimeId(CTX).iteration(1)
                .previousAttemptIndex(0).nextAttemptIndex(1).reason("retry after tool error").build());
        events.add(AssistantTextStreamCompleted.builder().timestamp(TS).agentRuntimeId(CTX).iteration(1).totalLength(42)
                .tokenUsage(tokens).finishReason("stop").build());
        events.add(ToolUseStarted.builder().timestamp(TS).agentRuntimeId(CTX).iteration(2).toolName("Read")
                .toolUseId("tu-1").inputSummary(inputSummary).build());
        events.add(ToolResultReady.builder().timestamp(TS).agentRuntimeId(CTX).iteration(2).toolName("Read")
                .toolUseId("tu-1").success(true).resultPreviewLength(120).build());
        events.add(CompactBoundary.builder().timestamp(TS).agentRuntimeId(CTX).iteration(3)
                .strategyName("sliding-window").messagesBefore(20).messagesAfter(8).build());
        events.add(ExecutionCompleted.builder().timestamp(TS).agentRuntimeId(CTX).iteration(0)
                .completionReason(CompletionReason.COMPLETED).totalIterations(4).elapsed(Duration.ofMillis(1234))
                .build());
        events.add(ExecutionError.builder().timestamp(TS).agentRuntimeId(CTX).iteration(0).errorMessage("turn failed")
                .completionReason(CompletionReason.ERROR).build());
        events.add(InterruptedAt.builder().timestamp(TS).agentRuntimeId(CTX).iteration(2)
                .reason(InterruptReason.USER_SIGINT).iterationIndex(2).partialOutput("partial output so far").build());
        events.add(RejectedAt.builder().timestamp(TS).agentRuntimeId(CTX).iteration(0)
                .reason(RejectReason.CONFLICTING_AGENT).requestedAgent("beta").existingAgent("alpha").inboxId("inbox-7")
                .build());
        events.add(SkillTurnSuspendedEvent.builder().timestamp(TS).agentRuntimeId(CTX).iteration(1)
                .pendingTurnId(PendingTurnId.of("pt-1"))
                .pendingSkills(List.of(
                        PendingSkillRequest.builder().toolUseId("tu-9").skillName("deploy").args("--env prod").build()))
                .build());
        events.add(SubagentTaskCompleted.builder().timestamp(TS).agentRuntimeId(CTX).iteration(0).taskId("task-42")
                .subagentName("researcher").outcome(SubagentTaskCompleted.Outcome.COMPLETED)
                .detail("summary: found 3 candidates").build());
        return events;
    }

    /**
     * Mirrors the int/long narrowing a JSON string round-trip applies in the Redis/Mongo signal codecs: an
     * in-{@code int}-range {@code long} comes back as {@code Integer}; everything else is unchanged.
     */
    private static Object normalizeLikeJsonCodec(Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put((String) k, normalizeLikeJsonCodec(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            final List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(normalizeLikeJsonCodec(v)));
            return out;
        }
        if (value instanceof Long longValue && longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
            return longValue.intValue();
        }
        return value;
    }
}
