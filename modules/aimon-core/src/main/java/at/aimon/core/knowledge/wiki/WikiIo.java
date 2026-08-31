package at.aimon.core.knowledge.wiki;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Shared I/O and parsing helpers for wiki storage and search strategies.
 *
 * <p>
 * Package-private utility class. Not part of the public API — consumers outside this package must use
 * {@link WikiKnowledgeBase} or the strategy interfaces.
 */
final class WikiIo {

    private static final Logger log = LoggerFactory.getLogger(WikiIo.class);

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    private static final Pattern FRONTMATTER_TAGS_PATTERN = Pattern.compile("^tags:\\s*\\[([^\\]]+)\\]",
            Pattern.MULTILINE | Pattern.UNIX_LINES);
    /**
     * Matches {@code type: <token>} lines in frontmatter. Only the first whitespace-delimited token after the colon is
     * captured so that trailing comments or stray whitespace don't break parsing. Type matching itself (including the
     * unknown-token fallback) is delegated to {@link WikiPageType#fromToken(String)}.
     */
    private static final Pattern FRONTMATTER_TYPE_PATTERN = Pattern.compile("^type:\\s*(\\S+)",
            Pattern.MULTILINE | Pattern.UNIX_LINES);
    /**
     * Matches {@code derived_from: [a.md, b.md]} inline list syntax. Mirrors the shape of
     * {@link #FRONTMATTER_TAGS_PATTERN}. Block-style YAML lists are intentionally unsupported — the existing tag
     * parser imposes the same constraint, and keeping a single format avoids a full YAML dependency.
     */
    private static final Pattern FRONTMATTER_DERIVED_FROM_PATTERN = Pattern.compile("^derived_from:\\s*\\[([^\\]]+)\\]",
            Pattern.MULTILINE | Pattern.UNIX_LINES);

    private WikiIo() {
    }

