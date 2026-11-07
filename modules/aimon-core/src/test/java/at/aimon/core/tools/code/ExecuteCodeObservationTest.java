package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionStatus;

/**
 * Which part of an engine's error message a failed program's observation shows (ptc.md §12.1, backlog TC-28): the
 * last line, except for a syntax error whose last line is the caret — then the message line, with the source line and
 * the caret as an aligned excerpt.
 */
@DisplayName("ExecuteCodeObservation — the error line of a failed program")
class ExecuteCodeObservationTest {

    @Test
    @DisplayName("a three-line caret message gives the message line plus the source and caret, aligned")
    void caretMessage() {
        final String message = "SyntaxError: <program>:1:14 Expected ; but found tools\n"
                + "const x = 1 + tools.Read({})\n" + "              ^\n";
        assertThat(ExecuteCodeObservation.errorLine(message))
                .isEqualTo("SyntaxError: <program>:1:14 Expected ; but found tools\n"
                        + "  const x = 1 + tools.Read({})\n" + "                ^");
        assertThat(failure(message)).startsWith("ExecuteCode script error: SyntaxError: <program>:1:14 Expected ;"
                + " but found tools\n  const x = 1 + tools.Read({})\n                ^\n\n[ExecuteCode (JavaScript)");
    }

    @Test
    @DisplayName("a caret line with tildes is a caret line too")
    void caretWithTildes() {
        assertThat(ExecuteCodeObservation.errorLine("E: bad\nfoo(bar)\n    ^~~~"))
                .isEqualTo("E: bad\n  foo(bar)\n      ^~~~");
    }

    @Test
    @DisplayName("a source line over the limit (minified code) gives the header alone")
    void longSourceLine() {
        final String source = "x".repeat(ExecuteCodeObservation.MAX_EXCERPT_SOURCE_CHARS + 1);
        assertThat(ExecuteCodeObservation.errorLine("SyntaxError: <program>:1:300 Expected ;\n" + source + "\n   ^"))
                .isEqualTo("SyntaxError: <program>:1:300 Expected ;");
    }

    @Test
    @DisplayName("a single-line message is unchanged")
    void singleLine() {
        assertThat(ExecuteCodeObservation.errorLine("  TypeError: x is not a function  "))
                .isEqualTo("TypeError: x is not a function");
    }

    @Test
    @DisplayName("a multi-line message whose last line is not a caret keeps the last-line rule")
    void lastLineRule() {
        assertThat(ExecuteCodeObservation.errorLine("stack\nlast line")).isEqualTo("last line");
        assertThat(ExecuteCodeObservation.errorLine("a\nb\nc ^")).isEqualTo("c ^");
        assertThat(ExecuteCodeObservation.errorLine("source\n  ^")).as("two lines: no message line to lead with")
                .isEqualTo("^");
    }

    private static String failure(String message) {
        return ExecuteCodeObservation.failure(CodeExecutionEngine.JAVASCRIPT,
                CodeExecutionOutcome.builder().status(CodeExecutionStatus.SCRIPT_ERROR).errorMessage(message).build(),
                List.of(), List.of(), List.of(), List.of(), Duration.ZERO, CodeExecutionLimits.defaults());
    }
}
