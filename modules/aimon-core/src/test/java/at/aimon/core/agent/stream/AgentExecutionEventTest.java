package at.aimon.core.agent.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.llm.TokenUsage;

@DisplayName("AgentExecutionEvent Tests")
class AgentExecutionEventTest {

    private static final Instant FIXED_TS = Instant.parse("2025-01-15T10:00:00Z");

    private static AgentRuntimeId ctx() {
        return AgentRuntimeIds.testCtx("ctx-1");
    }

    @Nested
    @DisplayName("Sealed hierarchy")
    class SealedHierarchyTests {

        @Test
        @DisplayName("permits exactly 15 subtypes")
        void permitsExactly15Subtypes() {
            Class<?>[] permitted = AgentExecutionEvent.class.getPermittedSubclasses();
            assertThat(permitted).hasSize(15).contains(IterationStarted.class, AssistantMessageReceived.class,
                    AssistantTextDelta.class, AssistantTextStreamReset.class, AssistantTextStreamCompleted.class,
                    ToolUseStarted.class, ToolResultReady.class, CompactBoundary.class, IterationCompleted.class,
                    ExecutionCompleted.class, ExecutionError.class, SkillTurnSuspendedEvent.class, InterruptedAt.class,
                    RejectedAt.class, SubagentTaskCompleted.class);
        }

