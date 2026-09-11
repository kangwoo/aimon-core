package at.aimon.core.agent.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

@DisplayName("TruncatedResponses - the one definition both ReAct loops read for a max_tokens cut")
class TruncatedResponsesTest {

    @Test
    @DisplayName("MAX_TOKENS is a truncation, with tool calls or without")
    void maxTokensIsTruncated() {
        assertThat(TruncatedResponses
                .isTruncated(LlmResponse.of("partial", List.of(), TokenUsage.empty(), StopReason.MAX_TOKENS))).isTrue();
        assertThat(TruncatedResponses.isTruncated(LlmResponse.of("", List.of(ToolUse.of("tu-1", "Write", Map.of())),
                TokenUsage.empty(), StopReason.MAX_TOKENS))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = StopReason.class, names = "MAX_TOKENS", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every other stop reason is not a truncation")
    void otherStopReasonsAreNotTruncated(StopReason reason) {
        assertThat(TruncatedResponses.isTruncated(LlmResponse.of("done", List.of(), TokenUsage.empty(), reason)))
                .isFalse();
    }

    @Test
    @DisplayName("a response built without a stop reason is not a truncation")
    void aResponseWithoutAStopReasonIsNotTruncated() {
        assertThat(TruncatedResponses.isTruncated(LlmResponse.of("done", List.of(), TokenUsage.empty()))).isFalse();
    }

    @Test
    @DisplayName("the marker is the text the main executor has always appended")
    void theMarkerTextIsUnchanged() {
        assertThat(TruncatedResponses.TRUNCATION_MARKER).isEqualTo("\n\n[System: response truncated at max_tokens]");
    }

    @Test
    @DisplayName("the refusal answers the call by its id, as an error, with the shared message")
    void theRefusalAnswersTheCallById() {
        final ToolUseResult refusal = TruncatedResponses
                .refusal(ToolUse.of("tu-7", "Write", Map.of("file_path", "/tmp/partial")));

        assertThat(refusal.getToolUseId()).isEqualTo("tu-7");
        assertThat(refusal.isError()).isTrue();
        assertThat(refusal.getContent()).isEqualTo(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE);
    }

    @Test
    @DisplayName("the refusal names max_tokens, says nothing with an effect ran, and offers remedies rather than a retry")
    void theRefusalCarriesItsRequiredParts() {
        // The parts the message has to keep. Its wording around them may change.
        assertThat(TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE).contains("max_tokens")
                .contains("no call that changes anything was run").contains("none of them has a result")
                .contains("Sending the same calls again will be cut off the same way").contains("fewer tool calls")
                .contains("split a large argument");
    }

    @Test
    @DisplayName("no reasoning tokens reported: the clause is empty, so a WARN reads as it did before")
    void noReasoningTokensGiveAnEmptyClause() {
        assertThat(TruncatedResponses.reasoningClause(TokenUsage.of(10, 4000, 4010))).isEmpty();
        assertThat(TruncatedResponses.reasoningClause(TokenUsage.of(10, 4000, 4010, 0))).isEmpty();
    }

    @Test
    @DisplayName("reasoning tokens reported: the clause states both counts and draws no conclusion from them")
    void reasoningTokensAreReportedAsNumbers() {
        assertThat(TruncatedResponses.reasoningClause(TokenUsage.of(10, 4000, 4010, 3990)))
                .isEqualTo("; the response's usage reports 4000 output tokens and 3990 reasoning tokens");
        // A small share is reported the same way: no share is named past which reasoning "used most" of the limit.
        assertThat(TruncatedResponses.reasoningClause(TokenUsage.of(10, 4000, 4010, 100)))
                .isEqualTo("; the response's usage reports 4000 output tokens and 100 reasoning tokens");
    }

    @Test
    @DisplayName("a reasoning count above the output count is printed as reported, without claiming one contains the other")
    void aReasoningCountAboveTheOutputCountIsPrintedAsReported() {
        // TokenUsage deliberately enforces no reasoning <= completion invariant: the server fills both.
        assertThat(TruncatedResponses.reasoningClause(TokenUsage.of(10, 100, 110, 500))).contains("100 output tokens")
                .contains("500 reasoning tokens").doesNotContain(" of ");
    }
}
