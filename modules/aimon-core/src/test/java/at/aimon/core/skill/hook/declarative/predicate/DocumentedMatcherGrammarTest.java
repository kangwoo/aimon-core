package at.aimon.core.skill.hook.declarative.predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

/**
 * Pins the matcher grammar as the hook guide documents it
 * ({@code docs/features/hook/hook-config-guide.md} › "Matcher 문법").
 *
 * <p>
 * The guide used to document regular expressions, an input-field form and an {@code &} operator. The parser never
 * had any of the three, and — which is what made it a hole rather than a typo — every one of those spellings
 * <em>parsed</em>: it became a name or a glob that no tool call can match, so the guard written with it registered
 * and never fired, without an error at startup. The first group of tests records what became of each (EE-85): the
 * two that are a term without parentheses no longer parse, the one inside parentheses still does and still matches
 * nothing. The second group pins each row of the rewritten table. A change to the parser that breaks a test here
 * changes what the guide promises.
 */
@DisplayName("the matcher grammar the hook guide documents")
class DocumentedMatcherGrammarTest {

    private static boolean matches(String matcher, String tool, Map<String, Object> input) {
        final ToolInputPredicate predicate = PredicateParser.parse(matcher);
        return predicate.test(tool, ToolInput.of(input));
    }

    private static boolean matchesBash(String matcher, String command) {
        return matches(matcher, "Bash", Map.of("command", command));
    }

    // --- what the guide used to document -----------------------------------------------------------------------

