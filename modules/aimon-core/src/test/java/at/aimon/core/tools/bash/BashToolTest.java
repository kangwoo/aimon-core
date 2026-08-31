package at.aimon.core.tools.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.shell.impl.local.LocalShell;

/** Unit tests for {@link BashTool}. */
class BashToolTest {

    private BashTool bashTool;
    private StubShell stubShell;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        stubShell = new StubShell();
        bashTool = new BashTool(stubShell);
        context = ToolContext.empty();
    }

    @AfterEach
    void tearDown() {
        if (bashTool != null) {
            bashTool.shutdown();
        }
    }

    // Constructor tests

    @Test
    void testConstructor_NullShell_ThrowsException() {
        assertThatThrownBy(() -> new BashTool(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Shell cannot be null");
    }

    @Test
    void testConstructor_ValidShell_Success() {
        BashTool tool = new BashTool(stubShell);
        assertThat(tool).isNotNull();
        tool.shutdown();
    }

    // getDefinition tests

    @Test
    void testGetDefinition_ReturnsCorrectName() {
        ToolDefinition definition = bashTool.getDefinition();
        assertThat(definition.getName()).isEqualTo("Bash");
    }

    @Test
    void testGetDefinition_ReturnsCorrectDescription() {
        ToolDefinition definition = bashTool.getDefinition();
        assertThat(definition.getDescription()).contains("Executes bash commands");
        assertThat(definition.getDescription()).contains("CRITICAL");
        assertThat(definition.getDescription()).contains("terminal operations");
        assertThat(definition.getDescription()).contains("DO NOT use it for file operations");
    }

    @Test
    void testGetDefinition_HasRequiredCommandParameter() {
        ToolDefinition definition = bashTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();

        assertThat(schema.get("required")).asList().contains("command");
    }

    @Test
    void testGetDefinition_HasOptionalParameters() {
        ToolDefinition definition = bashTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("command", "description", "timeout", "run_in_background");
    }

    // execute tests - validation

    @Test
    void testExecute_MissingCommand_ReturnsError() {
        Map<String, Object> toolUse = Map.of();

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter: Missing required parameter: command");
    }

    @Test
    void testExecute_EmptyCommand_ReturnsError() {
        Map<String, Object> toolUse = Map.of("command", "   ");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Command cannot be empty");
    }

    @Test
    void testExecute_NullToolUse_ThrowsException() {
        assertThatThrownBy(() -> bashTool.execute(ToolInput.of(null), context)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Input data cannot be null");
    }

    @Test
    void testExecute_NullContext_ThrowsException() {
        Map<String, Object> toolUse = Map.of("command", "echo test");

        assertThatThrownBy(() -> bashTool.execute(ToolInput.of(toolUse), null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Context cannot be null");
    }

    // execute tests - success cases

    @Test
    void testExecute_SimpleCommand_Success() {
        stubShell.setNextOutput("test output");

        Map<String, Object> toolUse = Map.of("command", "echo test");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("test output");
    }

    @Test
    void testExecute_CommandWithDescription_Success() {
        stubShell.setNextOutput("git status output");

        Map<String, Object> toolUse = Map.of("command", "git status", "description", "Check git status");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("git status output");
    }

    @Test
    void testExecute_MultilineOutput_Success() {
        stubShell.setNextOutput("Line 1\nLine 2\nLine 3");

        Map<String, Object> toolUse = Map.of("command", "ls -la");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Line 1");
        assertThat(result.getContent()).contains("Line 2");
        assertThat(result.getContent()).contains("Line 3");
    }

    @Test
    void testExecute_EmptyOutput_Success() {
        stubShell.setNextOutput("");

        Map<String, Object> toolUse = Map.of("command", "true");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEmpty();
    }

    // execute tests - error cases

    @Test
    void testExecute_NonZeroExit_ReturnsErrorCarryingTheCode() {
        // A non-zero exit is a normal result from the shell, not a thrown exception — the tool is what turns it into
        // an error for the model, so the exit code has to survive that conversion.
        stubShell.setNextResult(new ShellCommandResult(1, "error message", "", Duration.ofMillis(5)));

        Map<String, Object> toolUse = Map.of("command", "false");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        // The output comes first and the status is a marker at the end. Prefixing instead ("Command failed with exit
        // code 1: ...") made the first line of every failure something the command never printed, which is exactly
        // what the model quotes back when asked what went wrong.
        assertThat(result.getContent()).isEqualTo("error message\n[exit code: 1]");
    }

    @Test
    void testExecute_CommandNotFound_ReturnsError() {
        stubShell.setNextResult(new ShellCommandResult(127, "", "command not found", Duration.ofMillis(5)));

        Map<String, Object> toolUse = Map.of("command", "nonexistent-command");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("[exit code: 127]").contains("command not found");
    }

    @Test
    void testExecute_NoMatchExitCode_ReportsTheCodeRatherThanInterpretingIt() {
        // grep exiting 1 on no match is the canonical "non-zero but not broken" case. The tool takes no position on
        // it: the code is reported, the empty output is reported, and whether that counts as failure is left to the
        // model (and to a future allow-list — design §6.4). Pinned so adding such a list is a visible decision.
        stubShell.setNextResult(new ShellCommandResult(1, "", "", Duration.ofMillis(5)));

        Map<String, Object> toolUse = Map.of("command", "grep nothing file.txt");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        // No leading blank line: with nothing printed, the marker is the whole body.
        assertThat(result.getContent()).isEqualTo("[exit code: 1]");
    }

    @Test
    void testExecute_ShellCannotRunTheCommand_ReturnsError() {
        stubShell.setNextException(new ShellExecutionException("shell unavailable"));

        Map<String, Object> toolUse = Map.of("command", "echo test");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        // The one outcome with no exit status: the command never ran. This is the only place "Command failed" is
        // still the right words, and the only remaining producer of that prefix.
        assertThat(result.getContent()).contains("Command failed").contains("shell unavailable");
    }

    // execute tests - timeout
    //
    // The tool no longer enforces the timeout itself; it hands one to the shell, which owns both the waiting and the
    // process teardown. So these tests check the value that reaches ExecutionOptions, and that a ShellTimeoutException
    // coming back is reported as a timeout rather than as a generic failure.

    @Test
    void testExecute_DefaultTimeout_Used() {
        stubShell.setNextOutput("output");

        Map<String, Object> toolUse = Map.of("command", "echo test");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(stubShell.lastOptions().getTimeout()).isEqualTo(Duration.ofMillis(120_000));
    }

    @Test
    void testExecute_CustomTimeout_Applied() {
        stubShell.setNextOutput("output");

        Map<String, Object> toolUse = Map.of("command", "sleep 1", "timeout", 5000);

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(stubShell.lastOptions().getTimeout()).isEqualTo(Duration.ofMillis(5000));
    }

    @Test
    void testExecute_TimeoutExceeded_ReturnsPartialOutputWithTheDeadline() {
        stubShell.setNextException(
                new ShellTimeoutException("timed out", Duration.ofMillis(1000), "step 9 of 10\n", ""));

        Map<String, Object> toolUse = Map.of("command", "sleep 10", "timeout", 1000);

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        // What the command printed before it was killed is the only diagnostic a timeout ever produces. The shell
        // has always carried it on the exception; the adapter this tool replaced dropped it on the floor.
        assertThat(result.getContent()).isEqualTo("step 9 of 10\n[timed out after 1000ms]");
        // Not "Command failed: Command timed out after ..." — one assembly point means one prefix, or none.
        assertThat(result.getContent()).doesNotStartWith("Command failed");
    }

    @Test
    void testExecute_NonPositiveTimeout_ClampedToMinimumNotToZero() {
        stubShell.setNextOutput("output");

        Map<String, Object> toolUse = Map.of("command", "echo test", "timeout", -1000);

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        // The floor is load-bearing, not cosmetic: the shell reads a non-positive timeout as "wait forever", so
        // passing this through would turn a bad parameter into a command nothing ever kills. It used to mean the
        // opposite — an immediate abort — because the old future wrapper did the waiting.
        assertThat(result.isSuccess()).isTrue();
        assertThat(stubShell.lastOptions().getTimeout()).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void testExecute_TimeoutExceedsMaximum_CappedAtMaximum() {
        stubShell.setNextOutput("output");

        Map<String, Object> toolUse = Map.of("command", "echo test", "timeout", 1_000_000);

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(stubShell.lastOptions().getTimeout()).isEqualTo(Duration.ofMillis(600_000));
    }

    // execute tests - output truncation

    @Test
    void testExecute_LongOutput_TruncatedAt30000Characters() {
        String longOutput = "x".repeat(40_000);
        stubShell.setNextOutput(longOutput);

        Map<String, Object> toolUse = Map.of("command", "generate-long-output");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).hasSizeLessThanOrEqualTo(30_100); // 30,000 + truncation message
        assertThat(result.getContent()).contains("[Output truncated at 30,000 characters]");
    }

    @Test
    void testExecute_OutputUnder30000Characters_NotTruncated() {
        String output = "x".repeat(20_000);
        stubShell.setNextOutput(output);

        Map<String, Object> toolUse = Map.of("command", "generate-output");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).hasSize(20_000);
        assertThat(result.getContent()).doesNotContain("truncated");
    }

    @Test
    void testExecute_FailingCommandWithLongOutput_TruncatedButKeepsTheExitCode() {
        // Truncation used to apply to successes only; failures were assembled by a different branch that never cut
        // anything. Now one path does both — and the order matters: cut first, then append. Appending first would put
        // the marker 10,000 characters inside the region that gets dropped.
        stubShell.setNextResult(new ShellCommandResult(2, "x".repeat(40_000), "", Duration.ofMillis(5)));

        Map<String, Object> toolUse = Map.of("command", "noisy-failing-build");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("[Output truncated at 30,000 characters]");
        assertThat(result.getContent()).endsWith("[exit code: 2]");
    }

    @Test
    void testExecute_ShellHitItsCaptureCap_SaysSoOnTopOfTheOtherMarkers() {
        // Two different truncations, and both can be true at once: the 30,000-character cut is this tool hiding
        // output it holds, the capture cap is the shell never having held it. Only the second is unrecoverable, so
        // the model has to be able to tell them apart.
        stubShell.setNextResult(new ShellCommandResult(2, "some output", "", Duration.ofMillis(5), true));

        Map<String, Object> toolUse = Map.of("command", "very-noisy-failing-build");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent())
                .isEqualTo("some output\n[exit code: 2]\n[Output truncated: the shell reached its capture limit, "
                        + "so some output was discarded and is not recoverable]");
    }

    @Test
    void testExecute_SucceedingCommandHitTheCaptureCap_StillSaysSo() {
        // A successful command can lose output too, and a short body then reads as a short run. This is the half the
        // old assembly could not express at all: its truncation handling lived on the success path only, and the
        // shell's own capture flag was read nowhere.
        stubShell.setNextResult(new ShellCommandResult(0, "some output", "", Duration.ofMillis(5), true));

        Map<String, Object> toolUse = Map.of("command", "very-noisy-command");

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).startsWith("some output\n[Output truncated: the shell reached");
        assertThat(result.getContent()).doesNotContain("exit code");
    }

    // execute tests - background execution

    @Test
    void testExecute_BackgroundExecution_WithoutManager_ReturnsError() {
        stubShell.setNextOutput("output");

        Map<String, Object> toolUse = Map.of("command", "npm start", "run_in_background", true);

        ToolResult result = bashTool.execute(ToolInput.of(toolUse), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Background execution is not supported");
        assertThat(result.getContent()).contains("BackgroundBashManager was not provided");
    }

    @Test
    void testExecute_BackgroundExecution_WithManager_Success() {
        BackgroundBashManager manager = new BackgroundBashManager();
        BashTool bashToolWithManager = new BashTool(stubShell, manager);

        Map<String, Object> toolUse = Map.of("command", "echo test", "run_in_background", true);

        ToolResult result = bashToolWithManager.execute(ToolInput.of(toolUse), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Background task started with ID:");
        assertThat(result.getContent()).contains("bash_");
        assertThat(result.getContent()).contains("BashOutput");

        bashToolWithManager.shutdown();
    }

    // Integration tests against a real shell.
    //
    // These use LocalShell directly, which production code outside the shell tree may not do (ArchUnit's
    // shellImplMustNotLeakOutsideShellTree); the rule imports main classes only, and the point of these tests is
    // exactly to exercise the concrete implementation the stub above stands in for.

    @Test
    void testExecute_WithRealShell_Success() {
        BashTool realBashTool = new BashTool(new LocalShell());

        try {
            Map<String, Object> toolUse = Map.of("command", "echo 'Hello World'");

            ToolResult result = realBashTool.execute(ToolInput.of(toolUse), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Hello World");
        } finally {
            realBashTool.shutdown();
        }
    }

    @Test
    void testExecute_WithRealShell_CommandFails() {
        BashTool realBashTool = new BashTool(new LocalShell());

        try {
            Map<String, Object> toolUse = Map.of("command", "false");

            ToolResult result = realBashTool.execute(ToolInput.of(toolUse), context);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("[exit code: 1]");
        } finally {
            realBashTool.shutdown();
        }
    }

    @Test
    void testExecute_WithRealShell_GitCommand() {
        BashTool realBashTool = new BashTool(new LocalShell());

        try {
            Map<String, Object> toolUse = Map.of("command", "git --version");

            ToolResult result = realBashTool.execute(ToolInput.of(toolUse), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("git version");
        } finally {
            realBashTool.shutdown();
        }
    }

    /**
     * Stand-in for a {@link VirtualShell}. Records the options it was handed so the tests can check what the tool asked
     * for — with the shell owning the timeout, the options are the tool's whole side of that contract.
     */
    private static final class StubShell implements VirtualShell {

        private final AtomicReference<ExecutionOptions> lastOptions = new AtomicReference<>();
        private ShellCommandResult nextResult;
        private ShellExecutionException nextException;

        void setNextOutput(String output) {
            setNextResult(new ShellCommandResult(0, output, "", Duration.ofMillis(1)));
        }

        void setNextResult(ShellCommandResult result) {
            this.nextResult = result;
            this.nextException = null;
        }

        void setNextException(ShellExecutionException exception) {
            this.nextException = exception;
            this.nextResult = null;
        }

        ExecutionOptions lastOptions() {
            return lastOptions.get();
        }

        @Override
        public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
            return execute(command, ExecutionOptions.defaults());
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options)
                throws ShellExecutionException {
            lastOptions.set(options);

            if (nextException != null) {
                throw nextException;
            }

            return nextResult != null ? nextResult : new ShellCommandResult(0, "", "", Duration.ofMillis(1));
        }

        @Override
        public String getWorkingDirectory() {
            return null;
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return false;
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
