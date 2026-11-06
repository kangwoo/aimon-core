package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.programmatic.testkit.ScriptedToolCallBridge;

/**
 * The real runtime's output and log tail at their caps (shell doc §7): a large log tail arrives in several
 * {@code ERR} lines instead of one line over the 64 KiB limit, and neither the tail nor the output cut breaks a UTF-8
 * character.
 */
@DisplayName("aimon_ptc.py — the log tail in chunks, UTF-8-safe cuts")
class ShellRuntimeOutputTest {

    @TempDir
    Path tempDir;

    private ShellCodeExecutionEngine engine;
    private ScriptedToolCallBridge bridge;

    @BeforeAll
    static void requirePython() {
        assumeTrue(Python3.available(), "python3 3.8+ is not installed");
    }

    @BeforeEach
    void setUp() {
        engine = ShellCodeExecutionEngine.create();
        bridge = ScriptedToolCallBridge.succeedingWith("unused");
    }

    @AfterEach
    void tearDown() {
        bridge.close();
        engine.close();
    }

    @Test
    @DisplayName("a 40,000-character non-ASCII log tail arrives whole, with no U+FFFD (it was one line over 64 KiB)")
    void largeLogTail() throws IOException {
        final CodeExecutionOutcome outcome = run("import sys\nsys.stderr.write('가' * 40000)\n",
                CodeExecutionLimits.builder().maxLogChars(50_000).build());
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getLogs()).hasSize(40_000).isEqualTo("가".repeat(40_000));
    }

    @Test
    @DisplayName("a log tail longer than maxLogChars keeps its last characters, whole")
    void logTailCut() throws IOException {
        final CodeExecutionOutcome outcome = run("import sys\nsys.stderr.write('a' + '가' * 100)\n",
                CodeExecutionLimits.builder().maxLogChars(10).build());
        assertThat(outcome.getLogs()).isEqualTo("가".repeat(10));
    }

    @Test
    @DisplayName("output cut at a cap that is not a multiple of 3 ends on a whole 한: LIMIT_EXCEEDED")
    void outputCutKeepsWholeCharacters() throws IOException {
        final CodeExecutionOutcome outcome = run("print('한' * 5000)\n",
                CodeExecutionLimits.builder().maxScriptOutputBytes(10_000).build());
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.LIMIT_EXCEEDED);
        assertThat(outcome.getStdout()).isEqualTo("한".repeat(3333)).doesNotContain("�");
    }

    private CodeExecutionOutcome run(String code, CodeExecutionLimits limits) throws IOException {
        final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId("toolu_output").code(code)
                .tools(List.of(ToolDefinition.of("Echo", "Echoes.", Map.of("type", "object")))).limits(limits)
                .wallClock(Duration.ofSeconds(30)).cancellation(bridge.signal()).build();
        return engine.execute(request, bridge,
                ToolContext.builder()
                        .put(ToolContextKeys.EXECUTION_ENVIRONMENT, new SandboxedLocalEnvironment(tempDir.toRealPath()))
                        .build());
    }
}
