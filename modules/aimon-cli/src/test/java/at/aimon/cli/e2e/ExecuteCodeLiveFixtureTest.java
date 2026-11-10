package at.aimon.cli.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.cli.e2e.ExecuteCodeLiveFixtures.Fixture;
import at.aimon.cli.e2e.ExecuteCodeLiveFixtures.RunDirectoryRecorder;
import at.aimon.cli.e2e.ExecuteCodeLiveFixtures.Variant;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;
import at.aimon.core.tracing.SpanType;
import at.aimon.graaljs.GraalJsEngineHolder;
import at.aimon.graaljs.code.GraalJsCodeExecutionEngine;

/**
 * The real-model E2E's fixture, proven offline in plain {@code test}: no key, no cost, not gated. The same
 * {@link ExecuteCodeLiveFixtures} the live classes use, with a scripted model in place of the real one. It proves,
 * before any billed run, that each variant offers {@code ExecuteCode} with both host tools callable and neither offered
 * directly, that a scripted program passes the B.3.3 assertions, and — for the shell variant — that the stray-process
 * mechanism records a run directory while the runtime is alive and finds nothing left after the stack closes.
 */
@DisplayName("ExecuteCode live E2E fixture (offline, scripted model)")
class ExecuteCodeLiveFixtureTest {

    private static final String JS_PROGRAM = """
            const hosts = ['h1', 'h2', 'h3', 'h4', 'h5'];
            for (const h of hosts) {
              const s = JSON.parse(await tools.GetHostStatus({ host: h }));
              const l = JSON.parse(await tools.GetHostLoad({ host: h }));
              if (s.status === 'down' || l.load > 0.8) console.log(h);
            }
            """;

    private static final String PYTHON_PROGRAM = """
            import json
            for h in ['h1', 'h2', 'h3', 'h4', 'h5']:
                s = json.loads(await GetHostStatus({'host': h}))
                l = json.loads(await GetHostLoad({'host': h}))
                if s['status'] == 'down' or l['load'] > 0.8:
                    print(h)
            """;

    @Test
    @DisplayName("JavaScript: ExecuteCode offered with both tools callable, and a scripted program passes the checks")
    void javascript(@TempDir Path workspace) {
        final ScriptedModel model = new ScriptedModel(JS_PROGRAM);
        try (GraalJsEngineHolder holder = GraalJsEngineHolder.create();
                Fixture fixture = ExecuteCodeLiveFixtures.start(model, Variant.JAVASCRIPT,
                        new GraalJsCodeExecutionEngine(holder), workspace, null)) {
            final OrcaAgentExecutionResult result = fixture.runTurn();

            assertOfferShape(model, fixture);
            final ExecuteCodeLiveFixtures.NestedCounts nested = ExecuteCodeLiveFixtures.assertCoreClaims(fixture,
                    result);
            assertThat(nested.bestProgram).isEqualTo(10);
            assertThat(nested.total).isEqualTo(10);
            assertThat(ExecuteCodeLiveFixtures.answerLooksRight(result)).isTrue();
            ExecuteCodeLiveFixtures.assertAnswerReached(fixture, result);
        }
    }

    @Test
    @DisplayName("assertion 3: a program that printed the right hosts passes although the final answer only says it"
            + " printed them — the shape behind the old answerMatches=false")
    void answerPrintedButNotRepeated(@TempDir Path workspace) {
        final ScriptedModel model = new ScriptedModel(JS_PROGRAM, "The names have been printed as requested.");
        try (GraalJsEngineHolder holder = GraalJsEngineHolder.create();
                Fixture fixture = ExecuteCodeLiveFixtures.start(model, Variant.JAVASCRIPT,
                        new GraalJsCodeExecutionEngine(holder), workspace, null)) {
            final OrcaAgentExecutionResult result = fixture.runTurn();

            assertThat(ExecuteCodeLiveFixtures.answerLooksRight(result)).isFalse();
            assertThat(ExecuteCodeLiveFixtures.programPrinted(fixture.executeCodeResults))
                    .containsExactly(List.of("h2", "h3", "h5"));
            ExecuteCodeLiveFixtures.assertAnswerReached(fixture, result);
            assertThat(ExecuteCodeLiveFixtures.resultLine("scripted", "scripted", Variant.JAVASCRIPT, "PASS", fixture,
                    result)).contains("answer=CORRECT programPrinted=[[h2, h3, h5]] finalAnswerMatches=false");
        }
    }

