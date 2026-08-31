package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.StopReason;

@DisplayName("OpenAiStopReasons - Wire Finish-Reason Mapping Tests")
class OpenAiStopReasonsTest {

    @Test
    @DisplayName("Should map stop to END_TURN")
    void shouldMapStopToEndTurn() {
        assertThat(OpenAiStopReasons.fromWire("stop")).isEqualTo(StopReason.END_TURN);
    }

    @Test
    @DisplayName("Should map length to MAX_TOKENS")
    void shouldMapLengthToMaxTokens() {
        assertThat(OpenAiStopReasons.fromWire("length")).isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    @DisplayName("Should map tool_calls to TOOL_USE")
    void shouldMapToolCallsToToolUse() {
        assertThat(OpenAiStopReasons.fromWire("tool_calls")).isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    @DisplayName("Should map function_call to TOOL_USE")
    void shouldMapFunctionCallToToolUse() {
        assertThat(OpenAiStopReasons.fromWire("function_call")).isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    @DisplayName("Should map content_filter to REFUSAL")
    void shouldMapContentFilterToRefusal() {
        assertThat(OpenAiStopReasons.fromWire("content_filter")).isEqualTo(StopReason.REFUSAL);
    }

    @Test
    @DisplayName("Should map an unrecognised wire value to UNKNOWN")
    void shouldMapUnrecognisedValueToUnknown() {
        assertThat(OpenAiStopReasons.fromWire("weird")).isEqualTo(StopReason.UNKNOWN);
    }

    @Test
    @DisplayName("Should map null to UNKNOWN")
    void shouldMapNullToUnknown() {
        assertThat(OpenAiStopReasons.fromWire(null)).isEqualTo(StopReason.UNKNOWN);
    }

    @Test
    @DisplayName("Should report length as truncated")
    void shouldReportLengthAsTruncated() {
        assertThat(OpenAiStopReasons.fromWire("length").isTruncated()).isTrue();
    }

    @Test
    @DisplayName("Should report stop as not truncated")
    void shouldReportStopAsNotTruncated() {
        assertThat(OpenAiStopReasons.fromWire("stop").isTruncated()).isFalse();
    }
}