        @Test
        @DisplayName("AgentExecutionEvent is sealed and abstract")
        void rootIsSealedAndAbstract() {
            assertThat(AgentExecutionEvent.class.isSealed()).isTrue();
            assertThat(java.lang.reflect.Modifier.isAbstract(AgentExecutionEvent.class.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("IterationStarted")
    class IterationStartedTests {

        @Test
        @DisplayName("Builder happy path sets all fields")
        void buildsAllFields() {
            IterationStarted e = IterationStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(3)
                    .plannedIteration(3).build();

            assertThat(e.getTimestamp()).isEqualTo(FIXED_TS);
            assertThat(e.getAgentRuntimeId()).isEqualTo(ctx());
            assertThat(e.getIteration()).isEqualTo(3);
            assertThat(e.getPlannedIteration()).isEqualTo(3);
            assertThat(e.toString()).contains("IterationStarted").contains("iteration=3")
                    .contains("plannedIteration=3");
        }

        @Test
        @DisplayName("Missing timestamp throws NPE")
        void missingTimestampThrows() {
            assertThatThrownBy(() -> IterationStarted.builder().agentRuntimeId(ctx()).iteration(1).build())
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Timestamp");
        }

        @Test
        @DisplayName("Missing agentRuntimeId throws NPE")
        void missingCtxIdThrows() {
            assertThatThrownBy(() -> IterationStarted.builder().timestamp(FIXED_TS).iteration(1).build())
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("AgentRuntimeId");
        }

        @Test
        @DisplayName("Negative iteration rejected")
        void negativeIterationRejected() {
            assertThatThrownBy(
                    () -> IterationStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(-1).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Iteration");
        }

        @Test
        @DisplayName("Negative plannedIteration rejected")
        void negativePlannedRejected() {
            assertThatThrownBy(() -> IterationStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .plannedIteration(-1).build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("plannedIteration");
        }

        @Test
        @DisplayName("equals/hashCode contract")
        void equalsHashCode() {
            IterationStarted a = IterationStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(2)
                    .plannedIteration(2).build();
            IterationStarted b = IterationStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(2)
                    .plannedIteration(2).build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(null).isNotEqualTo("other");
        }
    }

    @Nested
    @DisplayName("AssistantMessageReceived")
    class AssistantMessageReceivedTests {

        @Test
        @DisplayName("Builder happy path sets all fields")
        void buildsAllFields() {
            TokenUsage usage = TokenUsage.of(10, 5, 15);
            AssistantMessageReceived e = AssistantMessageReceived.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .iteration(1).messageSummary("hello world").tokenUsage(usage).build();

            assertThat(e.getMessageSummary()).isEqualTo("hello world");
            assertThat(e.getTokenUsage()).contains(usage);
            assertThat(e.toString()).contains("AssistantMessageReceived").contains("hello world");
        }

        @Test
        @DisplayName("tokenUsage defaults to empty Optional")
        void tokenUsageDefaultsEmpty() {
            AssistantMessageReceived e = AssistantMessageReceived.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .iteration(1).messageSummary("x").build();

            assertThat(e.getTokenUsage()).isEmpty();
        }

        @Test
        @DisplayName("Summary longer than 200 chars is truncated with ellipsis")
        void truncatesLongSummary() {
            String longSummary = "a".repeat(500);
            AssistantMessageReceived e = AssistantMessageReceived.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .iteration(1).messageSummary(longSummary).build();

            assertThat(e.getMessageSummary()).hasSize(AssistantMessageReceived.MAX_SUMMARY_LENGTH).endsWith("…");
        }

        @Test
        @DisplayName("Summary exactly 200 chars is not truncated")
        void summaryAtLimitNotTruncated() {
            String exact = "a".repeat(AssistantMessageReceived.MAX_SUMMARY_LENGTH);
            AssistantMessageReceived e = AssistantMessageReceived.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .iteration(1).messageSummary(exact).build();

            assertThat(e.getMessageSummary()).isEqualTo(exact).doesNotEndWith("…");
        }

        @Test
        @DisplayName("null messageSummary throws NPE")
        void nullSummaryRejected() {
            assertThatThrownBy(() -> AssistantMessageReceived.builder().messageSummary(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("messageSummary");
        }

        @Test
        @DisplayName("Missing required fields throw NPE")
        void missingRequired() {
            assertThatThrownBy(() -> AssistantMessageReceived.builder().agentRuntimeId(ctx()).iteration(1)
                    .messageSummary("x").build()).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> AssistantMessageReceived.builder().timestamp(FIXED_TS).iteration(1)
                    .messageSummary("x").build()).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> AssistantMessageReceived.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .iteration(1).build()).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("equals/hashCode contract")
        void equalsHashCode() {
            TokenUsage usage = TokenUsage.of(10, 5, 15);
            AssistantMessageReceived a = AssistantMessageReceived.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .iteration(1).messageSummary("x").tokenUsage(usage).build();
            AssistantMessageReceived b = AssistantMessageReceived.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .iteration(1).messageSummary("x").tokenUsage(usage).build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("ToolUseStarted")
    class ToolUseStartedTests {

        @Test
        @DisplayName("Builder happy path sets all fields")
        void buildsAllFields() {
            Map<String, Object> input = Map.of("path", "/tmp/a.txt");
            ToolUseStarted e = ToolUseStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(2)
                    .toolName("Read").toolUseId("tu-1").inputSummary(input).build();

            assertThat(e.getToolName()).isEqualTo("Read");
            assertThat(e.getToolUseId()).isEqualTo("tu-1");
            assertThat(e.getInputSummary()).containsEntry("path", "/tmp/a.txt");
        }

        @Test
        @DisplayName("null inputSummary is treated as empty map")
        void nullInputDefaultsEmpty() {
            ToolUseStarted e = ToolUseStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").build();

            assertThat(e.getInputSummary()).isEmpty();
        }

        @Test
        @DisplayName("inputSummary is unmodifiable after build")
        void inputSummaryIsUnmodifiable() {
            Map<String, Object> source = new HashMap<>();
            source.put("key", "value");
            ToolUseStarted e = ToolUseStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").inputSummary(source).build();

            // Mutating source does not affect event.
            source.put("extra", "x");
            assertThat(e.getInputSummary()).containsOnlyKeys("key").hasSize(1);

            // Event's map is itself unmodifiable.
            assertThatThrownBy(() -> e.getInputSummary().put("extra", "x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Missing toolName throws NPE")
        void missingToolNameThrows() {
            assertThatThrownBy(() -> ToolUseStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolUseId("tu-1").build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("toolName");
        }

        @Test
        @DisplayName("Empty toolName rejected")
        void emptyToolNameRejected() {
            assertThatThrownBy(() -> ToolUseStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("").toolUseId("tu-1").build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("toolName");
        }

        @Test
        @DisplayName("Missing toolUseId throws NPE")
        void missingToolUseIdThrows() {
            assertThatThrownBy(() -> ToolUseStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("toolUseId");
        }

        @Test
        @DisplayName("equals/hashCode contract")
        void equalsHashCode() {
            ToolUseStarted a = ToolUseStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").inputSummary(Map.of("k", "v")).build();
            ToolUseStarted b = ToolUseStarted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").inputSummary(Map.of("k", "v")).build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("ToolResultReady")
    class ToolResultReadyTests {

        @Test
        @DisplayName("Success happy path")
        void successHappyPath() {
            ToolResultReady e = ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").success(true).resultPreviewLength(120).build();

            assertThat(e.isSuccess()).isTrue();
            assertThat(e.getErrorMessage()).isEmpty();
            assertThat(e.getResultPreviewLength()).contains(120);
        }

        @Test
        @DisplayName("Error happy path")
        void errorHappyPath() {
            ToolResultReady e = ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").success(false).errorMessage("not found").build();

            assertThat(e.isSuccess()).isFalse();
            assertThat(e.getErrorMessage()).contains("not found");
        }

        @Test
        @DisplayName("success=true with non-empty errorMessage fails build")
        void successWithErrorRejected() {
            assertThatThrownBy(() -> ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").success(true).errorMessage("boom").build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("errorMessage");
        }

        @Test
        @DisplayName("success=false with empty/null errorMessage fails build")
        void errorWithoutMessageRejected() {
            assertThatThrownBy(() -> ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").success(false).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("errorMessage");

            assertThatThrownBy(() -> ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").success(false).errorMessage("").build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("errorMessage");

            assertThatThrownBy(() -> ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").success(false).errorMessage(null).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("errorMessage");
        }

        @Test
        @DisplayName("Missing toolName/toolUseId throws NPE")
        void missingRequired() {
            assertThatThrownBy(() -> ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolUseId("tu-1").success(true).build()).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").success(true).build()).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("equals/hashCode contract")
        void equalsHashCode() {
            ToolResultReady a = ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").success(true).resultPreviewLength(10).build();
            ToolResultReady b = ToolResultReady.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .toolName("Read").toolUseId("tu-1").success(true).resultPreviewLength(10).build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("CompactBoundary")
    class CompactBoundaryTests {

        @Test
        @DisplayName("Builder happy path sets all fields")
        void buildsAllFields() {
            CompactBoundary e = CompactBoundary.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(5)
                    .strategyName("summarize-old").messagesBefore(40).messagesAfter(12).build();

            assertThat(e.getStrategyName()).isEqualTo("summarize-old");
            assertThat(e.getMessagesBefore()).isEqualTo(40);
            assertThat(e.getMessagesAfter()).isEqualTo(12);
        }

        @Test
        @DisplayName("null strategyName rejected")
        void nullStrategyRejected() {
            assertThatThrownBy(() -> CompactBoundary.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .messagesBefore(5).messagesAfter(2).build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("strategyName");
        }

        @Test
        @DisplayName("Empty strategyName rejected")
        void emptyStrategyRejected() {
            assertThatThrownBy(() -> CompactBoundary.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .strategyName("").messagesBefore(5).messagesAfter(2).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strategyName");
        }

        @Test
        @DisplayName("Negative message counts rejected")
        void negativeCountsRejected() {
            assertThatThrownBy(() -> CompactBoundary.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .strategyName("s").messagesBefore(-1).messagesAfter(0).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("messagesBefore");

            assertThatThrownBy(() -> CompactBoundary.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .strategyName("s").messagesBefore(0).messagesAfter(-1).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("messagesAfter");
        }

        @Test
        @DisplayName("equals/hashCode contract")
        void equalsHashCode() {
            CompactBoundary a = CompactBoundary.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .strategyName("s").messagesBefore(10).messagesAfter(3).build();
            CompactBoundary b = CompactBoundary.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .strategyName("s").messagesBefore(10).messagesAfter(3).build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("IterationCompleted")
    class IterationCompletedTests {

        @Test
        @DisplayName("Builder happy path sets all fields")
        void buildsAllFields() {
            IterationCompleted e = IterationCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(4)
                    .completedIteration(4).willContinue(true).build();

            assertThat(e.getCompletedIteration()).isEqualTo(4);
            assertThat(e.isWillContinue()).isTrue();
        }

        @Test
        @DisplayName("Negative completedIteration rejected")
        void negativeCompletedRejected() {
            assertThatThrownBy(() -> IterationCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .completedIteration(-1).build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("completedIteration");
        }

        @Test
        @DisplayName("Missing required fields throw NPE")
        void missingRequired() {
            assertThatThrownBy(() -> IterationCompleted.builder().agentRuntimeId(ctx()).iteration(1).build())
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IterationCompleted.builder().timestamp(FIXED_TS).iteration(1).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("equals/hashCode contract")
        void equalsHashCode() {
            IterationCompleted a = IterationCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .completedIteration(1).willContinue(false).build();
            IterationCompleted b = IterationCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(1)
                    .completedIteration(1).willContinue(false).build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("ExecutionCompleted")
    class ExecutionCompletedTests {

        @Test
        @DisplayName("Builder happy path sets all fields")
        void buildsAllFields() {
            ExecutionCompleted e = ExecutionCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).iteration(0)
                    .completionReason(CompletionReason.COMPLETED).totalIterations(7).elapsed(Duration.ofMillis(250))
                    .build();

            assertThat(e.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(e.getTotalIterations()).isEqualTo(7);
            assertThat(e.getElapsed()).contains(Duration.ofMillis(250));
            assertThat(e.getIteration()).isZero();
        }

        @Test
        @DisplayName("elapsed defaults to empty Optional")
        void elapsedDefaultsEmpty() {
            ExecutionCompleted e = ExecutionCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .completionReason(CompletionReason.MAX_ITERATIONS).totalIterations(10).build();

            assertThat(e.getElapsed()).isEmpty();
        }

        @Test
        @DisplayName("Missing completionReason throws NPE")
        void missingReasonThrows() {
            assertThatThrownBy(() -> ExecutionCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .totalIterations(1).build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("completionReason");
        }

        @Test
        @DisplayName("Negative totalIterations rejected")
        void negativeIterationsRejected() {
            assertThatThrownBy(() -> ExecutionCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .completionReason(CompletionReason.COMPLETED).totalIterations(-1).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalIterations");
        }

        @Test
        @DisplayName("equals/hashCode contract")
        void equalsHashCode() {
            ExecutionCompleted a = ExecutionCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .completionReason(CompletionReason.COMPLETED).totalIterations(3).elapsed(Duration.ofSeconds(1))
                    .build();
            ExecutionCompleted b = ExecutionCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .completionReason(CompletionReason.COMPLETED).totalIterations(3).elapsed(Duration.ofSeconds(1))
                    .build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("ExecutionError")
    class ExecutionErrorTests {

        @Test
        @DisplayName("Builder happy path sets all fields")
        void buildsAllFields() {
            RuntimeException ex = new RuntimeException("boom");
            ExecutionError e = ExecutionError.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .errorMessage("unhandled failure").cause(ex).completionReason(CompletionReason.ERROR).build();

            assertThat(e.getErrorMessage()).isEqualTo("unhandled failure");
            assertThat(e.getCause()).contains(ex);
            assertThat(e.getCompletionReason()).contains(CompletionReason.ERROR);
        }

        @Test
        @DisplayName("cause and completionReason default to empty Optional")
        void defaultsEmpty() {
            ExecutionError e = ExecutionError.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).errorMessage("oops")
                    .build();

            assertThat(e.getCause()).isEmpty();
            assertThat(e.getCompletionReason()).isEmpty();
        }

        @Test
        @DisplayName("null errorMessage throws NPE")
        void nullMessageThrows() {
            assertThatThrownBy(() -> ExecutionError.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).build())
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("errorMessage");
        }

        @Test
        @DisplayName("Empty errorMessage rejected")
        void emptyMessageRejected() {
            assertThatThrownBy(
                    () -> ExecutionError.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).errorMessage("").build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("errorMessage");
        }

        @Test
        @DisplayName("equals/hashCode contract")
        void equalsHashCode() {
            RuntimeException ex = new RuntimeException("boom");
            ExecutionError a = ExecutionError.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).errorMessage("fail")
                    .cause(ex).completionReason(CompletionReason.ERROR).build();
            ExecutionError b = ExecutionError.builder().timestamp(FIXED_TS).agentRuntimeId(ctx()).errorMessage("fail")
                    .cause(ex).completionReason(CompletionReason.ERROR).build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("SubagentTaskCompleted")
    class SubagentTaskCompletedTests {

        @Test
        @DisplayName("Builder happy path sets all fields")
        void buildsAllFields() {
            SubagentTaskCompleted e = SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .taskId("task-1").subagentName("researcher").outcome(SubagentTaskCompleted.Outcome.COMPLETED)
                    .detail("done: 3 files").build();

            assertThat(e.getTaskId()).isEqualTo("task-1");
            assertThat(e.getSubagentName()).isEqualTo("researcher");
            assertThat(e.getOutcome()).isEqualTo(SubagentTaskCompleted.Outcome.COMPLETED);
            assertThat(e.isSuccess()).isTrue();
            assertThat(e.getDetail()).contains("done: 3 files");
            assertThat(e.getIteration()).isZero();
            assertThat(e.toString()).contains("SubagentTaskCompleted").contains("taskId='task-1'")
                    .contains("subagentName='researcher'").contains("outcome=COMPLETED");
        }

        @Test
        @DisplayName("detail defaults to empty Optional and null/empty clears it")
        void detailDefaultsEmpty() {
            SubagentTaskCompleted noDetail = SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .taskId("t").subagentName("s").outcome(SubagentTaskCompleted.Outcome.FAILED).build();
            assertThat(noDetail.getDetail()).isEmpty();
            assertThat(noDetail.isSuccess()).isFalse();

            SubagentTaskCompleted cleared = SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .taskId("t").subagentName("s").outcome(SubagentTaskCompleted.Outcome.KILLED).detail("x").detail("")
                    .build();
            assertThat(cleared.getDetail()).isEmpty();
        }

        @Test
        @DisplayName("Missing required fields throw NPE")
        void missingRequiredThrows() {
            assertThatThrownBy(() -> SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .subagentName("s").outcome(SubagentTaskCompleted.Outcome.COMPLETED).build())
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("taskId");
            assertThatThrownBy(() -> SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .taskId("t").outcome(SubagentTaskCompleted.Outcome.COMPLETED).build())
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("subagentName");
            assertThatThrownBy(() -> SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .taskId("t").subagentName("s").build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("outcome");
        }

        @Test
        @DisplayName("Empty taskId/subagentName rejected")
        void emptyIdentifiersRejected() {
            assertThatThrownBy(() -> SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .taskId("").subagentName("s").outcome(SubagentTaskCompleted.Outcome.COMPLETED).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("taskId");
            assertThatThrownBy(() -> SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .taskId("t").subagentName("").outcome(SubagentTaskCompleted.Outcome.COMPLETED).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subagentName");
        }

        @Test
        @DisplayName("equals/hashCode contract")
        void equalsHashCode() {
            SubagentTaskCompleted a = SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .taskId("t").subagentName("s").outcome(SubagentTaskCompleted.Outcome.COMPLETED).detail("d").build();
            SubagentTaskCompleted b = SubagentTaskCompleted.builder().timestamp(FIXED_TS).agentRuntimeId(ctx())
                    .taskId("t").subagentName("s").outcome(SubagentTaskCompleted.Outcome.COMPLETED).detail("d").build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }
}
