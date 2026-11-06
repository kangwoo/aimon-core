package at.aimon.programmatic.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.OfferFacts;

/**
 * The contract every {@code ExecuteCode} engine keeps ({@code docs/design/tool/programmatic-tool-calling.md} §17): the
 * same shape of program produces the same observation (decision 7), a tool's result arrives as a string and a failed
 * call as an error the program can catch (decision 6), and limits and cancellation have the same meaning (§13).
 *
 * <p>
 * A subclass supplies the engine ({@link #engine()}, created once per test and closed after it) and the contract's
 * programs in the engine's language ({@link #programs()}). The bridge is a scripted fake; the engine sees only the
 * {@code at.aimon.core.programmatic} SPI.
 */
public abstract class AbstractCodeExecutionEngineContractTest {

    /** The tool every contract program calls. */
    protected static final String TOOL = "Echo";

    private CodeExecutionEngine engine;
    private ScriptedToolCallBridge bridge;

    /**
     * Creates the engine under test. Called once per test; the suite closes it.
     *
     * @return a new engine (never null)
     */
    protected abstract CodeExecutionEngine engine();

    /**
     * Returns the contract programs in the engine's language.
     *
     * @return the programs (never null)
     */
    protected abstract EnginePrograms programs();

    @BeforeEach
    void createEngine() {
        engine = engine();
    }

    @AfterEach
    void closeEngine() throws Exception {
        if (bridge != null) {
            bridge.close();
        }
        engine.close();
    }

    @Test
    @DisplayName("names a language and is available on an empty offer")
    void describesItself() {
        assertThat(engine.language()).isIn(CodeExecutionEngine.JAVASCRIPT, CodeExecutionEngine.PYTHON);
        assertThat(engine.intrinsicGrade()).isNotNull();
        assertThat(engine.staticAvailability(OfferFacts.none())).isNotNull();
    }

    @Test
    @DisplayName("standard output is the observation")
    void stdoutIsTheObservation() {
        final CodeExecutionOutcome outcome = run(programs().printText("hello world"), succeeding("unused"));
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).contains("hello world");
        assertThat(outcome.getLogs()).doesNotContain("hello world");
    }

    @Test
    @DisplayName("standard error is the log, not the observation")
    void stderrIsTheLog() {
        final CodeExecutionOutcome outcome = run(programs().logText("to the log"), succeeding("unused"));
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getLogs()).contains("to the log");
        assertThat(outcome.getStdout()).doesNotContain("to the log");
    }

    @Test
    @DisplayName("a tool's result arrives as a string, and its arguments arrive detached")
    void toolResultIsAString() {
        bridge = ScriptedToolCallBridge
                .answering(call -> ScriptedToolCallBridge.success("echo:" + call.getInput().get("value")));
        final CodeExecutionOutcome outcome = run(programs().callAndPrint(TOOL, "ping"), bridge);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).contains("echo:ping");
        assertThat(bridge.calls()).singleElement().satisfies(call -> {
            assertThat(call.getToolName()).isEqualTo(TOOL);
            assertThat(call.getInput()).containsEntry("value", "ping");
        });
    }

    @Test
    @DisplayName("a failed call is an error the program can catch, carrying the tool's error text")
    void failedCallIsCatchable() {
        bridge = ScriptedToolCallBridge.answering(call -> ScriptedToolCallBridge.error("no such host"));
        final CodeExecutionOutcome outcome = run(programs().callAndCatch(TOOL), bridge);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).contains("caught: ").contains("no such host");
    }

    @Test
    @DisplayName("calls that do not depend on each other run concurrently and all complete")
    void fanOut() {
        bridge = ScriptedToolCallBridge.answering(call -> ScriptedToolCallBridge.success("ok"));
        final CodeExecutionOutcome outcome = run(programs().fanOut(TOOL, 12), bridge);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).contains("ok=12");
        assertThat(bridge.calls()).hasSize(12);
    }

    @Test
    @DisplayName("a raised error is a script error with its message")
    void scriptError() {
        final CodeExecutionOutcome outcome = run(programs().raise("boom happened"), succeeding("unused"));
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
        assertThat(outcome.getErrorMessage())
                .hasValueSatisfying(message -> assertThat(message).contains("boom happened"));
    }

    @Test
    @DisplayName("a program that does not parse is a script error")
    void syntaxError() {
        final CodeExecutionOutcome outcome = run(programs().syntaxError(), succeeding("unused"));
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("the wall clock stops a program that never yields: TIMED_OUT")
    void wallClock() {
        final CodeExecutionOutcome outcome = run(programs().spinForever(), succeeding("unused"),
                CodeExecutionLimits.builder().maxStatements(Long.MAX_VALUE).build(), Duration.ofSeconds(1));
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.TIMED_OUT);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("cancelling the execution stops a program waiting on a call: CANCELLED")
    void cancellation() throws Exception {
        bridge = ScriptedToolCallBridge.succeedingWith("unused").holdCalls();
        final CompletableFuture<CodeExecutionOutcome> running = CompletableFuture
                .supplyAsync(() -> run(programs().callAndWait(TOOL), bridge));
        while (bridge.calls().isEmpty()) {
            Thread.sleep(10);
        }
        bridge.cancel();
        final CodeExecutionOutcome outcome = running.get(20, TimeUnit.SECONDS);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.CANCELLED);
        assertThat(outcome.getStdout()).doesNotContain("returned");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a limit the bridge stops the program for ends it at once: LIMIT_EXCEEDED")
    void bridgeLimitStopsAtOnce() {
        bridge = ScriptedToolCallBridge.succeedingWith("ok").stopAfter(3);
        final CodeExecutionOutcome outcome = run(programs().callSequentially(TOOL, 50), bridge);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.LIMIT_EXCEEDED);
        assertThat(bridge.calls()).hasSizeLessThanOrEqualTo(5);
        assertThat(outcome.getStdout()).doesNotContain("done");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("output past the limit stops the program: LIMIT_EXCEEDED")
    void outputLimit() {
        final CodeExecutionOutcome outcome = run(programs().printForever(), succeeding("unused"),
                CodeExecutionLimits.builder().maxScriptOutputBytes(10_000).build(), Duration.ofSeconds(20));
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.LIMIT_EXCEEDED);
        assertThat(outcome.getStdout().length()).isLessThanOrEqualTo(10_000);
    }

    /**
     * Runs a program with the default limits and a 20-second wall clock.
     *
     * @param code
     *            the program
     * @param bridge
     *            the bridge (its signal is the program's)
     * @return the outcome
     */
    protected final CodeExecutionOutcome run(String code, ScriptedToolCallBridge bridge) {
        return run(code, bridge, CodeExecutionLimits.defaults(), Duration.ofSeconds(20));
    }

    /**
     * Runs a program.
     *
     * @param code
     *            the program
     * @param bridge
     *            the bridge (its signal is the program's)
     * @param limits
     *            the limits
     * @param wallClock
     *            the wall clock
     * @return the outcome
     */
    protected final CodeExecutionOutcome run(String code, ScriptedToolCallBridge bridge, CodeExecutionLimits limits,
            Duration wallClock) {
        final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId("toolu_contract").code(code)
                .tools(List.of(ToolDefinition.of(TOOL, "Echoes its input.",
                        Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string"))))))
                .limits(limits).wallClock(wallClock).cancellation(bridge.signal()).build();
        return engine.execute(request, bridge, ToolContext.empty());
    }

    private ScriptedToolCallBridge succeeding(String text) {
        bridge = ScriptedToolCallBridge.succeedingWith(text);
        return bridge;
    }
}