    @Test
    @DisplayName("assertion 3: a program that printed the wrong hosts, and a final answer that names none, fail")
    void wrongAnswerFails(@TempDir Path workspace) {
        final String everyHost = "for (const h of ['h1', 'h2', 'h3', 'h4', 'h5']) {"
                + " await tools.GetHostStatus({ host: h }); await tools.GetHostLoad({ host: h }); console.log(h); }";
        final ScriptedModel model = new ScriptedModel(everyHost, "Done.");
        try (GraalJsEngineHolder holder = GraalJsEngineHolder.create();
                Fixture fixture = ExecuteCodeLiveFixtures.start(model, Variant.JAVASCRIPT,
                        new GraalJsCodeExecutionEngine(holder), workspace, null)) {
            final OrcaAgentExecutionResult result = fixture.runTurn();

            ExecuteCodeLiveFixtures.assertCoreClaims(fixture, result);
            assertThatThrownBy(() -> ExecuteCodeLiveFixtures.assertAnswerReached(fixture, result))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("programs printed [[h1, h2, h3, h4, h5]]");
            assertThat(ExecuteCodeLiveFixtures.resultLine("scripted", "scripted", Variant.JAVASCRIPT, "FAIL", fixture,
                    result)).contains("answer=WRONG");
        }
    }

