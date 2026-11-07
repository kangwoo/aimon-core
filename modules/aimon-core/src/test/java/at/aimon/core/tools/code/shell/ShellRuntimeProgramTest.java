package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
 * The real runtime's program shapes and what it prints of a failure (ptc-leftovers-core §1.5, §1.7; backlog TC-29):
 * a program runs inside an event loop only when it uses top-level await, so its own {@code asyncio.run(main())} works;
 * {@code asyncio} and {@code json} are pre-imported; and every printer of an uncaught exception — the main thread, a
 * thread, the asyncio logger, an unraisable exception, a warning — shows only the program's own frames, never the
 * runtime's file or an interpreter path.
 */
@DisplayName("aimon_ptc.py — program shapes, pre-imports and program-only tracebacks")
class ShellRuntimeProgramTest {

    /** An absolute path to a Python file, as a traceback of the runtime or the stdlib would print it. */
    private static final Pattern ABSOLUTE_PY = Pattern.compile("(^|[\\s\"'])/[^\\s\"']+\\.py");

    @TempDir
    Path tempDir;

    private ScriptedToolCallBridge bridge;
    private final ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create();

    @BeforeAll
    static void requirePython() {
        assumeTrue(Python3.available(), "python3 3.8+ is not installed");
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.close();
        }
        engine.close();
    }

    @Test
    @DisplayName("no top-level await: the program's own asyncio.run(main()) with two gathered calls completes")
    void ownAsyncioRun() throws IOException {
        final CodeExecutionOutcome outcome = run(ScriptedToolCallBridge.succeedingWith("echoed"),
                "import asyncio\n" + "async def main():\n"
                        + "    a, b = await asyncio.gather(Echo({'i': 1}), Echo({'i': 2}))\n" + "    print(a, b)\n"
                        + "asyncio.run(main())\n");
        assertThat(outcome.getStatus()).as(outcome.getLogs()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).isEqualTo("echoed echoed\n");
    }

    @Test
    @DisplayName("json and asyncio are usable without an import")
    void preImported() throws IOException {
        final CodeExecutionOutcome outcome = run(ScriptedToolCallBridge.succeedingWith("{\"n\": 1}"),
                "results = await asyncio.gather(Echo({}), Echo({}))\n"
                        + "print(json.dumps([json.loads(r)['n'] for r in results]))\n");
        assertThat(outcome.getStatus()).as(outcome.getLogs()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).isEqualTo("[1, 1]\n");
    }

    @Test
    @DisplayName("an exception raised in a nested function: the program's frames, code lines and exception line only")
    void programOnlyTraceback() throws IOException {
        final CodeExecutionOutcome outcome = run(ScriptedToolCallBridge.succeedingWith("ok"),
                "def inner(x):\n" + "    return x['missing']\n" + "async def main():\n" + "    await Echo({})\n"
                        + "    inner({})\n" + "await main()\n");
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
        assertThat(outcome.getLogs()).contains("Traceback (most recent call last):")
                .contains("File \"<program>\", line 2, in inner").contains("return x['missing']")
                .contains("File \"<program>\", line 5, in main").contains("KeyError: 'missing'");
        assertProgramOnly(outcome.getLogs());
    }

    @Test
    @DisplayName("a ToolError raised inside the tool function: the awaiting program frame and the exception line")
    void toolErrorTraceback() throws IOException {
        final CodeExecutionOutcome outcome = run(
                ScriptedToolCallBridge.answering(call -> ScriptedToolCallBridge.error("no such file")),
                "x = 1\n" + "await Echo({'path': 'a'})\n");
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
        assertThat(outcome.getLogs()).contains("File \"<program>\", line 2, in <module>")
                .contains("ToolError: no such file");
        assertProgramOnly(outcome.getLogs());
    }

    @Test
    @DisplayName("an unawaited task that raises: the asyncio log is program-only")
    void asyncioLogProgramOnly() throws IOException {
        final CodeExecutionOutcome outcome = run(ScriptedToolCallBridge.succeedingWith("ok"),
                "import gc\n" + "async def boom():\n" + "    raise ValueError('task boom')\n" + "async def main():\n"
                        + "    task = asyncio.ensure_future(boom())\n" + "    await asyncio.sleep(0.05)\n"
                        + "    del task\n" + "    gc.collect()\n" + "asyncio.run(main())\n");
        assertThat(outcome.getStatus()).as(outcome.getLogs()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getLogs()).contains("Task exception was never retrieved")
                .contains("File \"<program>\", line 3, in boom").contains("ValueError: task boom");
        assertProgramOnly(outcome.getLogs());
    }

    @Test
    @DisplayName("a never-awaited tool call: the RuntimeWarning names no runtime file and no path")
    void neverAwaitedWarning() throws IOException {
        final CodeExecutionOutcome outcome = run(ScriptedToolCallBridge.succeedingWith("ok"),
                "Echo({'i': 1})\n" + "print('done')\n");
        assertThat(outcome.getStatus()).as(outcome.getLogs()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getLogs()).contains("RuntimeWarning").contains("was never awaited");
        assertProgramOnly(outcome.getLogs());
    }

    @Test
    @DisplayName("a thread that raises: program-only")
    void threadProgramOnly() throws IOException {
        final CodeExecutionOutcome outcome = run(ScriptedToolCallBridge.succeedingWith("ok"),
                "import threading\n" + "def work():\n" + "    raise ValueError('thread boom')\n"
                        + "t = threading.Thread(target=work, name='worker')\n" + "t.start()\n" + "t.join()\n");
        assertThat(outcome.getStatus()).as(outcome.getLogs()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getLogs()).contains("Exception in thread worker:")
                .contains("File \"<program>\", line 3, in work").contains("ValueError: thread boom");
        assertProgramOnly(outcome.getLogs());
    }

    @Test
    @DisplayName("an unraisable exception (a __del__ that raises): program-only")
    void unraisableProgramOnly() throws IOException {
        final CodeExecutionOutcome outcome = run(ScriptedToolCallBridge.succeedingWith("ok"), "class Holder:\n"
                + "    def __del__(self):\n" + "        raise ValueError('del boom')\n" + "h = Holder()\n" + "del h\n");
        assertThat(outcome.getStatus()).as(outcome.getLogs()).isEqualTo(CodeExecutionStatus.COMPLETED);
        // The header is CPython's own err_msg, passed through: "Exception ignored in: <function Holder.__del__ ...>"
        // up to 3.13, "Exception ignored while calling deallocator <function Holder.__del__ ...>" from 3.14. Only
        // the common part is asserted — what matters here is that the output is program-only.
        assertThat(outcome.getLogs()).contains("Exception ignored ").contains("<function Holder.__del__ at ")
                .contains("File \"<program>\", line 3, in __del__").contains("ValueError: del boom");
        assertProgramOnly(outcome.getLogs());
    }

    private static void assertProgramOnly(String logs) {
        assertThat(logs).doesNotContain("aimon_ptc");
        assertThat(ABSOLUTE_PY.matcher(logs).find()).as("an absolute .py path in:\n%s", logs).isFalse();
    }

    private CodeExecutionOutcome run(ScriptedToolCallBridge answering, String code) throws IOException {
        bridge = answering;
        final CodeExecutionLimits limits = CodeExecutionLimits.defaults();
        final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId("toolu_program").code(code)
                .tools(List.of(ToolDefinition.of("Echo", "Echoes.", Map.of("type", "object")))).limits(limits)
                .wallClock(Duration.ofSeconds(30)).cancellation(bridge.signal()).build();
        return engine.execute(request, bridge,
                ToolContext.builder()
                        .put(ToolContextKeys.EXECUTION_ENVIRONMENT, new SandboxedLocalEnvironment(tempDir.toRealPath()))
                        .build());
    }
}
