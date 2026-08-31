package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tolerant parser for the scope's {@code index.md} file.
 *
 * <p>
 * Extracts entries in the markdown list format described by {@link WikiPageGenerator#generateIndexContent}:
 *
 * <pre>
 * - [Title Here](/wiki/.../pages/foo.md) — one-line summary {tag1, tag2}
 * * [Another Page](/wiki/.../pages/bar.md) {tag1}
 * - [Plain Entry](/wiki/.../pages/baz.md)
 * </pre>
 *
 * <p>
 * The parser is deliberately lenient so it can absorb format drift from different generator implementations:
 * <ul>
 * <li>List markers {@code -} and {@code *} are both accepted.
 * <li>The summary is optional; entries with just a link are parsed.
 * <li>Separators between link and summary may be em-dash ({@code —}), en-dash ({@code –}), hyphen ({@code -}), or
 * colon ({@code :}).
 * <li>Trailing tag list {@code {tag1, tag2}} is optional. Tags are comma-separated and trimmed; quoting is
 * stripped.
 * <li>Lines that don't match are silently ignored — headings, blank lines, prose, and YAML frontmatter are not a
 * problem.
 * </ul>
 *
 * <p>
 * Package-private utility; not part of the public API.
 */
final class IndexParser {

    // Horizontal whitespace only ([ \t]*) — never \s* — because \s matches newlines and would let a single entry's
    // optional-summary capture greedily swallow the next line, reducing the parsed entry count to roughly half.
    //
    // Groups: 1=title, 2=path, 3=summary (optional), 4=tags (optional, comma-separated inside curly braces).
    // The summary is matched non-greedily up to the optional tag group so `{...}` suffixes are never absorbed.
    private static final Pattern ENTRY_PATTERN = Pattern
            .compile(
                    "^[ \\t]*[-*][ \\t]+\\[([^\\]]+)\\]\\(([^)]+)\\)[ \\t]*"
                            + "(?:[\\u2014\\u2013:\\-][ \\t]*([^{]*?))?[ \\t]*" + "(?:\\{([^}]*)\\})?[ \\t]*$",
                    Pattern.MULTILINE);

    private IndexParser() {
    }

    /**
     * Parses the given index markdown content and returns all entries, in document order.
     *
     * @param indexContent
     *            the full content of {@code index.md} (may be null or empty)
     * @return an unmodifiable list of entries; empty if none found
     */
    static List<IndexEntry> parse(String indexContent) {
        if (indexContent == null || indexContent.isEmpty()) {
            return Collections.emptyList();
        }
        final List<IndexEntry> entries = new ArrayList<>();
        final Matcher m = ENTRY_PATTERN.matcher(indexContent);
        while (m.find()) {
            final String title = m.group(1).trim();
            final String path = m.group(2).trim();
            String summary = m.group(3);
            if (summary != null) {
                summary = summary.trim();
                if (summary.isEmpty()) {
                    summary = null;
                }
            }
            final List<String> tags = parseTags(m.group(4));
            if (!title.isEmpty() && !path.isEmpty()) {
                entries.add(new IndexEntry(title, path, summary, tags));
            }
        }
        return Collections.unmodifiableList(entries);
    }

    private static List<String> parseTags(String rawTagGroup) {
        if (rawTagGroup == null) {
            return Collections.emptyList();
        }
        final String trimmed = rawTagGroup.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> tags = new ArrayList<>();
        for (String tag : trimmed.split(",")) {
            final String t = tag.trim().replaceAll("[\"'#]", "");
            if (!t.isEmpty()) {
                tags.add(t);
            }
        }
        return Collections.unmodifiableList(tags);
    }

    /**
     * A single entry parsed from {@code index.md}: a title, its page path, an optional one-line summary, and the
     * page's tags (empty list when none).
     */
    static final class IndexEntry {

        private final String title;
        private final String path;
        private final String summary;
        private final List<String> tags;

        IndexEntry(String title, String path, String summary, List<String> tags) {
            this.title = Objects.requireNonNull(title, "title must not be null");
            this.path = Objects.requireNonNull(path, "path must not be null");
            this.summary = summary;
            this.tags = Collections.unmodifiableList(Objects.requireNonNull(tags, "tags must not be null"));
        }

        String getTitle() {
            return title;
        }

        String getPath() {
            return path;
        }

        String getSummary() {
            return summary;
        }

        List<String> getTags() {
            return tags;
        }
    }
}
