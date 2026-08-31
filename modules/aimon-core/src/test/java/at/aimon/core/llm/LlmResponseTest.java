package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LlmResponse Tests")
class LlmResponseTest {

    @Test
    @DisplayName("Should create response with text only")
    void shouldCreateTextOnlyResponse() {
        LlmResponse response = LlmResponse.text("Hello");

        assertThat(response.getTextContent()).isEqualTo("Hello");
        assertThat(response.getToolUses()).isEmpty();
        assertThat(response.hasTextContent()).isTrue();
        assertThat(response.hasToolUses()).isFalse();
    }

    @Test
    @DisplayName("Should create response with tools only")
    void shouldCreateToolsOnlyResponse() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));

        LlmResponse response = LlmResponse.tools(List.of(tool));

        assertThat(response.getTextContent()).isEmpty();
        assertThat(response.getToolUses()).hasSize(1);
        assertThat(response.hasTextContent()).isFalse();
        assertThat(response.hasToolUses()).isTrue();
    }

    @Test
    @DisplayName("Should create response with both text and tools")
    void shouldCreateMixedResponse() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));

        LlmResponse response = LlmResponse.of("Executing command", List.of(tool));

        assertThat(response.getTextContent()).isEqualTo("Executing command");
        assertThat(response.getToolUses()).hasSize(1);
        assertThat(response.hasTextContent()).isTrue();
        assertThat(response.hasToolUses()).isTrue();
    }

    @Test
    @DisplayName("Should handle null text as empty")
    void shouldHandleNullTextAsEmpty() {
        LlmResponse response = LlmResponse.of(null, List.of());

        assertThat(response.getTextContent()).isEmpty();
        assertThat(response.hasTextContent()).isFalse();
    }

    @Test
    @DisplayName("Should reject null tools uses in of()")
    void shouldRejectNullToolUsesInOf() {
        assertThatThrownBy(() -> LlmResponse.of("text", null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Tool uses cannot be null");
    }

    @Test
    @DisplayName("Should reject null text in text()")
    void shouldRejectNullTextInText() {
        assertThatThrownBy(() -> LlmResponse.text(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Text content cannot be null");
    }

    @Test
    @DisplayName("Should reject null tools uses in tools()")
    void shouldRejectNullToolUsesInTools() {
        assertThatThrownBy(() -> LlmResponse.tools(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Tool uses cannot be null");
    }

    @Test
    @DisplayName("Should reject empty tools uses in tools()")
    void shouldRejectEmptyToolUsesInTools() {
        assertThatThrownBy(() -> LlmResponse.tools(List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tool uses cannot be empty");
    }

    @Test
    @DisplayName("Should return immutable tools uses list")
    void shouldReturnImmutableToolUsesList() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));
        LlmResponse response = LlmResponse.tools(List.of(tool));

        assertThatThrownBy(() -> response.getToolUses().add(tool)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should create defensive copy of tools uses")
    void shouldCreateDefensiveCopyOfToolUses() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));
        List<ToolUse> toolList = new java.util.ArrayList<>();
        toolList.add(tool);

        LlmResponse response = LlmResponse.tools(toolList);

        // Modify original list
        toolList.clear();

        assertThat(response.getToolUses()).hasSize(1);
    }

    @Test
    @DisplayName("Should handle multiple tools uses")
    void shouldHandleMultipleToolUses() {
        ToolUse tool1 = ToolUse.of("id1", "bash", Map.of("command", "test1"));
        ToolUse tool2 = ToolUse.of("id2", "read", Map.of("path", "file.txt"));

        LlmResponse response = LlmResponse.tools(List.of(tool1, tool2));

        assertThat(response.getToolUses()).hasSize(2);
        assertThat(response.hasToolUses()).isTrue();
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCode() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));

        LlmResponse resp1 = LlmResponse.of("text", List.of(tool));
        LlmResponse resp2 = LlmResponse.of("text", List.of(tool));
        LlmResponse resp3 = LlmResponse.of("different", List.of(tool));

        assertThat(resp1).isEqualTo(resp2);
        assertThat(resp1.hashCode()).isEqualTo(resp2.hashCode());

        assertThat(resp1).isNotEqualTo(resp3);
        assertThat(resp1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));
        LlmResponse response = LlmResponse.of("Executing", List.of(tool));

        String toString = response.toString();

        assertThat(toString).contains("LlmResponse");
        assertThat(toString).contains("Executing");
        assertThat(toString).contains("tool(s)");
    }

    @Test
    @DisplayName("Should handle empty text content")
    void shouldHandleEmptyTextContent() {
        LlmResponse response = LlmResponse.text("");

        assertThat(response.getTextContent()).isEmpty();
        assertThat(response.hasTextContent()).isFalse();
    }

    @Test
    @DisplayName("Should handle response with no content")
    void shouldHandleResponseWithNoContent() {
        LlmResponse response = LlmResponse.of("", List.of());

        assertThat(response.hasTextContent()).isFalse();
        assertThat(response.hasToolUses()).isFalse();
    }

    @Test
    @DisplayName("Should round-trip MAX_TOKENS stop reason and report truncation")
    void shouldRoundTripMaxTokensStopReason() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));
        LlmResponse response = LlmResponse.of("text", List.of(tool), TokenUsage.empty(), StopReason.MAX_TOKENS);

        assertThat(response.getStopReason()).contains(StopReason.MAX_TOKENS);
        assertThat(response.getStopReason().get().isTruncated()).isTrue();
    }

    @Test
    @DisplayName("Should round-trip END_TURN stop reason as non-truncated")
    void shouldRoundTripEndTurnStopReason() {
        LlmResponse response = LlmResponse.of("text", List.of(), TokenUsage.empty(), StopReason.END_TURN);

        assertThat(response.getStopReason()).contains(StopReason.END_TURN);
        assertThat(response.getStopReason().get().isTruncated()).isFalse();
    }

    @Test
    @DisplayName("Should collapse UNKNOWN stop reason to empty")
    void shouldCollapseUnknownStopReasonToEmpty() {
        LlmResponse response = LlmResponse.of("text", List.of(), TokenUsage.empty(), StopReason.UNKNOWN);

        assertThat(response.getStopReason()).isEmpty();
    }

    @Test
    @DisplayName("Should default legacy factories to an empty stop reason")
    void shouldDefaultLegacyFactoriesToEmptyStopReason() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));

        assertThat(LlmResponse.of("text", List.of(tool), TokenUsage.empty()).getStopReason()).isEmpty();
        assertThat(LlmResponse.of("text", List.of(tool)).getStopReason()).isEmpty();
        assertThat(LlmResponse.text("text").getStopReason()).isEmpty();
        assertThat(LlmResponse.tools(List.of(tool)).getStopReason()).isEmpty();
    }

    @Test
    @DisplayName("Should not be equal when stop reason differs")
    void shouldNotBeEqualWhenStopReasonDiffers() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));
        LlmResponse maxTokens = LlmResponse.of("text", List.of(tool), TokenUsage.empty(), StopReason.MAX_TOKENS);
        LlmResponse endTurn = LlmResponse.of("text", List.of(tool), TokenUsage.empty(), StopReason.END_TURN);

        assertThat(maxTokens).isNotEqualTo(endTurn);
    }

    @Test
    @DisplayName("Should be equal and share hashCode when built identically with the same stop reason")
    void shouldBeEqualWhenStopReasonMatches() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));
        LlmResponse resp1 = LlmResponse.of("text", List.of(tool), TokenUsage.empty(), StopReason.MAX_TOKENS);
        LlmResponse resp2 = LlmResponse.of("text", List.of(tool), TokenUsage.empty(), StopReason.MAX_TOKENS);

        assertThat(resp1).isEqualTo(resp2);
        assertThat(resp1.hashCode()).isEqualTo(resp2.hashCode());
    }

    @Test
    @DisplayName("Should treat legacy 3-arg factory as equal to 4-arg factory with UNKNOWN stop reason")
    void shouldTreatLegacyFactoryAsEqualToUnknownStopReason() {
        ToolUse tool = ToolUse.of("id", "bash", Map.of("command", "test"));
        LlmResponse legacy = LlmResponse.of("text", List.of(tool), TokenUsage.empty());
        LlmResponse explicitUnknown = LlmResponse.of("text", List.of(tool), TokenUsage.empty(), StopReason.UNKNOWN);

        assertThat(legacy).isEqualTo(explicitUnknown);
        assertThat(legacy.hashCode()).isEqualTo(explicitUnknown.hashCode());
    }

    @Test
    @DisplayName("Should include stop reason in toString")
    void shouldIncludeStopReasonInToString() {
        LlmResponse response = LlmResponse.of("text", List.of(), TokenUsage.empty(), StopReason.MAX_TOKENS);

        assertThat(response.toString()).contains("MAX_TOKENS");
    }
}