    /**
     * Character class of everything that must be flattened to a space before a value is embedded in a single-line
     * frontmatter field. Two groups:
     * <ul>
     * <li>{@code \p{Cntrl}} — the ASCII control chars {@code [\x00-\x1F\x7F]}, which include CR/LF.
     * <li>{@code U+0085}/{@code U+2028}/{@code U+2029} — Unicode NEXT LINE / LINE SEPARATOR / PARAGRAPH SEPARATOR.
     * These are <em>not</em> in {@code \p{Cntrl}} or {@code \s} (without {@code UNICODE_CHARACTER_CLASS}), yet
     * {@link java.util.regex.Pattern#MULTILINE} honors them as line terminators, so leaving them in would let a
     * crafted value inject a spurious line-leading {@code type:}/{@code tags:}/{@code derived_from:} field.
     * </ul>
     * The frontmatter parse patterns additionally use {@link java.util.regex.Pattern#UNIX_LINES} so only {@code \n}
     * is a boundary at read time — this stripping is the write-side half of that defense-in-depth pair.
     */
    private static final Pattern FRONTMATTER_LINE_BREAK_PATTERN = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    /**
     * Sanitizes an LLM/source-derived value for safe embedding in a single-line YAML frontmatter field (e.g.
     * {@code title:}). Control characters — including CR/LF — and the Unicode line/paragraph separators
     * ({@code U+0085}, {@code U+2028}, {@code U+2029}) are replaced with spaces and collapsed, so injected content can
     * never introduce a spurious line-leading {@code type:} / {@code tags:} field that the MULTILINE, first-match
     * frontmatter regexes ({@link #FRONTMATTER_TYPE_PATTERN}, {@link #FRONTMATTER_TAGS_PATTERN}) would otherwise honor.
     * Frontmatter values are written by hand-rolled concatenation rather than a YAML serializer, so this is the guard
     * that keeps metadata parsing consistent with what was written.
     *
     * @param value
     *            the raw value (may be null)
     * @return a single-line, line-break-free value (never null)
     */
    static String sanitizeFrontmatterText(String value) {
        if (value == null) {
            return "";
        }
        return FRONTMATTER_LINE_BREAK_PATTERN.matcher(value).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Sanitizes a single tag for the inline {@code tags: [a, b]} list. In addition to
     * {@link #sanitizeFrontmatterText}, the structural characters {@code [ ] ,} are stripped so a tag value cannot
     * corrupt the bracketed, comma-separated list or terminate it early.
     *
     * @param tag
     *            the raw tag (may be null)
     * @return a sanitized tag safe for the inline list (never null; may be empty)
     */
    static String sanitizeFrontmatterTag(String tag) {
        return sanitizeFrontmatterText(tag).replaceAll("[\\[\\],]", "").trim();
    }

    static String readContent(VirtualFileSystem vfs, String path) throws IOException {
        try (InputStream is = vfs.read(path);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return String.join("\n", reader.lines().toList());
        }
    }

    static List<String> listPageFiles(VirtualFileSystem fs, String pagesDir) {
        try {
            return fs.listRecursive(pagesDir).stream().filter(p -> p.endsWith(".md")).toList();
        } catch (Exception e) {
            log.warn("Failed to list pages in {}: {}", pagesDir, e.getMessage());
            return Collections.emptyList();
        }
    }

    static WikiPage parseWikiPage(String path, String content) {
        final String title = extractTitle(content, path);
        final List<String> tags = extractTags(content);
        final List<String> linkedPages = extractLinkedPages(content);
        final WikiPageType type = extractType(content, path);
        final List<String> derivedFrom = extractDerivedFrom(content);

        return WikiPage.builder().path(path).title(title).content(content).type(type).tags(tags)
                .linkedPages(linkedPages).derivedFrom(derivedFrom).lastUpdatedAt(null).build();
    }

    /**
     * Extracts the semantic type of a wiki page. First tries the {@code type:} frontmatter field; if absent or
     * unrecognized, falls back to inferring from the file-name prefix (e.g., {@code entity-*.md} ⇒
     * {@link WikiPageType#ENTITY}). The two-stage lookup keeps the type stable across pages written before the
     * frontmatter field existed.
     */
    static WikiPageType extractType(String content, String path) {
        final Matcher m = FRONTMATTER_TYPE_PATTERN.matcher(content);
        if (m.find()) {
            final WikiPageType explicit = WikiPageType.fromToken(m.group(1));
            // fromToken returns DEFAULT on unknown tokens; fall through to file-name inference in that case so a
            // typo in frontmatter does not silently erase a stronger hint from the file name.
            if (explicit != WikiPageType.DEFAULT
                    || WikiPageType.DEFAULT.getToken().equalsIgnoreCase(m.group(1).trim())) {
                return explicit;
            }
        }
        return WikiPageType.fromFileName(extractFileName(path));
    }

    /**
     * Extracts the list of source documents a page was derived from, from the {@code derived_from: [a.md, b.md]}
     * frontmatter field. Returns an empty list when the field is missing — this is also the correct behavior for
     * pages produced before the field existed.
     */
    static List<String> extractDerivedFrom(String content) {
        final Matcher m = FRONTMATTER_DERIVED_FROM_PATTERN.matcher(content);
        if (!m.find()) {
            return Collections.emptyList();
        }
        final String list = m.group(1);
        final List<String> result = new ArrayList<>();
        for (String entry : list.split(",")) {
            final String trimmed = entry.trim().replaceAll("[\"']", "");
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Collections.unmodifiableList(result);
    }

    static String extractTitle(String content, String path) {
        final Matcher m = HEADING_PATTERN.matcher(content);
        if (m.find()) {
            return m.group(1).trim();
        }
        final String fileName = extractFileName(path);
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    static List<String> extractTags(String content) {
        final Matcher m = FRONTMATTER_TAGS_PATTERN.matcher(content);
        if (!m.find()) {
            return Collections.emptyList();
        }
        final String tagList = m.group(1);
        final List<String> tags = new ArrayList<>();
        for (String tag : tagList.split(",")) {
            final String trimmed = tag.trim().replaceAll("[\"']", "");
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return Collections.unmodifiableList(tags);
    }

    static List<String> extractLinkedPages(String content) {
        final Set<String> links = new HashSet<>();

        final Matcher mdMatcher = MARKDOWN_LINK_PATTERN.matcher(content);
        while (mdMatcher.find()) {
            final String href = mdMatcher.group(2).trim();
            if (href.endsWith(".md")) {
                links.add(href);
            }
        }

        final Matcher wikiMatcher = WIKI_LINK_PATTERN.matcher(content);
        while (wikiMatcher.find()) {
            links.add(wikiMatcher.group(1).trim());
        }

        return Collections.unmodifiableList(new ArrayList<>(links));
    }

    static int countOccurrences(String text, String keyword) {
        if (keyword.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }

    static boolean matchesFilePatterns(String path, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        final String fileName = extractFileName(path);
        for (String pattern : patterns) {
            if (matchGlob(pattern, fileName)) {
                return true;
            }
        }
        return false;
    }

    static String extractFileName(String path) {
        final int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * Builds the wiki page file name for a given {@link WikiPageType} and base name. The base name is expected to be
     * the source file name (for summaries) or a pre-built slug (for entities/concepts/etc.) and is preserved as-is
     * except for ensuring the trailing {@code .md} extension.
     *
     * <p>
     * The resulting name has the form {@code <type-prefix>-<base>.md} — e.g., {@code summary-foo.md},
     * {@code entity-kubernetes-pod.md}. This single function keeps the naming convention in one place so that
     * adding a new {@link WikiPageType} does not require hunting through {@link DefaultWikiKnowledgeBase} for
     * hard-coded prefixes.
     */
    static String buildPageFileName(WikiPageType type, String base) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(base, "base must not be null");
        final String normalized = base.endsWith(".md") ? base : base + ".md";
        return type.getPrefix() + "-" + normalized;
    }

    /**
     * Matches a simple glob pattern against a file name. Supports {@code *} (any sequence) and {@code ?} (single
     * character). Avoids regex to prevent ReDoS vulnerabilities.
     */
    static boolean matchGlob(String pattern, String text) {
        int pi = 0;
        int ti = 0;
        int starPi = -1;
        int starTi = -1;

        while (ti < text.length()) {
            if (pi < pattern.length() && (pattern.charAt(pi) == '?' || pattern.charAt(pi) == text.charAt(ti))) {
                pi++;
                ti++;
            } else if (pi < pattern.length() && pattern.charAt(pi) == '*') {
                starPi = pi;
                starTi = ti;
                pi++;
            } else if (starPi >= 0) {
                pi = starPi + 1;
                starTi++;
                ti = starTi;
            } else {
                return false;
            }
        }

        while (pi < pattern.length() && pattern.charAt(pi) == '*') {
            pi++;
        }
        return pi == pattern.length();
    }
}
