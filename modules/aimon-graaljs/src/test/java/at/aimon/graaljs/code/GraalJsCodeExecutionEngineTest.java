package at.aimon.graaljs.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionEngineFactory;
import at.aimon.core.programmatic.CodeExecutionEngines;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.programmatic.testkit.ScriptedToolCallBridge;

@DisplayName("GraalJsCodeExecutionEngine — sandbox, built-in output, marshalling budget, results")
class GraalJsCodeExecutionEngineTest {

    private static GraalJsCodeExecutionEngine engine;
    private ScriptedToolCallBridge bridge;

    @BeforeAll
    static void createEngine() {
        engine = GraalJsCodeExecutionEngine.create();
    }

    @AfterAll
    static void closeEngine() {
        engine.close();
    }

    @AfterEach
    void closeBridge() {
        if (bridge != null) {
            bridge.close();
        }
    }

    private CodeExecutionOutcome run(String code) {
        return run(code, CodeExecutionLimits.defaults());
    }

    private CodeExecutionOutcome run(String code, CodeExecutionLimits limits) {
        if (bridge == null) {
            bridge = ScriptedToolCallBridge.answering(call -> ScriptedToolCallBridge.success("ok"));
        }
        final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId("t").code(code)
                .tools(List.of(ToolDefinition.of("Echo", "echo", Map.of("type", "object")))).limits(limits)
                .wallClock(Duration.ofSeconds(20)).cancellation(bridge.signal()).build();
        return engine.execute(request, bridge, ToolContext.empty());
    }

    @Test
    @DisplayName("the grade is READ_ONLY / ENVIRONMENT with approval delegated, and the engine is found by"
            + " ServiceLoader")
    void gradeAndDiscovery() {
        assertThat(engine.language()).isEqualTo(CodeExecutionEngine.JAVASCRIPT);
        assertThat(engine.intrinsicGrade().isApprovalDelegated()).isTrue();
        assertThat(CodeExecutionEngines.discover(getClass().getClassLoader()))
                .extracting(CodeExecutionEngineFactory::language).contains(CodeExecutionEngine.JAVASCRIPT);
    }

    @Test
    @DisplayName("the built-in print is gone and nothing reaches System.out")
    void builtInOutputIsOff() {
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        final CodeExecutionOutcome outcome;
        try {
            outcome = run("print('leak');");
        } finally {
            System.setOut(original);
        }
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
        assertThat(captured.toString(StandardCharsets.UTF_8)).doesNotContain("leak");
    }

    @Test
    @DisplayName("no host access: Java interop is not there")
    void noHostAccess() {
        final CodeExecutionOutcome outcome = run("const S = Java.type('java.lang.System'); S.exit(1);");
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
    }

    @Test
    @DisplayName("a returned value other than undefined is reported, rendered as JSON for objects")
    void returnValue() {
        assertThat(run("return { a: 1 };").getReturnValue()).contains("{\"a\":1}");
        assertThat(run("console.log('x');").getReturnValue()).isEmpty();
        final CodeExecutionOutcome long_ = run("return 'y'.repeat(100000);",
                CodeExecutionLimits.builder().maxObservationChars(100).build());
        assertThat(long_.getReturnValue()).hasValueSatisfying(value -> assertThat(value).hasSize(101));
    }

    @Test
    @DisplayName("a huge array argument is refused before any host allocation, as a catchable ToolError")
    void hugeArrayArgument() {
        final CodeExecutionOutcome outcome = run("""
                try { await tools.Echo({ x: new Array(2 ** 31 - 1) }); }
                catch (e) { console.log(e.name + ' ' + e.message); }
                """);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).contains("ToolError").contains("larger than the limit");
        assertThat(bridge.calls()).isEmpty();
    }

    @Test
    @DisplayName("a shared-reference DAG runs out of nodes instead of heap")
    void dagArgument() {
        final CodeExecutionOutcome outcome = run("""
                let node = { leaf: 1 };
                for (let i = 0; i < 100; i++) { node = { a: node, b: node }; }
                try { await tools.Echo(node); } catch (e) { console.log('refused: ' + e.message); }
                """);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).contains("refused: ");
        assertThat(bridge.calls()).isEmpty();
    }

    @Test
    @DisplayName("a large result is cut at the response limit; results past the total limit stop the program")
    void resultLimits() {
        bridge = ScriptedToolCallBridge.answering(call -> ScriptedToolCallBridge.success("z".repeat(5000)));
        final CodeExecutionOutcome cut = run(
                "const r = await tools.Echo({}); console.log(r.length > 1000, " + "r.includes('[truncated'));",
                CodeExecutionLimits.builder().maxArgumentBytes(1000).build());
        assertThat(cut.getStdout()).contains("true true");

        final CodeExecutionOutcome stopped = run("for (let i = 0; i < 10; i++) { await tools.Echo({}); }",
                CodeExecutionLimits.builder().maxResultBytes(12_000).build());
        assertThat(stopped.getStatus()).isEqualTo(CodeExecutionStatus.LIMIT_EXCEEDED);
        assertThat(stopped.getErrorMessage()).hasValueSatisfying(m -> assertThat(m).contains("tool results"));
    }

    @Test
    @DisplayName("the statement limit stops a runaway loop: LIMIT_EXCEEDED")
    void statementLimit() {
        final CodeExecutionOutcome outcome = run("while (true) {}",
                CodeExecutionLimits.builder().maxStatements(10_000).build());
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("a program awaiting a promise that never settles ends as a script error, not a hang")
    void neverSettles() {
        final CodeExecutionOutcome outcome = run("await new Promise(() => {});");
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
        assertThat(outcome.getErrorMessage()).hasValueSatisfying(m -> assertThat(m).contains("never settles"));
    }

    @Test
    @DisplayName("a bridge that throws synchronously rejects the call, and leaves no call counted as in flight")
    void synchronouslyThrowingBridge() {
        bridge = ScriptedToolCallBridge.succeedingWith("unused"); // supplies the cancellation signal only
        final ToolCallBridge throwing = new ToolCallBridge() {
            @Override
            public CompletableFuture<ToolDispatchResult> callAsync(String toolName, Map<String, Object> input) {
                throw new IllegalStateException("bridge broke");
            }

            @Override
            public Optional<String> limitExceeded() {
                return Optional.empty();
            }
        };
        final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId("t").code("""
                try { await tools.Echo({}); } catch (e) { console.log('caught: ' + e.message); }
                await new Promise(() => {});
                """).tools(List.of(ToolDefinition.of("Echo", "echo", Map.of("type", "object"))))
                .limits(CodeExecutionLimits.defaults()).wallClock(Duration.ofSeconds(20)).cancellation(bridge.signal())
                .build();

        final long start = System.nanoTime();
        final CodeExecutionOutcome outcome = engine.execute(request, throwing, ToolContext.empty());

        assertThat(outcome.getStdout()).contains("caught: tool call failed: bridge broke");
        // With the call still counted, the never-settling await would wait out the 20 s wall clock instead.
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
        assertThat(outcome.getErrorMessage()).hasValueSatisfying(m -> assertThat(m).contains("never settles"));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("tools is read-only and lists exactly the callable set")
    void toolsObject() {
        final CodeExecutionOutcome outcome = run("""
                console.log(Object.keys(tools).join(','));
                try { tools.Other = 1; console.log('assigned'); } catch (e) { console.log('read-only'); }
                console.log(typeof tools.Missing);
                """);
        assertThat(outcome.getStdout()).contains("Echo\n").contains("read-only").contains("undefined");
    }
}
