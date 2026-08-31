package at.aimon.core.agent.tool.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.ToolUseResult;

@DisplayName("ToolExecutionResultConverter Tests")
class ToolExecutionResultConverterTest {

    @Test
    @DisplayName("Should convert successful ToolExecutionResult to ToolUseResult")
    void testToToolUseResult_Success_Converted() {
        // Given
        String toolExecutionId = "tool_123";
        String content = "Operation completed successfully";
        ToolResult toolResult = ToolResult.success(content);
        ToolExecutionResult executionResult = ToolExecutionResult.of(toolExecutionId, toolResult);

        // When
        ToolUseResult result = ToolExecutionResultConverter.toToolUseResult(executionResult);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getToolUseId()).isEqualTo(toolExecutionId);
        assertThat(result.getContent()).isEqualTo(content);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isError()).isFalse();
    }

    @Test
    @DisplayName("Should convert error ToolExecutionResult to ToolUseResult")
    void testToToolUseResult_Error_Converted() {
        // Given
        String toolExecutionId = "tool_456";
        String errorMessage = "Permission denied";
        ToolResult toolResult = ToolResult.error(errorMessage);
        ToolExecutionResult executionResult = ToolExecutionResult.of(toolExecutionId, toolResult);

        // When
        ToolUseResult result = ToolExecutionResultConverter.toToolUseResult(executionResult);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getToolUseId()).isEqualTo(toolExecutionId);
        assertThat(result.getContent()).isEqualTo(errorMessage);
        assertThat(result.isError()).isTrue();
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("Should throw NullPointerException when executionResult is null")
    void testToToolUseResult_NullInput_ThrowsException() {
        // When & Then
        assertThatThrownBy(() -> ToolExecutionResultConverter.toToolUseResult(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("ToolExecutionResult cannot be null");
    }

    @Test
    @DisplayName("Should preserve toolUseId exactly as provided")
    void testToToolUseResult_ToolUseIdPreserved() {
        // Given
        String toolExecutionId = "custom_tool_id_789";
        ToolResult toolResult = ToolResult.success("data");
        ToolExecutionResult executionResult = ToolExecutionResult.of(toolExecutionId, toolResult);

        // When
        ToolUseResult result = ToolExecutionResultConverter.toToolUseResult(executionResult);

        // Then
        assertThat(result.getToolUseId()).isEqualTo(toolExecutionId);
    }

    @Test
    @DisplayName("Should preserve content exactly as provided in success case")
    void testToToolUseResult_SuccessContentPreserved() {
        // Given
        String content = "Complex\nmultiline\ncontent\nwith special chars: @#$%";
        ToolResult toolResult = ToolResult.success(content);
        ToolExecutionResult executionResult = ToolExecutionResult.of("tool_id", toolResult);

        // When
        ToolUseResult result = ToolExecutionResultConverter.toToolUseResult(executionResult);

        // Then
        assertThat(result.getContent()).isEqualTo(content);
    }

    @Test
    @DisplayName("Should preserve error message exactly as provided in error case")
    void testToToolUseResult_ErrorMessagePreserved() {
        // Given
        String errorMessage = "Error: Connection timeout after 30 seconds\nStack trace: ...";
        ToolResult toolResult = ToolResult.error(errorMessage);
        ToolExecutionResult executionResult = ToolExecutionResult.of("tool_id", toolResult);

        // When
        ToolUseResult result = ToolExecutionResultConverter.toToolUseResult(executionResult);

        // Then
        assertThat(result.getContent()).isEqualTo(errorMessage);
    }

    @Test
    @DisplayName("Should propagate render payload from ToolResult to ToolUseResult")
    void testToToolUseResult_RenderPayloadPropagated() {
        // Given
        Map<String, Object> payload = Map.of("kind", "metric-series", "block", Map.of("series", "cpu.usage"));
        ToolResult toolResult = ToolResult.success("body").withRenderPayload(payload);
        ToolExecutionResult executionResult = ToolExecutionResult.of("tool_render_1", toolResult);

        // When
        ToolUseResult result = ToolExecutionResultConverter.toToolUseResult(executionResult);

        // Then
        assertThat(result.getToolUseId()).isEqualTo("tool_render_1");
        assertThat(result.getRenderPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should propagate render payload on error results")
    void testToToolUseResult_RenderPayloadPropagated_OnError() {
        // Given
        Map<String, Object> payload = Map.of("kind", "retry-hint");
        ToolResult toolResult = ToolResult.error("failed").withRenderPayload(payload);
        ToolExecutionResult executionResult = ToolExecutionResult.of("tool_err_1", toolResult);

        // When
        ToolUseResult result = ToolExecutionResultConverter.toToolUseResult(executionResult);

        // Then
        assertThat(result.isError()).isTrue();
        assertThat(result.getRenderPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should leave render payload null when ToolResult has none")
    void testToToolUseResult_NoRenderPayload_StaysNull() {
        // Given
        ToolResult toolResult = ToolResult.success("body");
        ToolExecutionResult executionResult = ToolExecutionResult.of("tool_plain", toolResult);

        // When
        ToolUseResult result = ToolExecutionResultConverter.toToolUseResult(executionResult);

        // Then
        assertThat(result.getRenderPayload()).isNull();
    }
}
