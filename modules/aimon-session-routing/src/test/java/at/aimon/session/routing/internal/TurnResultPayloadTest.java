package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.store.StoredAgentExecutionResult;

/**
 * Wire contract for {@link TurnResultPayload} — the rail a forwarded turn's answer travels back on.
 *
 * <p>
 * The failure mode this guards is silent: a payload that decodes to {@link Optional#empty()} raises nothing, it merely
 * leaves the submitting node's caller waiting on the polling fallback (or, with no idempotency key, on its deadline).
 * So
 * every field the manager depends on is asserted individually rather than through an equality check on a rebuilt result
 * object, which {@link AgentExecutionResult} does not define.
 */
@DisplayName("TurnResultPayload flat-map round-trip")
class TurnResultPayloadTest {

    private static final TurnId TURN = TurnId.of("turn-42");

    @Test
    @DisplayName("a successful result round-trips field-for-field")
    void successfulResultRoundTrips() {
        final AgentExecutionResult original = StoredAgentExecutionResult.builder().success(true)
                .finalAnswer("the answer").completionReason(CompletionReason.COMPLETED).wasStreamed(true).build();

        final TurnResultPayload.Decoded decoded = TurnResultPayload
                .fromPayload(TurnResultPayload.toPayload(TURN, "k-1", original)).orElseThrow();

        assertThat(decoded.turnId()).hasValue(TURN);
        assertThat(decoded.idempotencyKey()).hasValue("k-1");
        assertThat(decoded.failure()).isEmpty();
        final AgentExecutionResult rebuilt = decoded.result().orElseThrow();
        assertThat(rebuilt.isSuccess()).isTrue();
        assertThat(rebuilt.getFinalAnswer()).isEqualTo("the answer");
        assertThat(rebuilt.getErrorMessage()).isNull();
        assertThat(rebuilt.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(rebuilt.wasStreamed()).isTrue();
        assertThat(rebuilt.getArtifacts()).as("a result that crossed a node boundary carries no artifacts").isEmpty();
    }

    @Test
    @DisplayName("a failed result keeps its error message and completion reason")
    void failedResultRoundTrips() {
        final AgentExecutionResult original = StoredAgentExecutionResult.builder().success(false)
                .errorMessage("budget exhausted").completionReason(CompletionReason.TOKEN_BUDGET_EXCEEDED).build();

        final AgentExecutionResult rebuilt = TurnResultPayload
                .fromPayload(TurnResultPayload.toPayload(TURN, null, original)).orElseThrow().result().orElseThrow();

        assertThat(rebuilt.isSuccess()).isFalse();
        assertThat(rebuilt.getFinalAnswer()).isNull();
        assertThat(rebuilt.getErrorMessage()).isEqualTo("budget exhausted");
        assertThat(rebuilt.getCompletionReason()).isEqualTo(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        assertThat(rebuilt.wasStreamed()).isFalse();
    }

    @Test
    @DisplayName("absent fields are omitted, never null-valued — SessionSignal copies the payload with Map.copyOf")
    void absentFieldsAreOmittedRatherThanNulled() {
        final Map<String, Object> payload = TurnResultPayload.toPayload(TURN, null, StoredAgentExecutionResult.builder()
                .success(true).finalAnswer("hi").completionReason(CompletionReason.COMPLETED).build());

        assertThat(payload).doesNotContainKeys(TurnResultPayload.KEY_IDEMPOTENCY_KEY,
                TurnResultPayload.KEY_RESULT_ERROR);
        assertThat(payload.values()).doesNotContainNull();
        // Map.copyOf is what SessionSignal's constructor applies; a null value would throw there, not here.
        assertThat(Map.copyOf(payload)).containsEntry(TurnResultPayload.KEY_ANSWER, "hi");
        assertThat(TurnResultPayload.fromPayload(payload).orElseThrow().idempotencyKey()).isEmpty();
    }

    @Test
    @DisplayName("a failed result omits the answer field")
    void failedResultOmitsAnswer() {
        final Map<String, Object> payload = TurnResultPayload.toPayload(TURN, "k-2",
                StoredAgentExecutionResult.builder().success(false).errorMessage("stopped")
                        .completionReason(CompletionReason.INTERRUPTED).build());

        assertThat(payload).doesNotContainKey(TurnResultPayload.KEY_ANSWER)
                .containsEntry(TurnResultPayload.KEY_RESULT_ERROR, "stopped");
    }

    @Test
    @DisplayName("each failure code round-trips with its message")
    void failureCodesRoundTrip() {
        for (TurnResultPayload.Failure.Code code : TurnResultPayload.Failure.Code.values()) {
            final TurnResultPayload.Decoded decoded = TurnResultPayload
                    .fromPayload(TurnResultPayload.toFailurePayload(TURN, "k-3", code, "because " + code))
                    .orElseThrow();

            assertThat(decoded.result()).as("%s must not decode as a result", code).isEmpty();
            final TurnResultPayload.Failure failure = decoded.failure().orElseThrow();
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.message()).isEqualTo("because " + code);
            assertThat(decoded.turnId()).hasValue(TURN);
            assertThat(decoded.idempotencyKey()).hasValue("k-3");
        }
    }

    @Test
    @DisplayName("a failure addressed by key alone round-trips — holder loss cannot name the turn")
    void keyOnlyFailureRoundTrips() {
        final Map<String, Object> payload = TurnResultPayload.toFailurePayload(null, "k-4",
                TurnResultPayload.Failure.Code.HOLDER_LOST, "holder dead-node was lost");

        assertThat(payload).doesNotContainKey(TurnResultPayload.KEY_TURN);
        final TurnResultPayload.Decoded decoded = TurnResultPayload.fromPayload(payload).orElseThrow();
        assertThat(decoded.turnId()).as("the sweeper works from a reservation, which does not record the turn")
                .isEmpty();
        assertThat(decoded.idempotencyKey()).hasValue("k-4");
        assertThat(decoded.failure().orElseThrow().code()).isEqualTo(TurnResultPayload.Failure.Code.HOLDER_LOST);
    }

    @Test
    @DisplayName("a failure with neither address is refused at the encoder rather than published to nobody")
    void addresslessFailureIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> TurnResultPayload.toFailurePayload(null, null,
                TurnResultPayload.Failure.Code.FAILED, "nobody can be told this"))
                .withMessageContaining("idempotency key");
    }

    @Test
    @DisplayName("an outcome name this build does not know degrades to FAILED instead of dropping the signal")
    void unknownOutcomeDegradesToFailed() {
        final Map<String, Object> payload = failurePayload("SUPERSEDED_BY_A_LATER_STAGE");
        payload.put(TurnResultPayload.KEY_MESSAGE, "detail");

        final TurnResultPayload.Failure failure = TurnResultPayload.fromPayload(payload).orElseThrow().failure()
                .orElseThrow();

        assertThat(failure.code()).isEqualTo(TurnResultPayload.Failure.Code.FAILED);
        assertThat(failure.message()).as("the detail must survive the downgrade").isEqualTo("detail");
    }

    @Test
    @DisplayName("a failure with no message falls back to the raw outcome name rather than being dropped")
    void failureWithoutMessageKeepsTheOutcomeName() {
        final TurnResultPayload.Failure failure = TurnResultPayload.fromPayload(failurePayload("YIELDED")).orElseThrow()
                .failure().orElseThrow();

        assertThat(failure.code()).isEqualTo(TurnResultPayload.Failure.Code.FAILED);
        assertThat(failure.message()).isEqualTo("YIELDED");
    }

    @Test
    @DisplayName("a malformed or absent payload yields empty instead of throwing on the receive path")
    void malformedPayloadIsDiscarded() {
        assertThat(TurnResultPayload.fromPayload(null)).as("no payload").isEmpty();
        assertThat(TurnResultPayload.fromPayload(Map.of())).as("no address at all").isEmpty();
        assertThat(TurnResultPayload
                .fromPayload(Map.of(TurnResultPayload.KEY_OUTCOME, "FAILED", TurnResultPayload.KEY_MESSAGE, "x")))
                .as("a well-formed failure that names neither turn nor key").isEmpty();
        assertThat(TurnResultPayload.fromPayload(Map.of(TurnResultPayload.KEY_TURN, TURN.value())))
                .as("no outcome — nothing distinguishes a result from a failure").isEmpty();
        assertThat(TurnResultPayload.fromPayload(Map.of(TurnResultPayload.KEY_TURN, 42, TurnResultPayload.KEY_OUTCOME,
                "FAILED", TurnResultPayload.KEY_MESSAGE, "x"))).as("turn id is not a string").isEmpty();
        assertThat(TurnResultPayload.fromPayload(Map.of(TurnResultPayload.KEY_TURN, TURN.value(),
                TurnResultPayload.KEY_OUTCOME, TurnResultPayload.OUTCOME_RESULT))).as("result fields missing")
                .isEmpty();
        assertThat(TurnResultPayload.fromPayload(resultPayload(CompletionReason.COMPLETED.name(), "answer")))
                .as("control: the same shape with every field present decodes").isPresent();
        assertThat(TurnResultPayload.fromPayload(resultPayload("NOT_A_REASON", "answer")))
                .as("unknown completion reason").isEmpty();
        assertThat(TurnResultPayload.fromPayload(resultPayload(CompletionReason.COMPLETED.name(), null)))
                .as("a success with no answer violates the AgentExecutionResult invariant").isEmpty();
    }

    private static Map<String, Object> failurePayload(String outcome) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TurnResultPayload.KEY_TURN, TURN.value());
        payload.put(TurnResultPayload.KEY_OUTCOME, outcome);
        return payload;
    }

    private static Map<String, Object> resultPayload(String completion, String answer) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TurnResultPayload.KEY_TURN, TURN.value());
        payload.put(TurnResultPayload.KEY_OUTCOME, TurnResultPayload.OUTCOME_RESULT);
        payload.put(TurnResultPayload.KEY_SUCCESS, true);
        payload.put(TurnResultPayload.KEY_COMPLETION, completion);
        payload.put(TurnResultPayload.KEY_STREAMED, false);
        if (answer != null) {
            payload.put(TurnResultPayload.KEY_ANSWER, answer);
        }
        return payload;
    }
}
