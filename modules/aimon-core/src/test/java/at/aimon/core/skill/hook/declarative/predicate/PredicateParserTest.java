package at.aimon.core.skill.hook.declarative.predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

class PredicateParserTest {

    @Test
    void parse_bareToolName_returnsNameOnlyPredicate() {
        ToolInputPredicate p = PredicateParser.parse("Read");
        assertThat(p).isInstanceOf(NameOnlyPredicate.class);
        assertThat(p.test("Read", ToolInput.of())).isTrue();
        assertThat(p.test("Edit", ToolInput.of())).isFalse();
    }

    @Test
    void parse_wildcard_returnsAnyPredicate() {
        ToolInputPredicate p = PredicateParser.parse("*");
        assertThat(p).isSameAs(NameOnlyPredicate.ANY);
        assertThat(p.test("Anything", ToolInput.of())).isTrue();
    }

    @Test
    void parse_bashWithGlob_returnsBashPredicate() {
        ToolInputPredicate p = PredicateParser.parse("Bash(git *)");
        assertThat(p).isInstanceOf(BashSubcommandPredicate.class);
        assertThat(p.test("Bash", ToolInput.of(Map.of("command", "git status")))).isTrue();
        assertThat(p.test("Bash", ToolInput.of(Map.of("command", "ls")))).isFalse();
    }

    @Test
    void parse_pathToolWithGlob_returnsPathGlobPredicate() {
        ToolInputPredicate p = PredicateParser.parse("Edit(*.ts)");
        assertThat(p).isInstanceOf(PathGlobPredicate.class);
        assertThat(p.test("Edit", ToolInput.of(Map.of("file_path", "src/foo.ts")))).isTrue();
        assertThat(p.test("Edit", ToolInput.of(Map.of("file_path", "src/foo.js")))).isFalse();
    }

    @Test
    void parse_pipeOr_combinesViaCompositeOr() {
        ToolInputPredicate p = PredicateParser.parse("Bash(git *)|Edit(*.ts)|Write(*.ts)");
        assertThat(p).isInstanceOf(CompositePredicate.class);
        CompositePredicate cp = (CompositePredicate) p;
        assertThat(cp.getOp()).isEqualTo(CompositePredicate.Op.OR);
        assertThat(cp.getDelegates()).hasSize(3);

        assertThat(p.test("Bash", ToolInput.of(Map.of("command", "git status")))).isTrue();
        assertThat(p.test("Edit", ToolInput.of(Map.of("file_path", "a.ts")))).isTrue();
        assertThat(p.test("Write", ToolInput.of(Map.of("file_path", "a.ts")))).isTrue();
        assertThat(p.test("Read", ToolInput.of(Map.of("file_path", "a.ts")))).isFalse();
    }

    @Test
    void parse_pipeInsideParens_isNotASplitter() {
        // The pipe inside Bash(...) is part of the glob, not an OR separator.
        ToolInputPredicate p = PredicateParser.parse("Bash(git *|grep *)");
        assertThat(p).isInstanceOf(BashSubcommandPredicate.class);
    }

    @Test
    void parse_mixedBareAndArgs_combinesCorrectly() {
        ToolInputPredicate p = PredicateParser.parse("Read|Bash(git *)");
        assertThat(p.test("Read", ToolInput.of())).isTrue();
        assertThat(p.test("Bash", ToolInput.of(Map.of("command", "git status")))).isTrue();
        assertThat(p.test("Edit", ToolInput.of())).isFalse();
    }

    @Test
    void parse_trimsWhitespaceAroundTerms() {
        ToolInputPredicate p = PredicateParser.parse("  Read  |  Edit  ");
        assertThat(p.test("Read", ToolInput.of())).isTrue();
        assertThat(p.test("Edit", ToolInput.of())).isTrue();
    }

    @Test
    void parse_allPathTools_arePathGlobPredicates() {
        for (String tool : new String[]{"Read", "Edit", "Write", "MultiEdit", "Glob", "Grep", "LS", "NotebookEdit"}) {
            ToolInputPredicate p = PredicateParser.parse(tool + "(*.txt)");
            assertThat(p).as("tool=%s", tool).isInstanceOf(PathGlobPredicate.class);
        }
    }

    @Test
    void parse_null_throwsNpe() {
        assertThatThrownBy(() -> PredicateParser.parse(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parse_blank_throws() {
        assertThatThrownBy(() -> PredicateParser.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_emptyTermBetweenPipes_throws() {
        assertThatThrownBy(() -> PredicateParser.parse("Read||Edit")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("|Read")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("Read|")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_unmatchedOpenParen_throws() {
        assertThatThrownBy(() -> PredicateParser.parse("Bash(git *")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_unmatchedCloseParen_throws() {
        assertThatThrownBy(() -> PredicateParser.parse("Bash)git *")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_termMissingClosingParen_throws() {
        // Term ends with something other than ')'.
        assertThatThrownBy(() -> PredicateParser.parse("Bash(git *) trailing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_emptyPattern_throws() {
        assertThatThrownBy(() -> PredicateParser.parse("Bash()")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("Edit(   )")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_missingToolName_throws() {
        assertThatThrownBy(() -> PredicateParser.parse("(git *)")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_unsupportedToolWithArgs_throws() {
        // Only Bash + the known path tools accept argument patterns.
        assertThatThrownBy(() -> PredicateParser.parse("HttpFetch(https://*)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("Custom(foo)")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_singleTerm_returnsDelegateUnchangedNotComposite() {
        ToolInputPredicate p = PredicateParser.parse("Bash(git *)");
        assertThat(p).isNotInstanceOf(CompositePredicate.class);
    }
}
