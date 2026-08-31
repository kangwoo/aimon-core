package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.StopReason;

@DisplayName("AnthropicStopReasons - Wire Stop-Reason Mapping Tests")
class AnthropicStopReasonsTest {

    @Test
    @DisplayName("Should map end_turn to END_TURN")
    void shouldMapEndTurnToEndTurn() {
        assertThat(AnthropicStopReasons.fromWire("end_turn")).isEqualTo(StopReason.END_TURN);
    }

    @Test
    @DisplayName("Should map max_tokens to MAX_TOKENS")
    void shouldMapMaxTokensToMaxTokens() {
        assertThat(AnthropicStopReasons.fromWire("max_tokens")).isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    @DisplayName("Should map tool_use to TOOL_USE")
    void shouldMapToolUseToToolUse() {
        assertThat(AnthropicStopReasons.fromWire("tool_use")).isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    @DisplayName("Should map stop_sequence to STOP_SEQUENCE")
    void shouldMapStopSequenceToStopSequence() {
        assertThat(AnthropicStopReasons.fromWire("stop_sequence")).isEqualTo(StopReason.STOP_SEQUENCE);
    }

    @Test
    @DisplayName("Should map refusal to REFUSAL")
    void shouldMapRefusalToRefusal() {
        assertThat(AnthropicStopReasons.fromWire("refusal")).isEqualTo(StopReason.REFUSAL);
    }

    @Test
    @DisplayName("Should map pause_turn to UNKNOWN since it has no neutral slot")
    void shouldMapPauseTurnToUnknown() {
        assertThat(AnthropicStopReasons.fromWire("pause_turn")).isEqualTo(StopReason.UNKNOWN);
    }

    @Test
    @DisplayName("Should map an unrecognised wire value to UNKNOWN")
    void shouldMapUnrecognisedValueToUnknown() {
        assertThat(AnthropicStopReasons.fromWire("some_unknown_value")).isEqualTo(StopReason.UNKNOWN);
    }

    @Test
    @DisplayName("Should map null to UNKNOWN")
    void shouldMapNullToUnknown() {
        assertThat(AnthropicStopReasons.fromWire(null)).isEqualTo(StopReason.UNKNOWN);
    }

    @Test
    @DisplayName("Should report max_tokens as truncated")
    void shouldReportMaxTokensAsTruncated() {
        assertThat(AnthropicStopReasons.fromWire("max_tokens").isTruncated()).isTrue();
    }

    @Test
    @DisplayName("Should report end_turn as not truncated")
    void shouldReportEndTurnAsNotTruncated() {
        assertThat(AnthropicStopReasons.fromWire("end_turn").isTruncated()).isFalse();
    }
}
