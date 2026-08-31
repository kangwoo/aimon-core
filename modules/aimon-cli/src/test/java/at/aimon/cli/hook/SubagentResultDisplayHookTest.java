package at.aimon.cli.hook;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.task.TaskTool;

@DisplayName("SubagentResultDisplayHook Tests")
class SubagentResultDisplayHookTest {

    private OutputFormatter outputFormatter;
    private SubagentResultDisplayHook hook;

    @BeforeEach
    void setUp() {
        CliSettings settings = new CliSettings();
        settings.setShowToolCalls(true);
        settings.setColorOutput(false);
        outputFormatter = spy(new OutputFormatter(settings));
        hook = new SubagentResultDisplayHook(outputFormatter);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw NullPointerException for null OutputFormatter")
        void shouldThrowNullPointerExceptionForNullOutputFormatter() {
            assertThatThrownBy(() -> new SubagentResultDisplayHook(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("OutputFormatter cannot be null");
        }
    }

    @Nested
    @DisplayName("Execute with Task tool")
    class ExecuteWithTaskTool {

        @Test
        @DisplayName("Should return success HookResult for successful Task execution")
        void shouldReturnSuccessForSuccessfulTaskExecution() {
            String content = buildSubagentResultContent("test-agent", "test task description", "SUCCESS", 3, 1500,
                    "Task completed successfully");

            PostToolContext context = createPostToolContext(TaskTool.TOOL_NAME,
                    ToolUseResult.success("tool-1", content), InvokerType.MAIN_AGENT);

            HookResult result = hook.execute(context);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
            assertThat(result.isBlocked()).isFalse();
        }

        @Test
        @DisplayName("Should parse valid subagent result content and call displaySubagentResult")
        void shouldParseValidSubagentResultContent() {
            String content = buildSubagentResultContent("test-agent", "test task description", "SUCCESS", 3, 1500,
                    "Task completed successfully");

            PostToolContext context = createPostToolContext(TaskTool.TOOL_NAME,
                    ToolUseResult.success("tool-1", content), InvokerType.MAIN_AGENT);

            hook.execute(context);

            verify(outputFormatter).displaySubagentResult("", "test-agent", "test task description", "SUCCESS", 3, 1500,
                    "Task completed successfully");
        }

        @Test
        @DisplayName("Should use correct indentation for SUBAGENT invoker type")
        void shouldUseCorrectIndentationForSubagent() {
            String content = buildSubagentResultContent("nested-agent", "nested task", "SUCCESS", 5, 2500,
                    "Nested task done");

            PostToolContext context = createPostToolContext(TaskTool.TOOL_NAME,
                    ToolUseResult.success("tool-2", content), InvokerType.SUBAGENT);

            hook.execute(context);

            verify(outputFormatter).displaySubagentResult("  ", "nested-agent", "nested task", "SUCCESS", 5, 2500,
                    "Nested task done");
        }
    }

    @Nested
    @DisplayName("Execute with non-Task tool")
    class ExecuteWithNonTaskTool {

        @Test
        @DisplayName("Should return success HookResult for non-Task tool without processing")
        void shouldReturnSuccessForNonTaskTool() {
            PostToolContext context = createPostToolContext("Read", ToolUseResult.success("tool-1", "file content"),
                    InvokerType.MAIN_AGENT);

            HookResult result = hook.execute(context);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
            verify(outputFormatter, never()).displaySubagentResult(anyString(), anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), anyString());
        }
    }

    @Nested
    @DisplayName("Execute with failed Task result")
    class ExecuteWithFailedTaskResult {

        @Test
        @DisplayName("Should return success HookResult for failed Task result without processing")
        void shouldReturnSuccessForFailedTaskResult() {
            PostToolContext context = createPostToolContext(TaskTool.TOOL_NAME,
                    ToolUseResult.error("tool-1", "Task execution failed"), InvokerType.MAIN_AGENT);

            HookResult result = hook.execute(context);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
            verify(outputFormatter, never()).displaySubagentResult(anyString(), anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), anyString());
        }
    }

    @Nested
    @DisplayName("Content handling edge cases")
    class ContentHandlingEdgeCases {

        @Test
        @DisplayName("Should handle null content gracefully")
        void shouldHandleNullContentGracefully() {
            // ToolUseResult.success requires non-null content, so we use a mock
            PostToolContext context = mock(PostToolContext.class);
            ToolUse toolUse = ToolUse.of("tool-1", TaskTool.TOOL_NAME, Map.of());
            ToolUseResult toolUseResult = mock(ToolUseResult.class);
            when(toolUseResult.isSuccess()).thenReturn(true);
            when(toolUseResult.getContent()).thenReturn(null);

            when(context.getToolUse()).thenReturn(toolUse);
            when(context.getCurrentToolUseResult()).thenReturn(toolUseResult);
            when(context.getInvokerType()).thenReturn(InvokerType.MAIN_AGENT);

            HookResult result = hook.execute(context);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
            verify(outputFormatter, never()).displaySubagentResult(anyString(), anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Should handle empty content gracefully")
        void shouldHandleEmptyContentGracefully() {
            PostToolContext context = createPostToolContext(TaskTool.TOOL_NAME, ToolUseResult.success("tool-1", ""),
                    InvokerType.MAIN_AGENT);

            HookResult result = hook.execute(context);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
            verify(outputFormatter, never()).displaySubagentResult(anyString(), anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Should handle content that does not match subagent result pattern")
        void shouldHandleNonMatchingContent() {
            PostToolContext context = createPostToolContext(TaskTool.TOOL_NAME,
                    ToolUseResult.success("tool-1", "Some plain task output without subagent result"),
                    InvokerType.MAIN_AGENT);

            HookResult result = hook.execute(context);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
            verify(outputFormatter, never()).displaySubagentResult(anyString(), anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), anyString());
        }
    }

    /**
     * Creates a mock PostToolContext with the given parameters.
     */
    private PostToolContext createPostToolContext(String toolName, ToolUseResult toolUseResult,
            InvokerType invokerType) {
        PostToolContext context = mock(PostToolContext.class);
        ToolUse toolUse = ToolUse.of("tool-1", toolName, Map.of());

        when(context.getToolUse()).thenReturn(toolUse);
        when(context.getCurrentToolUseResult()).thenReturn(toolUseResult);
        when(context.getInvokerType()).thenReturn(invokerType);

        return context;
    }

    /**
     * Builds a valid subagent result content string.
     */
    private String buildSubagentResultContent(String subagentName, String task, String status, int iterations,
            int tokens, String resultText) {
        return "=== Subagent Task Result ===\n" + "Subagent: " + subagentName + "\n" + "Task: " + task + "\n"
                + "Status: " + status + "\n" + "Iterations: " + iterations + "\n" + "Tokens: " + tokens + "\n" + "\n"
                + "Result:\n" + resultText;
    }
}
