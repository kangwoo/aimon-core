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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No comment line in the repository's Java sources, and no prose line in its documentation, is left stranded by a
 * wrap: one or two words alone on a line that the next line continues ({@code docs/design/tool/ptc-features-core.md}
 * A.1, widened to the whole repository by {@code docs/design/tool/ptc-leftovers-core.md} PR 3, backlog TC-21).
 *
 * <h2>Why it recurs</h2>
 *
 * <p>
 * {@code config/eclipse/eclipse-formatter.xml} sets {@code comment.line_length=120} and
 * {@code join_lines_in_comments=false}: a comment line written slightly over 120 columns is broken, and the formatter
 * never joins lines back, so the last word or two end up on a line of their own ({@code returns} / {@code null}).
 * Turning the join on would reflow every comment in the repository whenever any file is formatted; this test keeps the
 * result of one mechanical reflow instead.
 *
 * <h2>Scope</h2>
 *
 * <p>
 * Every {@code modules/*}{@code /src/main/java} and {@code modules/*}{@code /src/test/java} tree: the artefact comes
 * from the formatter, which runs on all of them, so no module is less exposed than another. There are no generated
 * sources under {@code src/} (annotation-processor output goes to {@code build/generated}), so nothing is excluded.
 * For Markdown, every {@code docs/**}{@code /*.md} file, translations included, and the repository-root documents
 * ({@code README}, {@code CONTRIBUTING}, {@code SECURITY}, {@code CODE_OF_CONDUCT}, {@code MAINTAINERS},
 * {@code CHANGELOG} and their {@code .ko.md}). Of a {@code CHANGELOG} only the {@code [Unreleased]} section is read:
 * released sections are history and stay as they were written.
 *
 * <h2>The orphan</h2>
 *
 * <p>
 * A javadoc or block-comment continuation line ({@code * text}) is a candidate when it does not start a tag, an HTML
 * element, an inline {@code {@code ...}}, a list dash or a table bar, and the next line continues the same paragraph.
 * It is an orphan when its length plus a space plus the next line's first unbreakable unit ({@link #unit}: an inline
 * tag such as {@code {@link Foo}} with the punctuation glued to it, or else the first token) fits in 120 columns — a
 * reflow would have joined them; that condition is the formatter's signature, and it lets a long single token on its
 * own line pass — and, in addition, it is of form A or form B.
 *
 * <p>
 * <b>Form A</b>: its text has at most two tokens — the last word or two of a line broken by force.
 *
 * <p>
 * <b>Form B</b>: it is at most {@link #FORM_B_MAX_LINE} columns wide, and the line before it is in the same paragraph
 * and was broken by force — that line plus a space plus this line's first unit is over 120. This is the formatter's
 * other artefact: the medium-length remainder of a line written more than a word or two over 120, which the next line
 * could have filled. Hand-wrapped prose, broken by the author at 110–116 columns, fails the forced-break condition.
 *
 * <p>
 * A {@code //} line comment ({@link #lineCommentOrphans}) is held to form A only: a run of {@code //} lines at one
 * indentation whose line of at most two tokens could have taken the next line's first token. Form B is left out there
 * because {@code //} runs are often broken by hand at a sentence — one sentence a line — which form B cannot tell
 * from a forced remainder (a throwaway scan at {@code 994ae5c0} found 27 such lines, most of them laid out on purpose).
 * Three shapes are not prose and pass: a line next to a rule ({@code // -----}) is a section banner; a line that
 * starts with {@code .}, {@code -}, {@code *}, {@code |}, {@code <} or {@code @} is code or a list; and a line that
 * ends a sentence ({@code .}, {@code :}, {@code )}), or is a single capitalised word ({@code // Act}), before a line
 * that starts with a capital, is a label or a sentence of its own.
 *
 * <p>
 * In Markdown, a prose line of at most two tokens, or at most 60 display columns wide, between two prose lines, the
 * previous one at least 100 columns wide (Hangul counted as two), is the same artefact — the second form is
 * {@code programmatic-tool-calling-shell.md}'s stranded {@code 모델}, which ended a short line rather than standing
 * alone — unless one of four signs says the short line was laid out on purpose: the next line starts a new block
 * (a link, {@code **} or {@code `}); the short line ends a sentence ({@code .}, {@code :}, {@code )}) and the next
 * starts a capital or {@code **}; the next line's first token would not have fitted after it; or the next line is
 * over 120 columns because it holds a token over {@link #MARKDOWN_LONG_TOKEN} wide, such as a link kept whole on a
 * line of its own.
 *
 * <h2>Inputs</h2>
 *
 * <p>
 * The other modules' sources and the docs are not inputs of this module's {@code test} task, for the reason
 * {@code ReleaseGateMatchesCiGateTest} gives: declaring them would re-run core's tests after any edit there. After an
 * edit only in those trees the task can be {@code UP-TO-DATE} locally; {@code --rerun} or CI, which always runs from
 * clean, catches it.
 */
class CommentReflowTest {

    private static final int LINE_LENGTH = 120;
    /**
     * The widest line form B flags: at least 20 columns spare. Calibrated with a throwaway scan of every
     * {@code modules/*}{@code /src/*}{@code /java} at {@code 318805f5} (ptc-leftovers-core §1.10): form A alone found
     * 546 lines; form B without this bound and the forced-break condition 2 374, mostly hand-wrapped prose at 110–116
     * columns; with both, 212, which sampled as genuine artefacts.
     */
    private static final int FORM_B_MAX_LINE = 100;
    private static final int MARKDOWN_WIDE_LINE = 100;
    private static final int MARKDOWN_SHORT_LINE = 60;
    /** A Markdown token this wide is a link or a path the author kept whole, not prose. */
    private static final int MARKDOWN_LONG_TOKEN = 60;

    /** The repository-root documents, each scanned with its {@code .ko.md} translation when one exists. */
    private static final List<String> ROOT_DOCUMENTS = List.of("README", "CONTRIBUTING", "SECURITY", "CODE_OF_CONDUCT",
            "MAINTAINERS", "CHANGELOG");

    /**
     * Accepted orphans, keyed by {@code <path>:<trimmed line text>}, each with its reason. The path is relative to the
     * repository root and separated by {@code /} on every platform ({@link #key}). Each entry must still be hit: the
     * scan of its kind ({@code .java} or {@code .md}) fails on one that no longer matches a line, so a fixed or moved
     * line does not leave an exemption behind for whatever comes to match it next.
     */
    private static final Map<String, String> ALLOWED = Map.of("docs/design/README.md:옛 절 번호가 맞지 않는다.",
            "the deleted-records note starts each area (llm, session, tool) on a line of its own",
            "docs/design/README.md:`08c686e8` · `18fe9033` · `dabaafc3` · `2ba9ec61` 이다.",
            "the deleted-records note starts each area (llm, session, tool) on a line of its own",
            "docs/design/observability/subagent-tracing-backlog.md:실행기가 둘 생긴다). 그래서 private 생성자는 둘이다 —",
            "the dash introduces the enumerated (a) / (b) constructors, each starting its own line");

    private static final Pattern CONTINUATION = Pattern.compile("^\\s*\\*\\s(.*)$");
    private static final Pattern LINE_COMMENT = Pattern.compile("^(\\s*)// (.*)$");
    private static final Pattern RULE = Pattern.compile("^[-=*/]{3,}");
    private static final Pattern LABEL = Pattern.compile("^\\p{Lu}\\p{L}*$");
    private static final Pattern BLOCK_START = Pattern
            .compile("^(@|<p>|<li>|</|<pre>|<ul>|<ol>|<h\\d|<table|<blockquote|<dl>|<dt>|<dd>)");
    private static final Pattern MARKDOWN_NOT_PROSE = Pattern.compile("^\\s*([-*+]\\s|\\d+[.)]\\s|\\||#|>|```|<)");
    private static final Pattern SENTENCE_END = Pattern.compile("[.:)]$");

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    @DisplayName("no Java comment line is stranded by a wrap: a word or two, or a forced remainder, a reflow would have"
            + " joined to the next line")
    void noOrphansInJava() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");
        final List<String> orphans = new ArrayList<>();
        final Set<String> allowedHit = new TreeSet<>();
        final List<Path> files = javaSources();
        for (Path file : files) {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i : javaOrphans(lines)) {
                report(orphans, allowedHit, file, i, lines.get(i));
            }
            for (int i : lineCommentOrphans(lines)) {
                report(orphans, allowedHit, file, i, lines.get(i));
            }
        }
        assertThat(files).as("files scanned").hasSizeGreaterThan(1000);
        assertThat(orphans).as("comment lines stranded by a wrap — join each to the line after it and re-wrap, or add"
                + " a reasoned entry to ALLOWED").isEmpty();
        assertThat(allowedHit).as("ALLOWED .java entries still hit")
                .containsExactlyInAnyOrderElementsOf(allowed(".java"));
    }

    @Test
    @DisplayName("the Java scope is every module's main and test sources")
    void javaScopeCoversEveryModule() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");
        final Set<String> modules = new TreeSet<>();
        final Set<String> sourceSets = new TreeSet<>();
        for (Path file : javaSources()) {
            final Path relative = REPOSITORY_ROOT.resolve("modules").relativize(file);
            modules.add(relative.getName(0).toString());
            sourceSets.add(relative.getName(2).toString());
        }
        assertThat(modules).as("modules with Java sources scanned").hasSizeGreaterThanOrEqualTo(15)
                .contains("aimon-core", "aimon-graaljs", "aimon-cli", "aimon-session-routing");
        assertThat(sourceSets).containsExactlyInAnyOrder("main", "test");
    }

    @Test
    @DisplayName("no Markdown document has a prose line of one or two words stranded by a wrap")
    void noOrphansInMarkdown() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");
        final List<String> orphans = new ArrayList<>();
        final Set<String> allowedHit = new TreeSet<>();
        final List<Path> files = markdownDocuments();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (file.getFileName().toString().startsWith("CHANGELOG")) {
                lines = unreleasedOnly(lines);
            }
            for (int i : markdownOrphans(lines)) {
                report(orphans, allowedHit, file, i, lines.get(i));
            }
        }
        assertThat(files).as("documents scanned").hasSizeGreaterThan(100).contains(
                REPOSITORY_ROOT.resolve("CHANGELOG.md"), REPOSITORY_ROOT.resolve("CONTRIBUTING.ko.md"),
                REPOSITORY_ROOT.resolve("MAINTAINERS.md"));
        assertThat(orphans).as("prose lines stranded by a wrap — join each to the line after it and re-wrap, or add a"
                + " reasoned entry to ALLOWED").isEmpty();
        assertThat(allowedHit).as("ALLOWED .md entries still hit — remove an entry whose line is gone or fixed")
                .containsExactlyInAnyOrderElementsOf(allowed(".md"));
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

        // Line comments: form A only — the ExecuteCodePlan shape, a word left alone by the formatter.
        final String lineForced = "            // A fork whose own definition opts in but whose caller allows none of"
                + " it arrives with none(), and so does";
        assertThat(lineCommentOrphans(List.of(lineForced, "            // an",
                "            // execution an inline skill emptied: told why on the span too."))).containsExactly(1);
        // A banner, a label, a sentence end before a capital, commented-out code, another indentation, a long token.
        assertThat(lineCommentOrphans(List.of("    // ------", "    // Builder", "    // ------"))).isEmpty();
        assertThat(lineCommentOrphans(List.of("        // Act", "        // Thread 1: iterate"))).isEmpty();
        assertThat(lineCommentOrphans(List.of("        // the end.", "        // Next sentence."))).isEmpty();
        assertThat(lineCommentOrphans(List.of("        // .isInstanceOf(Foo.class)", "        // .hasMessage(x)")))
                .isEmpty();
        assertThat(lineCommentOrphans(List.of("        // an", "    // other indentation"))).isEmpty();
        assertThat(lineCommentOrphans(List.of("        // see", "        // " + "x".repeat(110)))).isEmpty();
        // Form B is not applied to line comments: a medium remainder of a sentence of its own passes.
        assertThat(lineCommentOrphans(List.of(lineForced, "            // a medium-length remainder of the line.",
                "            // next sentence."))).isEmpty();
        assertThat(unit("plain words")).isEqualTo("plain");
        // The ALLOWED key joins path names with '/', so a Windows path builds the same key.
        assertThat(key(Path.of("docs", "design", "README.md"), "  text  ")).isEqualTo("docs/design/README.md:text");

        final String wide = "가나다 ".repeat(15) + "and a few more words";
        assertThat(markdownOrphans(List.of(wide, "모델", "코드는 그 뒤에 이어진다."))).containsExactly(1);
        assertThat(markdownOrphans(List.of("short line", "모델", "코드는 그 뒤에 이어진다."))).isEmpty();
        assertThat(markdownOrphans(List.of(wide, "아니다 — §6)을 끝낸다. 모델", "코드는 그 뒤에 이어진다."))).containsExactly(1);
        assertThat(markdownOrphans(List.of(wide, "- item", "next"))).isEmpty();
        assertThat(markdownOrphans(List.of(wide, wide, wide))).isEmpty();
        assertThat(markdownOrphans(List.of("```", wide, "a b", "c", "```"))).isEmpty();
        final String english = "When one document carries several spellings and they disagree with each other, the"
                + " strictest one of them wins";
        assertThat(english.length()).isGreaterThanOrEqualTo(MARKDOWN_WIDE_LINE);
        assertThat(markdownOrphans(List.of(english, "the", "verdict, and the rest follows."))).containsExactly(1);
    }

    @Test
    @DisplayName("the Markdown calibration: a new block, a sentence boundary, an unjoinable token and a line laid out"
            + " around a long link end a short line on purpose")
    void markdownCalibration() {
        final String wide = "가나다 ".repeat(15) + "and a few more words";
        // The next line starts a new block: a link, bold text, a code span.
        assertThat(markdownOrphans(List.of(wide, "설계는", "[`design.md`](design.md) 에 있다."))).isEmpty();
        assertThat(markdownOrphans(List.of(wide, "설계는", "**정본** 이다."))).isEmpty();
        assertThat(markdownOrphans(List.of(wide, "테스트는", "`FooTest` 다."))).isEmpty();
        // A sentence ends the short line and the next starts a capital or bold text.
        assertThat(markdownOrphans(List.of(wide, "It ends here.", "Next sentence."))).isEmpty();
        assertThat(markdownOrphans(List.of(wide, "It ends here:", "**Label** follows."))).isEmpty();
        assertThat(markdownOrphans(List.of(wide, "It ends here.", "but this continues it."))).containsExactly(1);
        // The next line's first token would not have fitted after the short line.
        assertThat(markdownOrphans(List.of(wide, "그 경로는", "x".repeat(115) + " 이다."))).isEmpty();
        // The next line is over the limit because of a link kept whole.
        final String link = "[`../design/tool/execution-environment.md`](../design/tool/execution-environment.md)";
        assertThat(markdownOrphans(List.of(wide, "벗어난 점은", "옛 `x.md`(지금은 " + link + ") 에 있었다. 이어서 더"))).isEmpty();
        assertThat(markdownOrphans(List.of(wide, "벗어난 점은", "옛 `x.md` 에 있었다."))).containsExactly(1);

        // Of a CHANGELOG only [Unreleased] is read.
        assertThat(unreleasedOnly(List.of("# Changelog", "## [Unreleased]", "a", "## [0.3.3] - 2026-10-01", "b")))
                .containsExactly("# Changelog", "## [Unreleased]", "a");
        assertThat(unreleasedOnly(List.of("# Changelog", "## [Unreleased]", "a"))).hasSize(3);
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
     * The indexes of orphan {@code //} line-comment lines in a Java source: form A in a run of {@code //} lines at one
     * indentation, minus banners, code, lists, labels and sentence ends (class documentation).
     */
    static List<Integer> lineCommentOrphans(List<String> lines) {
        final List<Integer> orphans = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            final Matcher line = LINE_COMMENT.matcher(lines.get(i));
            final Matcher next = LINE_COMMENT.matcher(lines.get(i + 1));
            if (!line.matches() || !next.matches() || !line.group(1).equals(next.group(1))) {
                continue;
            }
            final String text = line.group(2).strip();
            final String nextText = next.group(2).strip();
            if (text.isEmpty() || nextText.isEmpty() || RULE.matcher(text).find() || RULE.matcher(nextText).find()
                    || ".-*|<@".indexOf(text.charAt(0)) >= 0 || tokens(text).length > 2) {
                continue;
            }
            if (lines.get(i).length() + 1 + tokens(nextText)[0].length() > LINE_LENGTH) {
                continue;
            }
            final boolean capitalNext = Character.isUpperCase(nextText.codePointAt(0));
            if (capitalNext && (SENTENCE_END.matcher(text).find() || LABEL.matcher(text).matches())) {
                continue;
            }
            orphans.add(i);
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
            if (displayWidth(lines.get(i - 1)) >= MARKDOWN_WIDE_LINE
                    && !endsOnPurpose(lines.get(i), lines.get(i + 1))) {
                orphans.add(i);
            }
        }
        return orphans;
    }

    /**
     * Whether a short Markdown line is followed by something that ends it on purpose: a new block, a new sentence, a
     * first token too wide to have joined it, or a line laid out around a token kept whole.
     */
    private static boolean endsOnPurpose(String line, String nextLine) {
        final String text = line.strip();
        final String next = nextLine.strip();
        if (next.startsWith("[") || next.startsWith("**") || next.startsWith("`")) {
            return true;
        }
        if (SENTENCE_END.matcher(text).find()
                && (Character.isUpperCase(next.codePointAt(0)) || next.startsWith("**"))) {
            return true;
        }
        if (displayWidth(line) + 1 + displayWidth(tokens(next)[0]) > LINE_LENGTH) {
            return true;
        }
        if (displayWidth(nextLine) > LINE_LENGTH) {
            for (String token : tokens(next)) {
                if (displayWidth(token) > MARKDOWN_LONG_TOKEN) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The lines of a changelog up to its first released section: released sections are history. */
    static List<String> unreleasedOnly(List<String> lines) {
        int sections = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## [") && ++sections == 2) {
                return lines.subList(0, i);
            }
        }
        return lines;
    }

    private static boolean prose(String line) {
        return !line.isBlank() && !MARKDOWN_NOT_PROSE.matcher(line).find() && !line.strip().equals("---");
    }

    private static int displayWidth(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            width += c >= 'ᄀ' && (c <= 'ᅟ' || (c >= '⺀' && c <= '꓏') || (c >= '가' && c <= '힣') || (c >= '豈' && c <= '﫿')
                    || (c >= '＀' && c <= '｠')) ? 2 : 1;
        }
        return width;
    }

    private static String[] tokens(String text) {
        return text.strip().split("\\s+");
    }

    /** Every {@code .java} file under a module's {@code src/main/java} or {@code src/test/java}. */
    private static List<Path> javaSources() throws IOException {
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> modules = Files.list(REPOSITORY_ROOT.resolve("modules"))) {
            for (Path module : modules.sorted().toList()) {
                for (String sourceSet : List.of("main", "test")) {
                    final Path root = module.resolve("src").resolve(sourceSet).resolve("java");
                    if (Files.isDirectory(root)) {
                        try (Stream<Path> walk = Files.walk(root)) {
                            walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
                        }
                    }
                }
            }
        }
        return files;
    }

    /** Every {@code .md} file under {@code docs/}, plus the root documents and their {@code .ko.md}. */
    private static List<Path> markdownDocuments() throws IOException {
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(REPOSITORY_ROOT.resolve("docs"))) {
            walk.filter(p -> p.toString().endsWith(".md")).sorted().forEach(files::add);
        }
        for (String name : ROOT_DOCUMENTS) {
            for (String suffix : List.of(".md", ".ko.md")) {
                final Path file = REPOSITORY_ROOT.resolve(name + suffix);
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    private static void report(List<String> orphans, Set<String> allowedHit, Path file, int index, String line) {
        final String key = key(REPOSITORY_ROOT.relativize(file), line);
        if (ALLOWED.containsKey(key)) {
            allowedHit.add(key);
        } else {
            orphans.add(key(REPOSITORY_ROOT.relativize(file), "") + (index + 1) + ": " + line.strip());
        }
    }

    /**
     * The {@link #ALLOWED} key of a line: its repository-relative path with {@code /} between the names whatever the
     * platform's separator, a colon, and the trimmed line.
     */
    static String key(Path relative, String line) {
        final List<String> names = new ArrayList<>();
        relative.forEach(name -> names.add(name.toString()));
        return String.join("/", names) + ":" + line.strip();
    }

    /** The {@link #ALLOWED} keys whose path ends in {@code extension}. */
    private static Set<String> allowed(String extension) {
        final Set<String> keys = new TreeSet<>();
        for (String key : ALLOWED.keySet()) {
            if (key.substring(0, key.indexOf(':')).endsWith(extension)) {
                keys.add(key);
            }
        }
        return keys;
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
