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

    // --- commands the splitter does not take apart -------------------------------------------------------------

    @Test
    void test_nestingDeeperThanTheStack_isAnsweredNotThrown() throws Exception {
        // The splitter recursed once per nested "$(" with nothing to stop it, so the model picked how deep the stack
        // went. The thread's stack is small here so that the depth that overflows it does not depend on the platform.
        final String nested = "$(".repeat(20_000) + "rm -rf /" + ")".repeat(20_000);

        assertThat(onASmallStack(() -> BashSubcommandPredicate.of("rm -rf*").test("Bash", inputWithCommand(nested))))
                .isTrue();
    }

    @Test
    void test_commandNestedPastTheLimit_matchesWhateverThePattern() {
        // Not split, so the predicate cannot say that no sub-command matches. It says the hook is to be asked.
        final String nested = nest(BashSubcommandPredicate.MAX_NESTING_DEPTH + 1, "true");

        assertThat(BashSubcommandPredicate.of("git push*").test("Bash", inputWithCommand(nested))).isTrue();
    }

    @Test
    void test_commandNestedUpToTheLimit_isStillSplit() {
        final String nested = nest(BashSubcommandPredicate.MAX_NESTING_DEPTH, "rm -rf /");

        assertThat(BashSubcommandPredicate.of("rm -rf*").test("Bash", inputWithCommand(nested))).isTrue();
        // Split, not assumed: a pattern nothing in it matches is still a miss.
        assertThat(BashSubcommandPredicate.of("git push*").test("Bash", inputWithCommand(nested))).isFalse();
    }

    @Test
    void test_quotesAndBackticksCountTowardTheDepthToo() {
        // As deep as is still split, and then one block around it.
        final String atTheLimit = nest(BashSubcommandPredicate.MAX_NESTING_DEPTH, "true");
        final BashSubcommandPredicate p = BashSubcommandPredicate.of("git push*");

        assertThat(p.test("Bash", inputWithCommand(atTheLimit))).isFalse();
        assertThat(p.test("Bash", inputWithCommand("echo \"" + atTheLimit + "\""))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("echo `" + atTheLimit + "`"))).isTrue();
    }

    @Test
    void test_commandLongerThanTheLimit_matchesWhateverThePattern() {
        final String flat = "true; ".repeat(BashSubcommandPredicate.MAX_SPLIT_LENGTH / 6 + 1);
        assertThat(flat.length()).isGreaterThan(BashSubcommandPredicate.MAX_SPLIT_LENGTH);

        assertThat(BashSubcommandPredicate.of("git push*").test("Bash", inputWithCommand(flat))).isTrue();
    }

    @Test
    void test_commandAsLongAsTheLimit_isStillSplit() {
        final String filler = "true; ".repeat(BashSubcommandPredicate.MAX_SPLIT_LENGTH / 6);
        final String tail = "rm -rf /";
        final String flat = filler.substring(0, BashSubcommandPredicate.MAX_SPLIT_LENGTH - tail.length() - 2) + "; "
                + tail;
        assertThat(flat).hasSize(BashSubcommandPredicate.MAX_SPLIT_LENGTH);

        assertThat(BashSubcommandPredicate.of("rm -rf*").test("Bash", inputWithCommand(flat))).isTrue();
        assertThat(BashSubcommandPredicate.of("git push*").test("Bash", inputWithCommand(flat))).isFalse();
    }

    @Test
    void test_theLargestCommandThatIsSplit_isSplitQuickly() {
        // The worst the limits allow: every level copies what is left of the command.
        final int depth = BashSubcommandPredicate.MAX_NESTING_DEPTH;
        final int padding = (BashSubcommandPredicate.MAX_SPLIT_LENGTH - depth * 3) / depth - 1;
        final String worst = ("$(" + "x".repeat(padding) + " ").repeat(depth) + ")".repeat(depth);
        assertThat(worst.length()).isLessThanOrEqualTo(BashSubcommandPredicate.MAX_SPLIT_LENGTH);

        final long start = System.nanoTime();
        assertThat(BashSubcommandPredicate.of("git push*").test("Bash", inputWithCommand(worst))).isFalse();

        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000L);
    }

    private static String nest(int levels, String innermost) {
        return "$(".repeat(levels) + innermost + ")".repeat(levels);
    }

    /** Runs {@code body} on a thread with a 256 KB stack and returns what it returned, or rethrows what it threw. */
    private static boolean onASmallStack(java.util.function.BooleanSupplier body) throws Exception {
        final boolean[] result = new boolean[1];
        final Throwable[] thrown = new Throwable[1];
        final Thread thread = new Thread(null, () -> {
            try {
                result[0] = body.getAsBoolean();
            } catch (Throwable t) {
                thrown[0] = t;
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join(30_000);
        assertThat(thread.isAlive()).isFalse();
        if (thrown[0] != null) {
            throw new AssertionError("the predicate threw " + thrown[0].getClass().getSimpleName(), thrown[0]);
        }
        return result[0];
    }

    // --- text the splitter cannot pair up ------------------------------------------------------------------------

    @Test
    void test_apostropheInsideDoubleQuotes_doesNotHideTheCommandsAfterIt() {
        // To a shell the ' is a character of the string. Read as an opening quote it has no partner, and the whole
        // command used to come back as one piece, so the rm was never looked at.
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm -rf*");

        assertThat(p.test("Bash", inputWithCommand("echo \"it's\"; rm -rf /tmp/x"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("echo \"it's\" && rm -rf /tmp/x"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("echo \"it's fine\""))).isFalse();
    }

    @Test
    void test_apostropheInAComment_doesNotHideTheNextLine() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm -rf*");

        assertThat(p.test("Bash", inputWithCommand("true # don't\nrm -rf /tmp/x"))).isTrue();
        // Two comments, two apostrophes: paired up, they would quote the line between them.
        assertThat(p.test("Bash", inputWithCommand("true # it's\nrm -rf /tmp/x # don't"))).isTrue();
    }

    @Test
    void test_commentIsStillSplit_soReadingOneNeverHidesACommand() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm -rf*");

        // Not run by a shell, and matched all the same, as before: a comment only stops quotes from pairing up. Were
        // its text dropped, a # this reads as a comment and a shell does not would hide what follows it.
        assertThat(p.test("Bash", inputWithCommand("echo hi # ; rm -rf /tmp/x"))).isTrue();
        // A # inside a word starts no comment, so the quotes after it still quote.
        assertThat(p.test("Bash", inputWithCommand("echo a#'b; rm -rf /tmp/x'"))).isFalse();
    }

    @Test
    void test_hashThatIsNoCommentToAShell_doesNotHideTheSubstitutionAfterIt() {
        // Each of these runs its substitution in a shell: the # is inside double quotes, or follows an escaped space.
        // Read as a comment, and that reading taken in place of the plain one, every one of them went unmatched.
        BashSubcommandPredicate rm = BashSubcommandPredicate.of("rm -rf*");

        assertThat(rm.test("Bash", inputWithCommand("echo \"x # $(rm -rf /)\""))).isTrue();
        assertThat(rm.test("Bash", inputWithCommand("echo \"x # `rm -rf /`\""))).isTrue();
        assertThat(rm.test("Bash", inputWithCommand("x=\"# $(rm -rf /)\""))).isTrue();
        assertThat(rm.test("Bash", inputWithCommand("echo \"a\n# $(rm -rf /)\""))).isTrue();
        assertThat(rm.test("Bash", inputWithCommand("echo \"; # $(rm -rf /)\""))).isTrue();
        assertThat(rm.test("Bash", inputWithCommand("echo a\\ #$(rm -rf /)"))).isTrue();
        assertThat(BashSubcommandPredicate.of("git push*").test("Bash",
                inputWithCommand("echo \"fix #12 $(git push origin)\""))).isTrue();
    }

    @Test
    void test_openerLeftOpenInsideACommentOnly_stillMatchesAsBefore() {
        assertThat(BashSubcommandPredicate.of("sudo *").test("Bash", inputWithCommand("sudo || #(x}$(||"))).isTrue();
        assertThat(BashSubcommandPredicate.of("rm -rf x").test("Bash", inputWithCommand("echo # \"a; rm -rf x\"")))
                .isTrue();
    }

    @Test
    void test_loneApostropheInAHereDocument_doesNotHideTheCommandAfterIt() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm -rf*");

        assertThat(p.test("Bash", inputWithCommand("cat <<EOF\nit's\nEOF\nrm -rf /tmp/x"))).isTrue();
    }

    @Test
    void test_apostrophesInAHereDocument_doNotQuoteTheLinesAfterIt() {
        // Two apostrophes, one in the document and one after it: paired up, they quote the rm between them. A shell
        // reads the first as text of the document and runs the rm.
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm -rf*");

        assertThat(p.test("Bash", inputWithCommand("cat <<EOF\nit's\nEOF\nrm -rf /tmp/x\necho it's"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("cat <<'EOF'\nit's\nEOF\nrm -rf /tmp/x\necho it's"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("cat <<-EOF\n\tit's\n\tEOF\nrm -rf /tmp/x\necho it's"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("cat <<A <<B\nit's\nA\nb\nB\nrm -rf /tmp/x\necho it's"))).isTrue();
    }

    @Test
    void test_hereDocument_isOtherwiseReadAsBefore() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm -rf*");

        assertThat(p.test("Bash", inputWithCommand("cat <<EOF\nhello\nEOF\nls"))).isFalse();
        // A substitution in a document is run by a shell, and is still read.
        assertThat(p.test("Bash", inputWithCommand("cat <<EOF\n$(rm -rf /tmp/x)\nEOF"))).isTrue();
        // A here-string and a shift are no here-document: the quotes after them still quote.
        assertThat(p.test("Bash", inputWithCommand("cat <<<'a\nrm -rf /tmp/x\n'"))).isFalse();
    }

    @Test
    void test_unclosedSubstitution_doesNotHideTheCommandsInsideIt() {
        BashSubcommandPredicate p = BashSubcommandPredicate.of("rm -rf*");

        assertThat(p.test("Bash", inputWithCommand("echo $(date; rm -rf /tmp/x"))).isTrue();
        assertThat(p.test("Bash", inputWithCommand("echo `date; rm -rf /tmp/x"))).isTrue();
    }

    @Test
    void test_tooManyOpenersLeftOpen_matchesWhateverThePattern() {
        // Each one costs a scan to the end of the command. Past the limit the command is not split, and is answered
        // quickly.
        final String open = "$(".repeat(BashSubcommandPredicate.MAX_SPLIT_LENGTH / 2);

        final long start = System.nanoTime();
        assertThat(BashSubcommandPredicate.of("git push*").test("Bash", inputWithCommand(open))).isTrue();

        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000L);
        final String few = "echo $(a $(b $(c; ls";
        assertThat(BashSubcommandPredicate.of("git push*").test("Bash", inputWithCommand(few))).isFalse();
    }

    @Test
    void test_commandThatCouldNotBePairedUp_isStillMatchedAsAWholeToo() {
        // What matched before the pieces were looked at still matches: a glob written across an operator.
        BashSubcommandPredicate p = BashSubcommandPredicate.of("echo \"it's\"; rm*");

        assertThat(p.test("Bash", inputWithCommand("echo \"it's\"; rm -rf /tmp/x"))).isTrue();
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
