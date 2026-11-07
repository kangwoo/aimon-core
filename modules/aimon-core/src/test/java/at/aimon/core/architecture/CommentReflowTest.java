package at.aimon.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No comment line in the programmatic-tool-calling code is left stranded by a wrap: one or two words alone on a line
 * that the next line continues ({@code docs/design/tool/ptc-features-core.md} A.1).
 *
 * <h2>Why it recurs</h2>
 *
 * <p>
 * {@code config/eclipse/eclipse-formatter.xml} sets {@code comment.line_length=120} and
 * {@code join_lines_in_comments=false}: a comment line written slightly over 120 columns is broken, and the formatter
 * never joins lines back, so the last word or two end up on a line of their own ({@code returns} / {@code null}).
 * Turning the join on would reflow every comment in the repository at once; this test guards the PTC code instead, and
 * its {@link #JAVA_ROOTS} and {@link #MARKDOWN} lists are the extension point for a wider sweep.
 *
 * <h2>The orphan</h2>
 *
 * <p>
 * A javadoc or block-comment continuation line ({@code * text}) is a candidate when it does not start a tag, an HTML
 * element, {@code {@code}, a list dash or a table bar, and the next line continues the same paragraph. It is an orphan
 * when its length plus a space plus the next line's first unbreakable unit ({@link #unit}: an inline tag such as
 * {@code {@link Foo}} with the punctuation glued to it, or else the first token) fits in 120 columns — a reflow would
 * have joined them; that condition is the formatter's signature, and it lets a long single token on its own line pass
 * — and, in addition, either
 *
<ul>
 *
<li><b>form A</b>: its text has at most two tokens — the last word or two of a line broken by force; or
 *
<li><b>form B</b>: it is at most {@link #FORM_B_MAX_LINE} columns wide, and the line before it is in the same
 * paragraph and was broken by force — that line plus a space plus this line's first unit is over 120. This is the
 * formatter's other artefact: the medium-length remainder of a line written more than a word or two over 120, which
 * the next line could have filled. Hand-wrapped prose, broken by the author at 110–116 columns, fails the
 * forced-break condition.
 *
</ul>
 * In the design documents, a prose line of at most two tokens, or at most 60 display columns wide, between two prose
 * lines, the previous one at least 100 columns wide (Hangul counted as two), is the same artefact: the second form is
 * {@code programmatic-tool-calling-shell.md}'s stranded {@code 모델}, which ended a short line rather than standing
 * alone.
 *
 *
<h2>Inputs</h2>
 *
 *
<p>
 * The sources of {@code aimon-graaljs}, {@code aimon-code-engine-testkit}, {@code aimon-cli} and the docs are not
 * inputs of this module's {@code test} task, for the reason {@code ReleaseGateMatchesCiGateTest} gives: declaring them
 * would re-run core's tests after any edit there. After an edit only in those trees the task can be
 * {@code UP-TO-DATE} locally; {@code --rerun} or CI, which always runs from clean, catches it.
 */
class CommentReflowTest {

    private static final int LINE_LENGTH = 120;
    /**
     * The widest line form B flags: at least 20 columns spare. Calibrated with a throwaway scan of every
     * {@code modules/*}{@code /src/*}{@code /java} at {@code 318805f5} (ptc-leftovers-core §1.10): form A alone found
     * 546
     * lines; form B without this bound and the forced-break condition 2 374, mostly hand-wrapped prose at 110–116
     * columns; with both, 212, which sampled as genuine artefacts.
     */
    private static final int FORM_B_MAX_LINE = 100;
    private static final int MARKDOWN_WIDE_LINE = 100;
    private static final int MARKDOWN_SHORT_LINE = 60;

    private static final String CORE_MAIN = "modules/aimon-core/src/main/java/at/aimon/core/";
    private static final String CORE_TEST = "modules/aimon-core/src/test/java/at/aimon/core/";

    /** The PTC code, as directories or single files, each with why it is in scope. */
    private static final Map<String, String> JAVA_ROOTS = Map.ofEntries(
            Map.entry(CORE_MAIN + "programmatic", "the engine SPI"),
            Map.entry(CORE_MAIN + "tools/code", "ExecuteCode, its bridge and the shell engine"),
            Map.entry(CORE_MAIN + "shell", "the shell contract the shell engine runs on"),
            Map.entry(CORE_MAIN + "agent/tool/ToolOfferContext.java", "the offer ExecuteCode describes itself in"),
            Map.entry(CORE_MAIN + "agent/tool/ToolDescriptor.java", "the descriptor ExecuteCode fills"),
            Map.entry(CORE_MAIN + "environment/RuntimeFileSystem.java", "the shell engine's runtime file SPI"),
            Map.entry(CORE_MAIN + "skill/execution/llm/LlmSkillExecutor.java", "the slash-skill loop's PTC wiring"),
            Map.entry(CORE_TEST + "tools/code", "test mirror"), Map.entry(CORE_TEST + "shell", "test mirror"),
            Map.entry(CORE_TEST + "architecture/InternalPackageBoundaryTest.java", "a named site of the C review"),
            Map.entry("modules/aimon-graaljs/src/main/java/at/aimon/graaljs/code", "the JavaScript engine"),
            Map.entry("modules/aimon-code-engine-testkit/src/main/java", "the engine contract suite"),
            Map.entry(CORE_MAIN + "agent/tool/ToolCallDispatcher.java", "the SPI that carries the caller's settings"),
            Map.entry(CORE_MAIN + "agent/tool/ProgrammaticToolSettings.java", "the settings and their narrowing"),
            Map.entry(CORE_MAIN + "agent/tool/ProgrammaticFrontmatter.java", "the shared frontmatter reading"),
            Map.entry(CORE_MAIN + "toolinvocation/ToolCallDispatchers.java", "the ungated dispatcher's PTC wiring"),
            Map.entry(CORE_MAIN + "tools/CallerProgrammatic.java", "the caller-ceiling accessor"),
            Map.entry("modules/aimon-cli/src/main/java/at/aimon/cli/config/ProgrammaticConfig.java",
                    "the CLI's PTC configuration"),
            Map.entry("modules/aimon-cli/src/test/java/at/aimon/cli/e2e", "the real-model E2E harness"));

    /**
     * The PTC design documents. {@code docs/design/} is not translated (documentation guide §5.1), so there is no
     * {@code .en.md} to scan.
     */
    private static final List<String> MARKDOWN = List.of("docs/design/tool/programmatic-tool-calling",
            "docs/design/tool/programmatic-tool-calling-shell", "docs/design/tool/ptc-qc4-and-nonblocking-fixes",
            "docs/design/tool/tool-call-dispatcher", "docs/design/tool/ptc-features-core",
            "docs/design/tool/ptc-leftovers-core");

    /** Accepted orphans, keyed by {@code <path>:<trimmed line text>}, each with its reason. */
    private static final Map<String, String> ALLOWED = Map.of();

    private static final Pattern CONTINUATION = Pattern.compile("^\\s*\\*\\s(.*)$");
    private static final Pattern BLOCK_START = Pattern
            .compile("^(@|<p>|<li>|</|<pre>|<ul>|<ol>|<h\\d|<table|<blockquote|<dl>|<dt>|<dd>)");
    private static final Pattern MARKDOWN_NOT_PROSE = Pattern.compile("^\\s*([-*+]\\s|\\d+[.)]\\s|\\||#|>|```|<)");

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    @DisplayName("no PTC comment line is stranded by a wrap: a word or two, or a forced remainder, a reflow would have"
            + " joined to the next line")
    void noOrphansInJava() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");
        final List<String> orphans = new ArrayList<>();
        int files = 0;
        for (String root : JAVA_ROOTS.keySet()) {
            final Path path = REPOSITORY_ROOT.resolve(root);
            assertThat(path).as("scope entry %s still exists", root).exists();
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    files++;
                    final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i : javaOrphans(lines)) {
                        report(orphans, file, i, lines.get(i));
                    }
                }
            }
        }
        assertThat(files).as("files scanned").isGreaterThan(50);
        assertThat(orphans).as("comment lines stranded by a wrap — join each to the line before it, or add a"
                + " reasoned entry to ALLOWED").isEmpty();
    }

    @Test
    @DisplayName("no PTC design document has a prose line of one or two words stranded by a wrap")
    void noOrphansInMarkdown() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");
        final List<String> orphans = new ArrayList<>();
        for (String doc : MARKDOWN) {
            final Path file = REPOSITORY_ROOT.resolve(doc + ".md");
            assertThat(file).as("scope entry %s still exists", doc + ".md").exists();
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i : markdownOrphans(lines)) {
                report(orphans, file, i, lines.get(i));
            }
        }
        assertThat(orphans).as("prose lines stranded by a wrap — join each to the line before it").isEmpty();
    }

    @Test
    @DisplayName("the orphan predicate: a stranded word or a forced remainder is found; a long token, hand-wrapped"
            + " prose, a paragraph break and a list are not")
    void predicate() {
        assertThat(javaOrphans(List.of("    /**",
                "     * Returns the thing, or the other thing when there is none, and"
                        + " this sentence keeps going until it",
                "     * returns", "     * null when absent.", "     */"))).containsExactly(2);
        // A long single token: a reflow could not have joined it.
        assertThat(javaOrphans(List.of("     * see", "     * " + "x".repeat(115)))).isEmpty();
        // A paragraph break, a tag, a list, the end of the comment.
        assertThat(javaOrphans(List.of("     * Thread-safe.", "     * <p>"))).isEmpty();
        assertThat(javaOrphans(List.of("     * the end", "     * @param x the x"))).isEmpty();
        assertThat(javaOrphans(List.of("     * <li>one", "     * <li>two"))).isEmpty();
        assertThat(javaOrphans(List.of("     * done.", "     */"))).isEmpty();
        // Inside <pre> nothing is prose.
        assertThat(javaOrphans(List.of("     * <pre>", "     * a b", "     * more code", "     * </pre>"))).isEmpty();

        // Form B: the medium-length remainder of a line broken by force, which the next line could have filled — the
        // ToolCallDispatcher shape.
        final String forced = " * The dispatcher runs one call through the same pipeline the loop uses; what it may"
                + " call is fixed by the binding and";
        assertThat(forced.length()).isEqualTo(117);
        assertThat(javaOrphans(
                List.of(forced, " * what varies per call is an", " * argument, which the caller passes in.")))
                .containsExactly(1);
        // A hand-wrapped paragraph at about 110 columns: the line before was not broken by force.
        final String hand = " * This paragraph was wrapped by its author, a little short of the limit, which is how"
                + " people write their prose";
        assertThat(hand.length()).isBetween(100, 112);
        assertThat(javaOrphans(List.of(hand, " * by hand and nothing a formatter did to",
                " * it, so nothing is stranded here at all."))).isEmpty();
        // A {@link ...} unit that does not fit after the line is not joinable, although its first token would be.
        final String sixtyNine = " * " + "word ".repeat(13) + "x";
        assertThat(javaOrphans(List.of(forced, sixtyNine + " y z",
                " * {@link at.aimon.core.agent.tool.ToolCallDispatcher#dispatch(ToolUse, ToolCallOrigin, ToolContext)}"
                        + " rest")))
                .isEmpty();
        // A forced break followed by a full line: the full line cannot take the next unit.
        final String full = " * " + "y".repeat(10) + " " + "word ".repeat(19) + "end";
        assertThat(full.length()).isGreaterThan(110);
        assertThat(javaOrphans(List.of(forced, full, " * nextword continues."))).isEmpty();
        assertThat(unit("({@link Foo#bar(int, long)}), then")).isEqualTo("({@link Foo#bar(int, long)}),");
        assertThat(unit("plain words")).isEqualTo("plain");

        final String wide = "가나다 ".repeat(15) + "and a few more words";
        assertThat(markdownOrphans(List.of(wide, "모델", "코드는 그 뒤에 이어진다."))).containsExactly(1);
        assertThat(markdownOrphans(List.of("short line", "모델", "코드는 그 뒤에 이어진다."))).isEmpty();
        assertThat(markdownOrphans(List.of(wide, "아니다 — §6)을 끝낸다. 모델", "코드는 그 뒤에 이어진다."))).containsExactly(1);
        assertThat(markdownOrphans(List.of(wide, "- item", "next"))).isEmpty();
        assertThat(markdownOrphans(List.of(wide, wide, wide))).isEmpty();
        assertThat(markdownOrphans(List.of("```", wide, "a b", "c", "```"))).isEmpty();
    }

    /** The indexes of orphan lines in a Java source (forms A and B, class documentation). */
    static List<Integer> javaOrphans(List<String> lines) {
        final List<Integer> orphans = new ArrayList<>();
        boolean pre = false;
        // The text of line i - 1 when it is a prose line of the same comment, else null.
        String previous = null;
        for (int i = 0; i < lines.size(); i++) {
            final Matcher line = CONTINUATION.matcher(lines.get(i));
            if (!line.matches()) {
                previous = null;
                continue;
            }
            final String text = line.group(1).strip();
            if (text.contains("<pre>")) {
                pre = true;
            }
            if (text.contains("</pre>")) {
                pre = false;
                previous = null;
                continue;
            }
            final String before = previous;
            previous = pre || text.isEmpty() ? null : text;
            if (pre || text.isEmpty() || text.startsWith("{@code") || text.startsWith("-") || text.startsWith("|")
                    || text.startsWith("<") || text.startsWith("@") || i + 1 >= lines.size()) {
                continue;
            }
            final Matcher next = CONTINUATION.matcher(lines.get(i + 1));
            if (!next.matches()) {
                continue;
            }
            final String nextText = next.group(1).strip();
            if (nextText.isEmpty() || BLOCK_START.matcher(nextText).find() || nextText.startsWith("*/")) {
                continue;
            }
            if (lines.get(i).length() + 1 + unit(nextText).length() > LINE_LENGTH) {
                continue;
            }
            final boolean formA = tokens(text).length <= 2;
            final boolean formB = lines.get(i).length() <= FORM_B_MAX_LINE && before != null
                    && !BLOCK_START.matcher(text).find()
                    && lines.get(i - 1).length() + 1 + unit(text).length() > LINE_LENGTH;
            if (formA || formB) {
                orphans.add(i);
            }
        }
        return orphans;
    }

    /**
     * The first unbreakable unit of a comment line's text: an inline tag ({@code {@link Foo}}, {@code {@code a b}}),
     * with any punctuation in front of it ({@code (}) and the non-space characters after it glued on, or else the first
     * whitespace token. Splitting {@code {@link Foo}} at its space would report lines a reflow could not have joined.
     */
    static String unit(String text) {
        final String stripped = text.strip();
        int start = 0;
        while (start < stripped.length() && "([\"'".indexOf(stripped.charAt(start)) >= 0) {
            start++;
        }
        if (!stripped.startsWith("{@", start)) {
            return tokens(stripped)[0];
        }
        int depth = 0;
        int end = start;
        for (; end < stripped.length(); end++) {
            final char c = stripped.charAt(end);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                break;
            }
        }
        if (end >= stripped.length()) {
            return stripped;
        }
        end++;
        while (end < stripped.length() && !Character.isWhitespace(stripped.charAt(end))) {
            end++;
        }
        return stripped.substring(0, end);
    }

    /** The indexes of orphan prose lines in a Markdown document. */
    static List<Integer> markdownOrphans(List<String> lines) {
        final List<Integer> orphans = new ArrayList<>();
        final boolean[] fenced = new boolean[lines.size()];
        boolean inFence = false;
        for (int i = 0; i < lines.size(); i++) {
            final boolean fence = lines.get(i).strip().startsWith("```");
            fenced[i] = inFence || fence;
            if (fence) {
                inFence = !inFence;
            }
        }
        for (int i = 1; i + 1 < lines.size(); i++) {
            final String line = lines.get(i).strip();
            final boolean stranded = tokens(line).length <= 2 || displayWidth(lines.get(i)) <= MARKDOWN_SHORT_LINE;
            if (fenced[i] || line.isEmpty() || !stranded || !prose(lines.get(i))) {
                continue;
            }
            if (fenced[i - 1] || fenced[i + 1] || !prose(lines.get(i - 1)) || !prose(lines.get(i + 1))) {
                continue;
            }
            if (displayWidth(lines.get(i - 1)) >= MARKDOWN_WIDE_LINE) {
                orphans.add(i);
            }
        }
        return orphans;
    }

    private static boolean prose(String line) {
        return !line.isBlank() && !MARKDOWN_NOT_PROSE.matcher(line).find() && !line.strip().equals("---");
    }

    private static int displayWidth(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            width += c >= 'ᄀ' && (c <= 'ᅟ' || (c >= '⺀' && c <= '꓏') || (c >= '가' && c <= '힣') || (c >= '豈' && c <= '﫿')
                    || (c >= '＀' && c <= '｠')) ? 2 : 1;
        }
        return width;
    }

    private static String[] tokens(String text) {
        return text.strip().split("\\s+");
    }

    private static void report(List<String> orphans, Path file, int index, String line) {
        final String relative = REPOSITORY_ROOT.relativize(file).toString();
        if (!ALLOWED.containsKey(relative + ":" + line.strip())) {
            orphans.add(relative + ":" + (index + 1) + ": " + line.strip());
        }
    }

    /**
     * Walks up from the working directory to the directory holding both {@code settings.gradle.kts} and
     * {@code modules/}, or returns {@code null} so the test skips itself when run from elsewhere.
     */
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
