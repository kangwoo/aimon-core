package at.aimon.core.skill.hook.declarative.predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;

class BashSubcommandPredicateTest {

    @Test
    void of_nullOrBlank_throws() {
        assertThatThrownBy(() -> BashSubcommandPredicate.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BashSubcommandPredicate.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BashSubcommandPredicate.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void test_nonBashTool_returnsFalse() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Read", inputWithCommand("git status"))).isFalse();
        assertThat(p.test("Edit", inputWithCommand("git status"))).isFalse();
    }

    @Test
    void test_missingCommandField_returnsFalse() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Bash", ToolInput.of())).isFalse();
        assertThat(p.test("Bash", ToolInput.of(Map.of("other", "x")))).isFalse();
        assertThat(p.test("Bash", ToolInput.of(Map.of("command", "")))).isFalse();
    }

    @Test
    void test_simpleCommandMatchesGlob() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Bash", inputWithCommand("git status"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("git push --force"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("npm install"))).isFalse();
    }

    @Test
    void test_exactPatternMatchesExactSubcommand() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("npm install");

        assertThat(p.test("Bash", inputWithCommand("npm install"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("npm install lodash"))).isFalse();
    }

    @Test
    void test_logicalAndSplit_eitherSubcommandMatches() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Bash", inputWithCommand("git status && rm -rf /tmp"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("rm -rf /tmp && git status"))).isTrue();
    }

    @Test
    void test_logicalAndSplit_rmPatternAlsoMatches() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm *");

        assertThat(p.test("Bash", inputWithCommand("git status && rm -rf /tmp"))).isTrue();
    }

    @Test
    void test_semicolonSplit_anyMatch() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Bash", inputWithCommand("echo hi; git status; echo bye"))).isTrue();
    }

    @Test
    void test_pipeSplit_anyMatch() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("grep *");

        assertThat(p.test("Bash", inputWithCommand("cat file | grep foo"))).isTrue();
    }

    @Test
    void test_logicalOrSplit_anyMatch() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm *");

        assertThat(p.test("Bash", inputWithCommand("git status || rm -rf /tmp"))).isTrue();
    }

    @Test
    void test_dollarParenSubstitution_innerMatches() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Bash", inputWithCommand("echo $(git status)"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("echo \"branch: $(git rev-parse HEAD)\""))).isTrue();
    }

    @Test
    void test_backtickSubstitution_innerMatches() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Bash", inputWithCommand("echo `git status`"))).isTrue();
    }

    @Test
    void test_quotedInnerString_matchesInnerSubcommand() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Bash", inputWithCommand("bash -c \"git push\""))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("bash -c \"echo hi && git push\""))).isTrue();
    }

    @Test
    void test_singleQuoteSuppressesSplit_butMatchesWholeWrapper() {
        // Single-quoted block is opaque; the outer command stays a single segment containing the whole quoted string.
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm *");

        assertThat(p.test("Bash", inputWithCommand("echo 'rm -rf /'"))).isFalse();
    }

    @Test
    void test_unterminatedQuote_fallsBackToSingleSegment() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("*git push*");

        // Tokenizer fails -> falls back to whole string as single sub-command.
        assertThat(p.test("Bash", inputWithCommand("echo \"git push"))).isTrue();
    }

    @Test
    void test_globPattern_matchesAnyPositionViaWildcard() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("*--force*");

        assertThat(p.test("Bash", inputWithCommand("git push --force"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("git push"))).isFalse();
    }

    @Test
    void test_nestedSubstitutions() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Bash", inputWithCommand("echo $(echo $(git status))"))).isTrue();
    }

    @Test
    void splitSubcommands_simpleAnd() {
        assertThat(BashSubcommandPredicate.splitSubcommands("a && b")).extracting(String::strip).containsExactly("a",
                "b");
    }

    @Test
    void splitSubcommands_mixedOperators() {
        assertThat(BashSubcommandPredicate.splitSubcommands("a && b || c ; d | e")).extracting(String::strip)
                .containsExactly("a", "b", "c", "d", "e");
    }

    @Test
    void splitSubcommands_quoteSuppressesSplit() {
        assertThat(BashSubcommandPredicate.splitSubcommands("echo 'a && b' && c")).extracting(String::strip)
                .containsExactly("echo 'a && b'", "c");
    }

    @Test
    void test_nonStringCommandValue_returnsFalse() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThat(p.test("Bash", ToolInput.of(Map.of("command", 42)))).isFalse();
    }

    @Test
    void test_nullInputs_throwNpe() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("git *");

        assertThatThrownBy(() -> p.test(null, ToolInput.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.test("Bash", null)).isInstanceOf(NullPointerException.class);
    }

    private static ToolInput inputWithCommand(String command) {
        return ToolInput.of(Map.of("command", command));
    }
}
