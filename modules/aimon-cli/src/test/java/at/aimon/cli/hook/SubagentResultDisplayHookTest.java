package at.aimon.cli.hook;

import static org.assertj.core.api.Assertions.*;
import static org.fusesource.jansi.Ansi.ansi;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.TruncatedResponses;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.tools.ToolContextKeys;
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
     * Feeds the hook what {@link TaskTool} itself prints, so a change to that text the hook no longer parses fails
     * here (#133). {@code TaskTool} runs over a mocked execution manager. These tests supply values — the answer, the
     * counts, the completion reason — and never one of {@code TaskTool}'s labels.
     */
    @Nested
    @DisplayName("With TaskTool's own output")
    class WithTaskToolsOwnOutput {

        private final String subagent = "explorer";
        private final String description = "map the module";
        private final String answer = "The module has three packages: api, impl, and";

        private SubagentExecutionManager executionManager;
        private TaskTool taskTool;

        @BeforeEach
        void setUpTaskTool() {
            SubagentRegistry subagentRegistry = mock(SubagentRegistry.class);
            lenient().when(subagentRegistry.getAllSubagents()).thenReturn(List.of());
            executionManager = mock(SubagentExecutionManager.class);
            taskTool = new TaskTool(mock(LlmModel.class), subagentRegistry, mock(ToolRegistry.class),
                    mock(HookRegistry.class), mock(Environment.class), executionManager);
        }

        @Test
        @DisplayName("a cut fork's Completion reason line is handed over apart from its answer")
        void aCutForksReasonLineIsHandedOverApartFromItsAnswer() {
            String cutAnswer = answer + TruncatedResponses.TRUNCATION_MARKER;
            SubagentExecutionResult cut = SubagentExecutionResult.success(cutAnswer, snapshot(), metadata(),
                    CompletionReason.TRUNCATED);
            String printed = printedByTaskTool(cut);
            String reasonLine = lastLine(printed);

            hook.execute(createPostToolContext(TaskTool.TOOL_NAME, ToolUseResult.success("tool-1", printed),
                    InvokerType.MAIN_AGENT));

            assertThat(reasonLine).contains(CompletionReason.TRUNCATED.name());
            verify(outputFormatter).displaySubagentResult("", subagent, description, cut.getStatus(), 3, 300,
                    cutAnswer);
            verify(outputFormatter).displaySubagentCompletionReason("", cut.getStatus(), reasonLine);
        }

        @Test
        @DisplayName("a completed fork hands over its whole answer and no Completion reason line")
        void aCompletedForkHandsOverItsWholeAnswerAndNoReasonLine() {
            SubagentExecutionResult completed = SubagentExecutionResult.success(answer, snapshot(), metadata());

            hook.execute(createPostToolContext(TaskTool.TOOL_NAME,
                    ToolUseResult.success("tool-1", printedByTaskTool(completed)), InvokerType.MAIN_AGENT));

            verify(outputFormatter).displaySubagentResult("", subagent, description, completed.getStatus(), 3, 300,
                    answer);
            verify(outputFormatter, never()).displaySubagentCompletionReason(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a stalled fork's Completion reason line is handed over apart from its stop message")
        void aStalledForksReasonLineIsHandedOverApartFromItsStopMessage() {
            String stopMessage = "the fork stopped: its last three iterations made no progress";
            SubagentExecutionResult stalled = SubagentExecutionResult.failure(stopMessage, snapshot(), metadata(),
                    CompletionReason.ERROR);
            String printed = printedByTaskTool(stalled);
            String reasonLine = lastLine(printed);

            hook.execute(createPostToolContext(TaskTool.TOOL_NAME, ToolUseResult.success("tool-1", printed),
                    InvokerType.MAIN_AGENT));

            assertThat(reasonLine).contains(CompletionReason.ERROR.name());
            verify(outputFormatter).displaySubagentResult("", subagent, description, stalled.getStatus(), 3, 300,
                    stopMessage);
            verify(outputFormatter).displaySubagentCompletionReason("", stalled.getStatus(), reasonLine);
        }

        @Test
        @DisplayName("a cut fork's Completion reason line is not printed in the success colour")
        void aCutForksReasonLineIsNotPrintedInTheSuccessColour() {
            CliSettings colourSettings = new CliSettings();
            colourSettings.setShowToolCalls(true);
            colourSettings.setColorOutput(true);
            SubagentResultDisplayHook colourHook = new SubagentResultDisplayHook(new OutputFormatter(colourSettings));
            String printed = printedByTaskTool(SubagentExecutionResult.success(
                    answer + TruncatedResponses.TRUNCATION_MARKER, snapshot(), metadata(), CompletionReason.TRUNCATED));
            String reasonLine = lastLine(printed);

            PrintStream originalOut = System.out;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            try {
                colourHook.execute(createPostToolContext(TaskTool.TOOL_NAME, ToolUseResult.success("tool-1", printed),
                        InvokerType.MAIN_AGENT));
            } finally {
                System.setOut(originalOut);
            }

            assertThat(captured.toString(StandardCharsets.UTF_8))
                    .contains(ansi().fgYellow().a(reasonLine).reset().toString())
                    .doesNotContain(ansi().fgGreen().a(reasonLine).reset().toString());
        }

        /** Runs {@link TaskTool} in the foreground over a subagent returning {@code result}, and returns its text. */
        private String printedByTaskTool(SubagentExecutionResult result) {
            when(executionManager.execute(any(SubagentExecutionEnvironment.class), anyString(), eq(subagent),
                    anyString(), eq(description))).thenReturn(result);
            ToolResult toolResult = taskTool.execute(
                    ToolInput.of(Map.of("subagent_name", subagent, "prompt", "map it", "description", description)),
                    ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test"))
                            .build());
            assertThat(toolResult.isSuccess()).isTrue();
            return toolResult.getContent();
        }

        private String lastLine(String text) {
            return text.lines().reduce((previous, next) -> next).orElseThrow();
        }

        private SessionSnapshot snapshot() {
            return SessionSnapshot.of(SessionId.generate(), "sys", List.of());
        }

        private ExecutionMetadata metadata() {
            Instant now = Instant.now();
            return ExecutionMetadata.builder().iterationCount(3).tokenUsage(TokenUsage.of(100, 200, 300))
                    .timestamps(now, now).build();
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
