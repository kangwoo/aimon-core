package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionStatus;

@DisplayName("ExecuteCode support — renderer, observation, log escaping, limits")
class ExecuteCodeSupportTest {

    private static final ToolDefinition QUERY = ToolDefinition.of("mcp__prom__query-range",
            "Query a range.\nMulti line.",
            Map.of("type", "object", "properties",
                    Map.of("q", Map.of("type", "string", "description", "PromQL"), "step", Map.of("type", "integer"),
                            "mode", Map.of("type", "string", "enum", List.of("a", "b")), "tags",
                            Map.of("type", "array", "items", Map.of("type", "string"))),
                    "required", List.of("q")));

    @Test
    @DisplayName("JavaScript declarations: sorted, typed, indexed for non-identifiers, optional marks")
    void javascript() {
        final String rendered = ToolApiRenderer.render(CodeExecutionEngine.JAVASCRIPT,
                List.of(QUERY, ToolDefinition.of("Grep", "Search.", Map.of("type", "object"))), Set.of("Grep"));
        assertThat(rendered.indexOf("tools.Grep")).isLessThan(rendered.indexOf("tools[\"mcp__prom__query-range\"]"));
        assertThat(rendered).contains("q: string").contains("step?: number").contains("mode?: \"a\" | \"b\"")
                .contains("tags?: string[]").contains("/** Query a range. Multi line. */")
                .contains("tools.Grep(args: object): Promise<string>");
    }

    @Test
    @DisplayName("Python declarations: docstrings with typed args; non-identifiers through call_tool")
    void python() {
        final ToolDefinition read = ToolDefinition.of("Read", "Read a file.", Map.of("type", "object", "properties",
                Map.of("path", Map.of("type", "string", "description", "file")), "required", List.of("path")));
        final String rendered = ToolApiRenderer.render(CodeExecutionEngine.PYTHON, List.of(read, QUERY), Set.of());
        assertThat(rendered).contains("async def Read(args: dict) -> str:").contains("path (str): file")
                .contains("await call_tool(\"mcp__prom__query-range\", args)");
    }

    @Test
    @DisplayName("log escaping turns control characters into \\xNN and caps lines")
    void modelText() {
        assertThat(ModelText.forLog("a\u001b[31mred\u0007")).isEqualTo("a\\x1B[31mred\\x07");
        assertThat(ModelText.escapeLine("x".repeat(1000))).hasSize(ModelText.MAX_LINE_CHARS + 1);
        final StringBuilder many = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            many.append("line").append(i).append('\n');
        }
        assertThat(ModelText.forLog(many.toString())).contains("more lines]").doesNotContain("line25");
    }

    @Test
    @DisplayName("the observation cuts output, appends the return value, side channels and the summary")
    void observation() {
        final CodeExecutionOutcome outcome = CodeExecutionOutcome.builder().status(CodeExecutionStatus.COMPLETED)
                .stdout("0123456789").logs("log line").returnValue("4242").build();
        final List<NestedToolCall> calls = List.of(new NestedToolCall("t#1", "Read", "{}", 3, false, null, 1),
                new NestedToolCall("t#2", "Read", "{}", 3, true, null, 1),
                new NestedToolCall("t#3", "Write", "{}", 3, true, "write cap", 0));
        final String text = ExecuteCodeObservation.success(outcome, calls, List.of("env changed"), List.of("audited"),
                List.of(), Duration.ofMillis(3200),
                CodeExecutionLimits.builder().maxObservationChars(12).maxLogChars(100).build());
        assertThat(text).startsWith("0123456789\n42\n[truncated 2 chars]").contains("[environment]\nenv changed")
                .contains("[hook feedback]\naudited").contains("[logs]\nlog line")
                .endsWith("[ExecuteCode: 3 tool calls (Read x2, Write x1), 1 error, 1 refused by write cap, 3.2s]");
        final String failure = ExecuteCodeObservation.failure(
                CodeExecutionOutcome.builder().status(CodeExecutionStatus.TIMED_OUT).errorMessage("stack\nlast line")
                        .build(),
                List.of(), List.of(), List.of(), List.of("Read"), Duration.ZERO, CodeExecutionLimits.defaults());
        assertThat(failure).startsWith("ExecuteCode timed out: last line").contains("smaller programs")
                .contains("still running when the script ended: Read");
    }

    @Test
    @DisplayName("a program stopped from outside keeps its partial output, cut at the limit; a script error does not")
    void partialOutputOnStop() {
        final CodeExecutionLimits limits = CodeExecutionLimits.builder().maxObservationChars(5).build();
        for (CodeExecutionStatus stopped : List.of(CodeExecutionStatus.TIMED_OUT, CodeExecutionStatus.LIMIT_EXCEEDED)) {
            final String text = ExecuteCodeObservation.failure(
                    CodeExecutionOutcome.builder().status(stopped).stdout("done 1-3\n").errorMessage("stopped").build(),
                    List.of(), List.of(), List.of(), List.of(), Duration.ZERO, limits);
            assertThat(text).as(stopped.name()).contains("[output before the stop]\ndone \n[truncated 4 chars]");
        }
        final String noOutput = ExecuteCodeObservation.failure(
                CodeExecutionOutcome.builder().status(CodeExecutionStatus.TIMED_OUT).errorMessage("stopped").build(),
                List.of(), List.of(), List.of(), List.of(), Duration.ZERO, limits);
        assertThat(noOutput).doesNotContain("[output before the stop]");
        final String scriptError = ExecuteCodeObservation.failure(
                CodeExecutionOutcome.builder().status(CodeExecutionStatus.SCRIPT_ERROR).stdout("done 1-3\n")
                        .errorMessage("boom").build(),
                List.of(), List.of(), List.of(), List.of(), Duration.ZERO, limits);
        assertThat(scriptError).doesNotContain("done 1-3").doesNotContain("[output before the stop]");
    }

    @Test
    @DisplayName("limits: the requested wall clock is capped; inconsistent limits are refused")
    void limits() {
        final CodeExecutionLimits limits = CodeExecutionLimits.defaults();
        assertThat(limits.wallClockFor(null)).isEqualTo(Duration.ofSeconds(120));
        assertThat(limits.wallClockFor(30)).isEqualTo(Duration.ofSeconds(30));
        assertThat(limits.wallClockFor(10_000)).isEqualTo(Duration.ofSeconds(600));
        assertThat(limits.wallClockFor(0)).isEqualTo(Duration.ofSeconds(120));
        assertThatIllegalArgumentException().isThrownBy(() -> CodeExecutionLimits.builder().maxNestedCalls(0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CodeExecutionLimits.builder().wallClock(Duration.ofSeconds(700)).build());
    }
}
