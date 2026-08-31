package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentResultFormatter;

class TaskResultTest {

    private static SessionSnapshot snapshot() {
        return SessionSnapshot.of(SessionId.of("task-session"), "sys", List.of(Message.user("do it")));
    }

    private static ExecutionMetadata metadata(int iterations, int tokens, long durationMillis) {
        final Instant start = Instant.parse("2026-07-08T10:00:00Z");
        return ExecutionMetadata.builder().iterationCount(iterations).tokenUsage(TokenUsage.of(1, tokens - 1, tokens))
                .timestamps(start, start.plusMillis(durationMillis)).build();
    }

    @Test
    void fromRejectsNullSource() {
        assertThatNullPointerException().isThrownBy(() -> TaskResult.from(null));
    }

    @Test
    void fromProjectsSuccessfulResult() {
        final SubagentExecutionResult source = SubagentExecutionResult.success("the answer", snapshot(),
                metadata(3, 100, 1_500));

        final TaskResult result = TaskResult.from(source);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getFinalAnswer()).contains("the answer");
        assertThat(result.getErrorMessage()).isEmpty();
        assertThat(result.getSummary()).isEqualTo("the answer");
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.getIterationCount()).isEqualTo(3);
        assertThat(result.getTotalTokens()).isEqualTo(100);
        assertThat(result.getDurationMillis()).isEqualTo(1_500L);
        assertThat(result.getDuration()).isEqualTo(Duration.ofMillis(1_500));
        assertThat(result.isSummaryTruncated()).isFalse();
    }

    @Test
    void fromProjectsFailedResult() {
        final SubagentExecutionResult source = SubagentExecutionResult.failure("kaboom", snapshot(),
                metadata(1, 10, 250), CompletionReason.ERROR);

        final TaskResult result = TaskResult.from(source);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo("FAILURE");
        assertThat(result.getFinalAnswer()).isEmpty();
        assertThat(result.getErrorMessage()).contains("kaboom");
        assertThat(result.getSummary()).isEqualTo("kaboom");
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
    }

    @Test
    void fromCarriesTheCompletionReasonThatEndedTheLoop() {
        final SubagentExecutionResult source = SubagentExecutionResult.failure("out of budget", snapshot(),
                metadata(9, 50, 10), CompletionReason.MAX_ITERATIONS);

        assertThat(TaskResult.from(source).getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
    }

    @Test
    void fromKeepsTheTailAndFlagsTruncationWhenTheAnswerExceedsTheStorageCap() {
        final String huge = "A".repeat(200) + "the conclusion";
        final SubagentExecutionResult source = SubagentExecutionResult.success(huge, snapshot(), metadata(1, 2, 0));

        final TaskResult result = TaskResult.from(source, 20);

        assertThat(result.isSummaryTruncated()).isTrue();
        // Tail-keep: agent conclusions land at the end, so the end is what survives.
        assertThat(result.getSummary()).endsWith("the conclusion").contains("chars omitted");
        assertThat(result.getFinalAnswer()).get().asString().doesNotContain("A".repeat(200));
    }

    @Test
    void fromFlagsTruncationEvenWhenTheMarkerHappensToRestoreTheOriginalLength() {
        // The elision marker "…[21 chars omitted]…\n" is itself exactly 21 characters, so an answer overflowing the
        // cap by exactly that much comes back the same length it went in. Length alone cannot decide truncation.
        final String justOver = "A".repeat(21) + "the conclusion";
        final SubagentExecutionResult source = SubagentExecutionResult.success(justOver, snapshot(), metadata(1, 2, 0));

        final TaskResult result = TaskResult.from(source, "the conclusion".length());

        assertThat(result.getSummary()).hasSameSizeAs(justOver).contains("21 chars omitted");
        assertThat(result.isSummaryTruncated()).isTrue();
    }

    @Test
    void fromDoesNotFlagTruncationWhenTheAnswerFits() {
        final SubagentExecutionResult source = SubagentExecutionResult.success("short", snapshot(), metadata(1, 2, 0));

        assertThat(TaskResult.from(source, 1_000).isSummaryTruncated()).isFalse();
    }

    @Test
    void fromWithNonPositiveCapStoresTheAnswerUnbounded() {
        final String huge = "A".repeat(5_000);
        final SubagentExecutionResult source = SubagentExecutionResult.success(huge, snapshot(), metadata(1, 2, 0));

        final TaskResult result = TaskResult.from(source, 0);

        assertThat(result.isSummaryTruncated()).isFalse();
        assertThat(result.getFinalAnswer()).contains(huge);
    }

    @Test
    void defaultStorageCapIsLargerThanTheInlineCap() {
        // The storage cap must not lose text the inline path would still have shown.
        assertThat(TaskResult.DEFAULT_MAX_SUMMARY_CHARS).isGreaterThan(SubagentResultFormatter.DEFAULT_MAX_CHARS);
    }

    @Test
    void summaryIsEmptyStringWhenNoTextWasStored() {
        final TaskResult result = TaskResult.builder().success(true).build();

        assertThat(result.getSummary()).isEmpty();
        assertThat(result.getFinalAnswer()).isEmpty();
    }

    @Test
    void builderDefaultsCompletionReasonToCompleted() {
        assertThat(TaskResult.builder().build().getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    void builderRejectsNullCompletionReason() {
        assertThatNullPointerException().isThrownBy(() -> TaskResult.builder().completionReason(null).build());
    }

    @Test
    void builderClampsNegativeCounters() {
        final TaskResult result = TaskResult.builder().iterationCount(-3).durationMillis(-7L).totalTokens(-1).build();

        assertThat(result.getIterationCount()).isZero();
        assertThat(result.getDurationMillis()).isZero();
        assertThat(result.getTotalTokens()).isZero();
    }

    @Test
    void equalsAndHashCodeCompareEveryStoredField() {
        final TaskResult one = TaskResult.builder().success(true).finalAnswer("a").iterationCount(2).durationMillis(5L)
                .totalTokens(9).build();
        final TaskResult same = TaskResult.builder().success(true).finalAnswer("a").iterationCount(2).durationMillis(5L)
                .totalTokens(9).build();
        final TaskResult different = TaskResult.builder().success(true).finalAnswer("b").iterationCount(2)
                .durationMillis(5L).totalTokens(9).build();

        assertThat(one).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(different);
        assertThat(one).isNotEqualTo(null).isNotEqualTo("not a result");
    }

    @Test
    void toStringSummarizesWithoutDumpingTheAnswer() {
        final TaskResult result = TaskResult.builder().success(true).finalAnswer("x".repeat(50)).iterationCount(2)
                .totalTokens(9).summaryTruncated(true).build();

        assertThat(result.toString()).contains("status=SUCCESS").contains("summaryChars=50").contains("truncated")
                .doesNotContain("x".repeat(50));
    }
}
