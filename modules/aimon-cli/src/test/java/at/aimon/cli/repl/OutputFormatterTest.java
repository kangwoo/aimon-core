package at.aimon.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.CliSettings;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AssistantTextDelta;
import at.aimon.core.agent.stream.AssistantTextStreamCompleted;
import at.aimon.core.agent.stream.AssistantTextStreamReset;
import at.aimon.core.agent.stream.SubagentTaskCompleted;
import at.aimon.core.command.execution.ExecutionMetadata;

@DisplayName("OutputFormatter Tests")
class OutputFormatterTest {

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    private CliSettings settings;
    private OutputFormatter formatter;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(outputStream));
        settings = new CliSettings();
        settings.setColorOutput(false);
        formatter = new OutputFormatter(settings);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private String getOutput() {
        return outputStream.toString();
    }

    private SessionSnapshot createSessionRecord() {
        return SessionSnapshot.of(SessionId.of("test"));
    }

    private ExecutionMetadata createMetadata(int iterationCount) {
        Instant start = Instant.now();
        Instant end = start.plus(Duration.ofSeconds(1));
        return ExecutionMetadata.builder().iterationCount(iterationCount).duration(Duration.ofSeconds(1))
                .startTime(start).endTime(end).build();
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw NullPointerException for null settings")
        void shouldThrowNullPointerExceptionForNullSettings() {
            assertThatThrownBy(() -> new OutputFormatter(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("settings cannot be null");
        }
    }

    @Nested
    @DisplayName("displayInfo")
    class DisplayInfo {

        @Test
        @DisplayName("Should print message")
        void shouldPrintMessage() {
            formatter.displayInfo("Processing request...");

            assertThat(getOutput()).contains("Processing request...");
        }
    }

    @Nested
    @DisplayName("displayError")
    class DisplayError {

        @Test
        @DisplayName("Should print 'Error: ' followed by message")
        void shouldPrintErrorMessage() {
            formatter.displayError("Connection failed");

            assertThat(getOutput()).contains("Error: Connection failed");
        }
    }

    @Nested
    @DisplayName("displayGoodbye")
    class DisplayGoodbye {

        @Test
        @DisplayName("Should print 'Goodbye!'")
        void shouldPrintGoodbye() {
            formatter.displayGoodbye();

            assertThat(getOutput()).contains("Goodbye!");
        }
    }

    @Nested
    @DisplayName("displayResult")
    class DisplayResult {

        @Test
        @DisplayName("Should print final answer on success")
        void shouldPrintFinalAnswerOnSuccess() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("The answer is 42",
                    createSessionRecord(), createMetadata(3));

            formatter.displayResult(result);

            assertThat(getOutput()).contains("The answer is 42");
        }

        @Test
        @DisplayName("Should print iteration count when showIterations is true")
        void shouldPrintIterationCountWhenEnabled() {
            settings.setShowIterations(true);
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("Done", createSessionRecord(),
                    createMetadata(3));

            formatter.displayResult(result);

            assertThat(getOutput()).contains("[Completed in 3 iteration(s)]");
        }

        @Test
        @DisplayName("Should not print iterations when showIterations is false")
        void shouldNotPrintIterationsWhenDisabled() {
            settings.setShowIterations(false);
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("Done", createSessionRecord(),
                    createMetadata(3));

            formatter.displayResult(result);

            assertThat(getOutput()).doesNotContain("iteration(s)");
        }

        @Test
        @DisplayName("Should print error message on failure")
        void shouldPrintErrorMessageOnFailure() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.failure("Something went wrong",
                    createSessionRecord(), createMetadata(2));

            formatter.displayResult(result);

            assertThat(getOutput()).contains("Error: Something went wrong");
        }

        @Test
        @DisplayName("Should render '[Interrupted]' banner without 'Error:' on INTERRUPTED completion")
        void shouldRenderInterruptedBannerOnInterruptedCompletion() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.failure("Execution interrupted",
                    createSessionRecord(), createMetadata(2), java.util.List.of(), CompletionReason.INTERRUPTED);

            formatter.displayResult(result);

            String out = getOutput();
            assertThat(out).contains("[Interrupted]").contains("Execution interrupted");
            // The red "Error:" prefix would mislead a user who just hit Ctrl+C on purpose — interrupted is a
            // successful stop, not a failure.
            assertThat(out).doesNotContain("Error:");
            assertThat(out).contains("[Interrupted after 2 iteration(s)]");
        }

        @Test
        @DisplayName("PSTREAM-10: should skip getFinalAnswer output when result.wasStreamed is true")
        void shouldSkipFinalAnswerWhenWasStreamedTrue() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("The answer is 42",
                    createSessionRecord(), createMetadata(3), java.util.List.of(), CompletionReason.COMPLETED, true);

            formatter.displayResult(result);

            // Delta events have already painted the answer inline; displaySuccessResult must not re-print it.
            assertThat(getOutput()).doesNotContain("The answer is 42");
            // Iteration footer still renders so users see how much work ran.
            assertThat(getOutput()).contains("[Completed in 3 iteration(s)]");
        }

        @Test
        @DisplayName("PSTREAM-10: should still print getFinalAnswer when result.wasStreamed is false")
        void shouldPrintFinalAnswerWhenWasStreamedFalse() {
            // Default success() factory sets wasStreamed=false
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("The answer is 42",
                    createSessionRecord(), createMetadata(3));

            formatter.displayResult(result);

            assertThat(getOutput()).contains("The answer is 42");
        }
    }

    @Nested
    @DisplayName("displayEvent — streaming text")
    class DisplayStreamingEvents {

        private AssistantTextDelta buildDelta(String text, int chunkIndex) {
            return AssistantTextDelta.builder().timestamp(Instant.now())
                    .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).iteration(1).delta(text).chunkIndex(chunkIndex)
                    .build();
        }

        private AssistantTextStreamReset buildReset(String reason) {
            return AssistantTextStreamReset.builder().timestamp(Instant.now())
                    .agentRuntimeId(AgentRuntimeId.of("agent:test-2")).iteration(1).previousAttemptIndex(0)
                    .nextAttemptIndex(1).reason(reason).build();
        }

        private AssistantTextStreamCompleted buildCompleted(int totalLength) {
            return AssistantTextStreamCompleted.builder().timestamp(Instant.now())
                    .agentRuntimeId(AgentRuntimeId.of("agent:test-3")).iteration(1).totalLength(totalLength).build();
        }

        @Test
        @DisplayName("Should print delta inline without appending a newline")
        void shouldPrintDeltaInlineWithoutNewline() {
            formatter.displayEvent(buildDelta("Hello ", 0));
            formatter.displayEvent(buildDelta("world", 1));

            String out = getOutput();
            assertThat(out).isEqualTo("Hello world");
        }

        @Test
        @DisplayName("Should render retry banner with reason on stream reset")
        void shouldRenderRetryBannerOnStreamReset() {
            formatter.displayEvent(buildDelta("partial attempt...", 0));
            formatter.displayEvent(buildReset("5xx_retry"));

            // Tight equality guards the exact layout: the partial delta is left intact (we do not ANSI-erase, which
            // is unreliable once text has wrapped), followed by one leading newline that lifts the banner off the
            // delta line, the banner itself, and exactly one trailing newline from colorPrintln. A double-newline
            // regression (e.g. banner literal carrying its own trailing \n) would produce "...]\n\n" and fail here.
            assertThat(getOutput()).isEqualTo("partial attempt..." + System.lineSeparator()
                    + "[Retrying stream: 5xx_retry]" + System.lineSeparator());
        }

        @Test
        @DisplayName("Should emit a terminating newline on stream completion")
        void shouldEmitTerminatingNewlineOnStreamCompletion() {
            formatter.displayEvent(buildDelta("final text", 0));
            // Before completion there is no trailing newline, so the next line of output would run straight on.
            assertThat(getOutput()).isEqualTo("final text");

            formatter.displayEvent(buildCompleted("final text".length()));

            assertThat(getOutput()).isEqualTo("final text" + System.lineSeparator());
        }
    }

    @Nested
    @DisplayName("displayToolCall")
    class DisplayToolCall {

        @Test
        @DisplayName("Should print tool name and input")
        void shouldPrintToolNameAndInput() {
            formatter.displayToolCall("Read", "/path/to/file", InvokerType.MAIN_AGENT);

            assertThat(getOutput()).contains("[Tool] Read: /path/to/file");
        }

        @Test
        @DisplayName("Should not print when showToolCalls is false")
        void shouldNotPrintWhenShowToolCallsDisabled() {
            settings.setShowToolCalls(false);

            formatter.displayToolCall("Read", "/path/to/file", InvokerType.MAIN_AGENT);

            assertThat(getOutput()).isEmpty();
        }

        @Test
        @DisplayName("Should suppress the tool-call line for the Task tool (launch shown by the SUBAGENT_START hook)")
        void shouldSuppressToolCallLineForTaskTool() {
            formatter.displayToolCall("Task", "Analyze code quality", InvokerType.MAIN_AGENT);

            assertThat(getOutput()).isEmpty();
        }

        @Test
        @DisplayName("displaySubagentLaunch shows the subagent name and a truncated goal preview")
        void displaySubagentLaunchShowsNameAndGoal() {
            formatter.displaySubagentLaunch("workflow:perspective:risk", "assess the rollout risk");

            assertThat(getOutput()).contains("[Subagent] workflow:perspective:risk: assess the rollout risk");
        }

        @Test
        @DisplayName("displaySubagentLaunch collapses whitespace and is gated by showToolCalls")
        void displaySubagentLaunchCollapsesAndRespectsSetting() {
            formatter.displaySubagentLaunch("s", "line one\n  line two");
            assertThat(getOutput()).contains("[Subagent] s: line one line two");

            settings.setShowToolCalls(false);
            outputStream.reset();
            formatter.displaySubagentLaunch("s", "y");
            assertThat(getOutput()).isEmpty();
        }

        @Test
        @DisplayName("Should default to MAIN_AGENT when invoker type not specified")
        void shouldDefaultToMainAgentWhenInvokerTypeNotSpecified() {
            formatter.displayToolCall("Read", "/path/to/file");

            assertThat(getOutput()).contains("[Tool] Read: /path/to/file");
        }
    }

    @Nested
    @DisplayName("displaySubagentResult")
    class DisplaySubagentResult {

        @Test
        @DisplayName("Should print formatted subagent result")
        void shouldPrintFormattedSubagentResult() {
            formatter.displaySubagentResult("  ", "code-analyzer", "Analyze code quality", "SUCCESS", 5, 1200,
                    "Found 3 issues");

            String output = getOutput();
            assertThat(output).contains("[Subagent Result] code-analyzer - Analyze code quality");
            assertThat(output).contains("SUCCESS");
            assertThat(output).contains("5 iteration(s), 1200 tokens");
            assertThat(output).contains("Found 3 issues");
        }

        @Test
        @DisplayName("Should not print when showToolCalls is false")
        void shouldNotPrintWhenShowToolCallsDisabled() {
            settings.setShowToolCalls(false);

            formatter.displaySubagentResult("  ", "code-analyzer", "Analyze code quality", "SUCCESS", 5, 1200,
                    "Found 3 issues");

            assertThat(getOutput()).isEmpty();
        }
    }

    @Nested
    @DisplayName("displaySubagentTaskCompleted")
    class DisplaySubagentTaskCompleted {

        private SubagentTaskCompleted event(SubagentTaskCompleted.Outcome outcome, String detail) {
            SubagentTaskCompleted.Builder builder = SubagentTaskCompleted.builder().timestamp(Instant.now())
                    .agentRuntimeId(AgentRuntimeId.of("agent:test")).taskId("t-1").subagentName("researcher")
                    .outcome(outcome);
            if (detail != null) {
                builder.detail(detail);
            }
            return builder.build();
        }

        @Test
        @DisplayName("Should render a single info line with outcome, subagent, taskId and first detail line")
        void rendersCompletionLine() {
            formatter.displayEvent(event(SubagentTaskCompleted.Outcome.COMPLETED, "summary line one\nsecond line"));

            String output = getOutput();
            assertThat(output).contains("[Background task completed]").contains("researcher").contains("taskId=t-1")
                    .contains("summary line one");
            assertThat(output).doesNotContain("second line");
        }

        @Test
        @DisplayName("Should render a failed outcome without a detail")
        void rendersFailedWithoutDetail() {
            formatter.displayEvent(event(SubagentTaskCompleted.Outcome.FAILED, null));

            assertThat(getOutput()).contains("[Background task failed]").contains("researcher").contains("taskId=t-1");
        }
    }
}