    @Test
    @DisplayName("assertion 3: a program that printed the right hosts and then hit SCRIPT_ERROR does not pass")
    void answerPrintedThenScriptErrorFails(@TempDir Path workspace) {
        final ScriptedModel model = new ScriptedModel(JS_PROGRAM + "throw new Error('after the answer');\n", "Done.");
        try (GraalJsEngineHolder holder = GraalJsEngineHolder.create();
                Fixture fixture = ExecuteCodeLiveFixtures.start(model, Variant.JAVASCRIPT,
                        new GraalJsCodeExecutionEngine(holder), workspace, null)) {
            final OrcaAgentExecutionResult result = fixture.runTurn();

            assertThat(fixture.executeCodeResults).singleElement().satisfies(
                    program -> assertThat(program.getRenderPayload()).containsEntry("status", "SCRIPT_ERROR"));
            assertThat(ExecuteCodeLiveFixtures.programPrinted(fixture.executeCodeResults))
                    .as("non-vacuity: the hosts were printed").containsExactly(List.of("h2", "h3", "h5"));
            assertThat(ExecuteCodeLiveFixtures.answerReached(fixture.executeCodeResults, result)).isFalse();
            assertThatThrownBy(() -> ExecuteCodeLiveFixtures.assertAnswerReached(fixture, result))
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    @DisplayName("Python (shell engine, sandboxed local environment): offered, checks pass, and no runtime is left")
    void python(@TempDir Path workspace) throws Exception {
        assumeTrue(ExecuteCodeLiveFixtures.python3Available(), "python3 >= 3.8 is not on the path");
        final ScriptedModel model = new ScriptedModel(PYTHON_PROGRAM);
        final RunDirectoryRecorder recorder = new RunDirectoryRecorder();
        try (ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create()) {
            try (Fixture fixture = ExecuteCodeLiveFixtures.start(model, Variant.PYTHON, engine, workspace, recorder)) {
                final OrcaAgentExecutionResult result = fixture.runTurn();

                assertOfferShape(model, fixture);
                // No reason recorded on the iteration span: the shell engine was available.
                assertThat(fixture.spans).filteredOn(span -> span.getType() == SpanType.ITERATION).allSatisfy(
                        span -> assertThat(span.getAttributes()).doesNotContainKey("ptc.unavailable_reason"));
                final ExecuteCodeLiveFixtures.NestedCounts nested = ExecuteCodeLiveFixtures.assertCoreClaims(fixture,
                        result);
                assertThat(nested.bestProgram).isEqualTo(10);
                assertThat(nested.total).isEqualTo(10);
                assertThat(ExecuteCodeLiveFixtures.answerLooksRight(result)).isTrue();
                ExecuteCodeLiveFixtures.assertAnswerReached(fixture, result);
                // Non-vacuity: the pattern matched the live runtime while a nested call was pending.
                assertThat(recorder.directories).as("run directories recorded while the runtime was alive").isNotEmpty()
                        .allMatch(dir -> dir.contains("aimon-run-"));
            }
            recorder.assertNoneLeft(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("assertion 1 is per program: two programs calling one tool each do not add up to a pass")
    void nestedCallsAreCountedPerProgram() {
        final ToolUseResult statusOnly = program("p1", call(ExecuteCodeLiveFixtures.STATUS_TOOL, false, false));
        final ToolUseResult loadOnly = program("p2", call(ExecuteCodeLiveFixtures.LOAD_TOOL, false, false));
        final ExecuteCodeLiveFixtures.NestedCounts split = ExecuteCodeLiveFixtures
                .countNested(List.of(statusOnly, loadOnly));
        assertThat(split.total).isEqualTo(2);
        assertThat(split.bestProgram).as("no single program called both tools").isZero();
        assertThat(split.bestProgramId).isNull();

        // A refused call and an errored call do not count toward the program.
        final ToolUseResult refusedAndErrored = program("p3", call(ExecuteCodeLiveFixtures.STATUS_TOOL, false, false),
                call(ExecuteCodeLiveFixtures.LOAD_TOOL, true, false),
                call(ExecuteCodeLiveFixtures.LOAD_TOOL, false, true));
        assertThat(ExecuteCodeLiveFixtures.countNested(List.of(refusedAndErrored)).bestProgram).isZero();

        final ToolUseResult both = program("p4", call(ExecuteCodeLiveFixtures.STATUS_TOOL, false, false),
                call(ExecuteCodeLiveFixtures.LOAD_TOOL, false, false));
        final ExecuteCodeLiveFixtures.NestedCounts whole = ExecuteCodeLiveFixtures
                .countNested(List.of(statusOnly, both));
        assertThat(whole.bestProgram).isEqualTo(2);
        assertThat(whole.bestProgramId).isEqualTo("p4");
        assertThat(whole.total).isEqualTo(3);
    }

    @SafeVarargs
    private static ToolUseResult program(String id, Map<String, Object>... calls) {
        return ToolUseResult.success(id, "[ExecuteCode (JavaScript): …]")
                .withRenderPayload(Map.of("calls", List.of(calls), "status", "COMPLETED"));
    }

    private static Map<String, Object> call(String tool, boolean error, boolean refused) {
        final Map<String, Object> call = new java.util.LinkedHashMap<>();
        call.put("name", tool);
        call.put("error", error);
        if (refused) {
            call.put("refused", "call limit");
        }
        return call;
    }

    private static void assertOfferShape(ScriptedModel model, Fixture fixture) {
        assertThat(model.firstOffer).as("the model was called").isNotNull();
        final List<String> names = model.firstOffer.stream().map(ToolDefinition::getName).toList();
        assertThat(names).as("offer: %s%n%s", names, ExecuteCodeLiveFixtures.diagnostics(fixture, null))
                .contains(ExecuteCodeTool.TOOL_NAME)
                .doesNotContain(ExecuteCodeLiveFixtures.STATUS_TOOL, ExecuteCodeLiveFixtures.LOAD_TOOL);
        final String description = model.firstOffer.stream()
                .filter(definition -> ExecuteCodeTool.TOOL_NAME.equals(definition.getName())).findFirst().orElseThrow()
                .getDescription();
        assertThat(description).contains(ExecuteCodeLiveFixtures.STATUS_TOOL, ExecuteCodeLiveFixtures.LOAD_TOOL);
    }

    /**
     * First answers an {@code ExecuteCode} call with the program, then a final text: the host lines of its result, or
     * a fixed text when one is given.
     */
    private static final class ScriptedModel implements LlmClient {

        private final String program;
        private final String finalText;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> seen = new CopyOnWriteArrayList<>();
        private volatile List<ToolDefinition> firstOffer;

        ScriptedModel(String program) {
            this(program, null);
        }

        ScriptedModel(String program, String finalText) {
            this.program = program;
            this.finalText = finalText;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            if (calls.getAndIncrement() == 0) {
                firstOffer = List.copyOf(tools);
                return LlmResponse
                        .tools(List.of(ToolUse.of("call-1", ExecuteCodeTool.TOOL_NAME, Map.of("code", program))));
            }
            final Message last = messages.get(messages.size() - 1);
            last.getToolUseResults().forEach(result -> seen.add(result.getContent()));
            if (finalText != null) {
                return LlmResponse.text(finalText);
            }
            final String output = seen.isEmpty() ? "" : seen.get(0);
            return LlmResponse.text(output.lines().filter(line -> line.matches("h[1-5]")).reduce((a, b) -> a + "\n" + b)
                    .orElse("(nothing)"));
        }

        @Override
        public String getProviderName() {
            return "scripted";
        }
    }
}
