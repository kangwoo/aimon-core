package at.aimon.core.skill.hook.declarative.predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.mcp.McpServerConfig;

/**
 * A matcher term that parses and can match no tool (EE-85).
 *
 * <p>
 * A term without parentheses is a tool name in its entirety, so a matcher written in another grammar — a regular
 * expression, an input-field form, an {@code &} — used to parse into a name-only predicate for a tool of that name.
 * No tool has that name, the hook registered and never fired. The parser now refuses such a term, which turns it into
 * a matcher that does not parse: a startup failure on {@code preTool} and a skill that does not load (EE-72).
 *
 * <p>
 * The first group records what is refused. The second is the other half of the rule — nothing that <em>can</em> match
 * is refused: every tool name in the tree, the composed MCP names, and the globs over them.
 */
@DisplayName("a bare matcher term that can match no tool (EE-85)")
class UnmatchableBareTermTest {

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    // --- (1) refused ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            // Regular expressions on the tool name.
            "^Edit$", "^Edit", "Edit$", "Bash\\b", "[Bb]ash", "Rea?", "Bash+", "Bash{2}",
            // An input field, or a key=value form.
            "tool=Bash", "tool:Bash", "Bash & input.command~^npm", "input.command~^npm",
            // AND, a comma list, two names in one term.
            "Bash&Edit", "Bash && Edit", "Bash, Edit", "Bash,Edit", "Bash Edit",
            // One bad term is enough.
            "Read|^Edit$", "Read | Bash Edit"})
    void termWithACharacterNoToolNameHas_doesNotParse(String matcher) {
        assertThatThrownBy(() -> PredicateParser.parse(matcher)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can match no tool");
    }

    @Test
    void theMessageNamesTheTermAndTheCharacter_andSaysWhatTheGrammarIs() {
        assertThatThrownBy(() -> PredicateParser.parse("Read|^Edit$")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'^Edit$'").hasMessageContaining("'^'").hasMessageContaining("'*'")
                .hasMessageContaining("Tool(glob)");
        assertThatThrownBy(() -> PredicateParser.parse("Bash Edit")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'Bash Edit'").hasMessageContaining("whitespace");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"mcp__.*", "mcp__Git*", "mcp__GitHub__create_issue", "mcp__git.hub__*", "mcp__.*__delete"})
    void mcpTermWhoseServerSegmentNoServerNameCanBe_doesNotParse(String matcher) {
        assertThatThrownBy(() -> PredicateParser.parse(matcher)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can match no MCP tool").hasMessageContaining("mcp__<server>__<tool>");
    }

    @Test
    void theServerSegmentRule_isTheOneMcpServerConfigEnforces() {
        // The parser repeats the character class of McpServerConfig's name pattern rather than importing it. This
        // holds the two together: a name the config accepts is a segment the parser accepts, and the characters the
        // parser refuses are ones the config refuses.
        for (String server : new String[]{"github", "git-hub", "a", "s3", "my-server-2"}) {
            assertThatCode(() -> serverConfig(server)).as(server).doesNotThrowAnyException();
            assertThatCode(() -> PredicateParser.parse("mcp__" + server + "__tool")).as(server)
                    .doesNotThrowAnyException();
        }
        for (String server : new String[]{"GitHub", "git.hub", ".", "git hub"}) {
            assertThatThrownBy(() -> serverConfig(server)).as(server).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PredicateParser.parse("mcp__" + server + "__tool")).as(server)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static McpServerConfig serverConfig(String name) {
        return McpServerConfig.builder().name(name).transportType(McpServerConfig.McpTransportType.STDIO)
                .command("true").build();
    }

    // --- (2) not refused: anything that can match ----------------------------------------------------------------

    @Test
    void everyToolNameInTheTree_isATermThatParsesAndMatchesItself() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");
        final TreeSet<String> names = inTreeToolNames();
        // Not vacuous: the scan found the tools, including the shapes that stretch the character set.
        assertThat(names).hasSizeGreaterThan(30).contains("Bash", "Read", "schedule_task", "deriver.memory.search");

        for (String name : names) {
            assertThatCode(() -> PredicateParser.parse(name)).as(name).doesNotThrowAnyException();
            assertThat(PredicateParser.parse(name).test(name, ToolInput.of(Map.of()))).as(name).isTrue();
        }
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"*", "mcp__*", "*Search", "Wiki*", "mcp__github__*", "mcp__git*", "mcp__*__delete_*",
            "mcp__github__create_issue", "mcp__my-server-2__list.items", "deriver.*", "*.search", "schedule_*",
            "Read|Write|Edit", " Read | mcp__* ", "kebab-case-tool"})
    void namesAndNameGlobs_stillParse(String matcher) {
        assertThatCode(() -> PredicateParser.parse(matcher)).doesNotThrowAnyException();
    }

    @Test
    void dotIsAToolNameCharacter_soARegexDotStarIsNotRefused() {
        // 'deriver.memory.search' is a tool in this tree and MCP servers may name tools with dots, so 'deriver.*' is
        // a glob that matches. The same spelling written as a regex ('Bash.*' for "starts with Bash") cannot be told
        // apart from it and still parses; it gets a WARN, not a refusal.
        assertThat(PredicateParser.parse("deriver.*").test("deriver.memory.search", ToolInput.of(Map.of()))).isTrue();
        assertThat(PredicateParser.parse("Bash.*").test("BashOutput", ToolInput.of(Map.of()))).isFalse();
        assertThat(PredicateParser.parse("mcp__github__.*").test("mcp__github__create_issue", ToolInput.of(Map.of())))
                .isFalse();
    }

    @Test
    void theCharacterRuleStopsAtTheParenthesis_aGlobMayHoldAnything() {
        // Inside the parentheses the text is compared with a command or a path, and those hold any character.
        for (String matcher : new String[]{"Bash(FOO=1 make *)", "Bash(git commit -m *)", "Bash(*&*)", "Bash(^*)",
                "Read(/tmp/report (1)/*)", "Edit(*.env)", "Bash(a | b)"}) {
            assertThatCode(() -> PredicateParser.parse(matcher)).as(matcher).doesNotThrowAnyException();
        }
    }

    @Test
    void permissionSpelling_cannotBeRefused_itIsAlsoALegitimateGlob() {
        // allowed-tools writes "Bash(git:*)" for "git, any arguments". As a matcher the colon is a literal, so this
        // never fires on 'git push' ...
        assertThat(bash("Bash(git:*)", "git push")).isFalse();
        // ... but the same shape is what a guard on a script namespace looks like, and that one fires.
        assertThat(bash("Bash(npm run deploy:*)", "npm run deploy:prod")).isTrue();
        assertThat(bash("Bash(docker pull registry:*)", "docker pull registry:5000/app")).isTrue();
        // And the path spelling is not dead at all: '**' is two stars, which is one star.
        assertThat(path("Edit(**/*.java)", "/srv/app/src/Main.java")).isTrue();
        assertThat(path("Edit(**/*.java)", "src/Main.java")).isTrue();
        // It differs from the permission grammar only where there is no directory to cross.
        assertThat(path("Edit(**/*.java)", "Main.java")).isFalse();
    }

    private static boolean bash(String matcher, String command) {
        return PredicateParser.parse(matcher).test("Bash", ToolInput.of(Map.of("command", command)));
    }

    private static boolean path(String matcher, String filePath) {
        return PredicateParser.parse(matcher).test("Edit", ToolInput.of(Map.of("file_path", filePath)));
    }

    // --- (3) the guide ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"docs/features/hook/hook-config-guide.md", "docs/features/hook/hook-config-guide.en.md",
            "docs/references/aimon-skill-extensions.md"})
    void everyMatcherInACodeBlockOfTheGuide_parses(String relative) throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");
        final Path file = REPOSITORY_ROOT.resolve(relative);
        assumeTrue(Files.isRegularFile(file), relative + " is not in this checkout");

        final List<String> matchers = matchersInCodeBlocks(file);
        assertThat(matchers).as("matchers found in %s", relative).isNotEmpty();
        for (String matcher : matchers) {
            assertThatCode(() -> PredicateParser.parse(matcher)).as("%s: matcher \"%s\"", relative, matcher)
                    .doesNotThrowAnyException();
        }
    }

    /** {@code "matcher": "…"} (JSON) and {@code matcher: "…"} (YAML) inside fenced code blocks. */
    private static final Pattern MATCHER_VALUE = Pattern.compile("\"?matcher\"?\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static List<String> matchersInCodeBlocks(Path file) throws IOException {
        final List<String> out = new ArrayList<>();
        boolean fenced = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (!fenced) {
                continue;
            }
            final Matcher m = MATCHER_VALUE.matcher(line);
            while (m.find()) {
                final String value = m.group(1).replace("\\\\", "\\").replace("\\\"", "\"");
                // "<tool matcher>" is the schema placeholder, not an example.
                if (!value.startsWith("<")) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static final Pattern TOOL_NAME_CONSTANT = Pattern.compile("\\bTOOL_NAME\\s*=\\s*\"([^\"]+)\"");

    /** Every {@code TOOL_NAME = "…"} constant in main sources: each concrete tool in the tree declares one. */
    private static TreeSet<String> inTreeToolNames() throws IOException {
        final TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(REPOSITORY_ROOT.resolve("modules"))) {
            final List<Path> sources = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/")).toList();
            for (Path source : sources) {
                final Matcher m = TOOL_NAME_CONSTANT.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    private static Path locateRepositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))
                    && Files.isDirectory(candidate.resolve("modules"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
