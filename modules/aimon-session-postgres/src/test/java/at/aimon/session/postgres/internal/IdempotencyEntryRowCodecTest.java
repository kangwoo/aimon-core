package at.aimon.session.postgres.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.store.StoredAgentExecutionResult;

/**
 * Unit tests for {@link IdempotencyEntryRowCodec} — the {@code result_blob} round-trip, exercised without a container
 * so it runs under the daemonless {@code test} task.
 */
@DisplayName("IdempotencyEntryRowCodec — result_blob round-trip and forward tolerance")
class IdempotencyEntryRowCodecTest {

    private final IdempotencyEntryRowCodec codec = new IdempotencyEntryRowCodec(new ObjectMapper());

    @Test
    @DisplayName("a result round-trips field-for-field")
    void resultRoundTrips() {
        final AgentExecutionResult original = StoredAgentExecutionResult.builder().success(false)
                .errorMessage("budget exhausted").completionReason(CompletionReason.TOKEN_BUDGET_EXCEEDED)
                .wasStreamed(true).build();

        final AgentExecutionResult decoded = codec.decodeResult(codec.encodeResult(original)).orElseThrow();

        assertThat(decoded.isSuccess()).isFalse();
        assertThat(decoded.getFinalAnswer()).isNull();
        assertThat(decoded.getErrorMessage()).isEqualTo("budget exhausted");
        assertThat(decoded.getCompletionReason()).isEqualTo(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        assertThat(decoded.wasStreamed()).isTrue();
    }

    @Test
    @DisplayName("a blob whose completion reason this build does not know still replays its answer")
    void unknownCompletionReasonOnASuccessKeepsTheAnswer() {
        // Written by a node on a newer build during a rolling upgrade. The row exists so a retried turn gets the
        // answer that already ran; the label is the least important field in it.
        final AgentExecutionResult decoded = codec
                .decodeResult("{\"success\":true,\"finalAnswer\":\"the answer\",\"errorMessage\":null,"
                        + "\"completionReason\":\"INVENTED_BY_A_NEWER_NODE\",\"wasStreamed\":false}")
                .orElseThrow();

        assertThat(decoded.isSuccess()).isTrue();
        assertThat(decoded.getFinalAnswer()).isEqualTo("the answer");
        assertThat(decoded.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    @DisplayName("a failed blob whose completion reason this build does not know replays as ERROR")
    void unknownCompletionReasonOnAFailureDegradesToError() {
        final AgentExecutionResult decoded = codec
                .decodeResult("{\"success\":false,\"finalAnswer\":null,\"errorMessage\":\"stopped\","
                        + "\"completionReason\":\"INVENTED_BY_A_NEWER_NODE\",\"wasStreamed\":false}")
                .orElseThrow();

        assertThat(decoded.isSuccess()).isFalse();
        assertThat(decoded.getErrorMessage()).isEqualTo("stopped");
        assertThat(decoded.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
    }
}