    @Test
    void regexOnTheToolName_noLongerParses() {
        // Documented as "every tool starting with mcp__". It was a glob that needs a literal dot after "mcp__",
        // where a server name goes, and no server name starts with one.
        assertThatThrownBy(() -> PredicateParser.parse("mcp__.*")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can match no MCP tool").hasMessageContaining("'mcp__*'");
        // The spelling that does what was meant.
        assertThat(matches("mcp__*", "mcp__github__create_issue", Map.of())).isTrue();
    }

    @Test
    void inputFieldRegex_isStillALiteralSubcommandGlob() {
        // Documented as "tool name plus an input-field match"; the three guide examples that used it were deny and
        // approval guards. Inside the parentheses nothing can be refused: a command line holds any character.
        assertThat(matchesBash("Bash(command=^git\\s+push)", "git push origin main")).isFalse();
        assertThat(matchesBash("Bash(command=^rm\\s+-rf\\s+/)", "rm -rf /")).isFalse();
        assertThat(matchesBash("Bash(command=^git\\s+push.*--force)", "git push --force")).isFalse();
        assertThat(matchesBash("Bash(command=^kubectl\\s+apply.*-prod)", "kubectl apply -f x -n app-prod")).isFalse();
        // Only a command that is literally that text would match.
        assertThat(matchesBash("Bash(command=^git\\s+push)", "command=^git\\s+push")).isTrue();
    }

    @Test
    void ampersand_isNotAnOperator_andTheTermNoLongerParses() {
        // The whole text was one tool name. No tool has a name with a space, an '&' or a '~' in it.
        assertThatThrownBy(() -> PredicateParser.parse("Bash & input.command~^npm"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("can match no tool");
    }

    @Test
    void permissionPatternGrammar_isNotTheMatcherGrammar() {
        // allowed-tools writes "Bash(git:*)" for "git, any arguments". As a hook matcher the colon is a literal.
        assertThat(matchesBash("Bash(git:*)", "git push")).isFalse();
        assertThat(matchesBash("Bash(git *)", "git push")).isTrue();
    }

    // --- the table as it is documented now ---------------------------------------------------------------------

    @Test
    void row_exactToolName() {
        assertThat(matches("Bash", "Bash", Map.of())).isTrue();
        assertThat(matches("Bash", "BashOutput", Map.of())).isFalse();
        assertThat(matches("Bash", "bash", Map.of())).isFalse();
    }

    @Test
    void row_anyOfSeveralNames() {
        for (String tool : new String[]{"Read", "Write", "Edit"}) {
            assertThat(matches("Read|Write|Edit", tool, Map.of())).as(tool).isTrue();
        }
        assertThat(matches("Read|Write|Edit", "Grep", Map.of())).isFalse();
        assertThat(matches(" Read | Write ", "Write", Map.of())).isTrue();
    }

    @Test
    void row_nameGlob_starIsTheOnlyWildcard() {
        assertThat(matches("mcp__*", "mcp__github__create_issue", Map.of())).isTrue();
        assertThat(matches("*Search", "WebSearch", Map.of())).isTrue();
        assertThat(matches("mcp__*", "Bash", Map.of())).isFalse();
        // '.' is a literal; '?' is not a name character at all, so the term does not parse.
        assertThat(matches("Rea.", "Read", Map.of())).isFalse();
        assertThatThrownBy(() -> PredicateParser.parse("Rea?")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void row_bashSubcommandGlob_matchesAWholeSubcommand() {
        assertThat(matchesBash("Bash(git push*)", "git push origin main")).isTrue();
        assertThat(matchesBash("Bash(git push*)", "cd repo && git push")).isTrue();
        assertThat(matchesBash("Bash(git push*)", "echo ok; git push --force")).isTrue();
        assertThat(matchesBash("Bash(git push*)", "bash -c \"git push\"")).isTrue();
        assertThat(matchesBash("Bash(git push*)", "echo $(git push)")).isTrue();
        // The glob covers the whole sub-command, so a prefix needs a trailing star ...
        assertThat(matchesBash("Bash(git push)", "git push origin main")).isFalse();
        // ... and a sub-command that merely contains the text does not match a pattern anchored at its start.
        assertThat(matchesBash("Bash(git push*)", "sudo git push")).isFalse();
        assertThat(matchesBash("Bash(*git push*)", "sudo git push")).isTrue();
        // Text, not shell semantics: other spacing is another command as far as the glob can tell.
        assertThat(matchesBash("Bash(git push*)", "git  push")).isFalse();
        assertThat(matches("Bash(git push*)", "Read", Map.of("command", "git push"))).isFalse();
    }

    @Test
    void row_pathGlob_matchesTheWholePathArgument() {
        assertThat(matches("Edit(*.env)", "Edit", Map.of("file_path", "/srv/app/.env"))).isTrue();
        assertThat(matches("Edit(*.env)", "Edit", Map.of("file_path", ".env"))).isTrue();
        assertThat(matches("Edit(*.env)", "Edit", Map.of("file_path", "/srv/app/.env.example"))).isFalse();
        // No leading star: only a path that is exactly that text.
        assertThat(matches("Write(.env)", "Write", Map.of("file_path", "/srv/app/.env"))).isFalse();
        // '*' crosses directory separators; there is no '**'.
        assertThat(matches("Read(/etc/*)", "Read", Map.of("file_path", "/etc/ssh/sshd_config"))).isTrue();
        // Bound to the named tool.
        assertThat(matches("Edit(*.env)", "Write", Map.of("file_path", ".env"))).isFalse();
    }

    @Test
    void row_orOfArgumentTerms_andAPipeInsideParenthesesIsPatternText() {
        final String matcher = "Bash(rm -rf*)|Write(*.env)";
        assertThat(matchesBash(matcher, "rm -rf build")).isTrue();
        assertThat(matches(matcher, "Write", Map.of("file_path", "conf/.env"))).isTrue();
        assertThat(matchesBash(matcher, "ls")).isFalse();
        // Inside the parentheses '|' does not separate terms: it is part of one glob, and since the command is
        // split on '|' before matching, no sub-command can contain it.
        assertThat(matchesBash("Bash(git *|grep *)", "git log | grep fix")).isFalse();
    }

    @Test
    void theGuidesExampleGuards_fireOnTheCommandsTheyName() {
        // Examples 2, 5 and 6 of the guide, which were written in the input-field form and never fired.
        assertThat(matchesBash("Bash(rm -rf /*)", "rm -rf /")).isTrue();
        assertThat(matchesBash("Bash(rm -rf /*)", "cd /tmp && rm -rf /var/lib")).isTrue();
        assertThat(matchesBash("Bash(rm -rf /*)", "rm -rf build")).isFalse();
        // The limits the guide states next to example 2.
        assertThat(matchesBash("Bash(rm -rf /*)", "rm -fr /")).isFalse();
        assertThat(matchesBash("Bash(rm -rf /*)", "sudo rm -rf /")).isFalse();
        assertThat(matchesBash("Bash(*rm -rf /*)", "sudo rm -rf /")).isTrue();

        assertThat(matchesBash("Bash(git push*--force*)", "git push origin main --force")).isTrue();
        assertThat(matchesBash("Bash(git push*--force*)", "git push --force-with-lease")).isTrue();
        assertThat(matchesBash("Bash(git push*--force*)", "git push origin main")).isFalse();

        assertThat(matchesBash("Bash(kubectl apply*-prod*)", "kubectl apply -f app.yaml -n shop-prod")).isTrue();
        assertThat(matchesBash("Bash(kubectl apply*-prod*)", "kubectl apply -f app.yaml -n shop-dev")).isFalse();
    }

    @Test
    void whatDoesNotParse() {
        // An argument pattern on a tool that is neither Bash nor a path tool.
        assertThatThrownBy(() -> PredicateParser.parse("WebFetch(https://*)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("mcp__github__create_issue(*)"))
                .isInstanceOf(IllegalArgumentException.class);
        // Unbalanced parentheses, an empty pattern, an empty term, text after the closing parenthesis.
        assertThatThrownBy(() -> PredicateParser.parse("Bash(rm *")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("Bash()")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("Read|")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("Bash(rm *) & Edit"))
                .isInstanceOf(IllegalArgumentException.class);
        // A term without parentheses that holds a character no tool name has.
        assertThatThrownBy(() -> PredicateParser.parse("^Edit$")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("Bash Edit")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateParser.parse("Bash,Edit")).isInstanceOf(IllegalArgumentException.class);
    }
}
