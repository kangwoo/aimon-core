package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Generates wiki page content and index content from raw source documents.
 *
 * <p>
 * This interface separates content generation from wiki storage lifecycle, following the Single Responsibility
 * Principle. Implementations may use LLMs, templates, or other strategies to produce wiki content.
 *
 * <p>
 * Implementations must be thread-safe and stateless.
 *
 * @see LlmWikiPageGenerator
 * @see DefaultWikiKnowledgeBase
 */
public interface WikiPageGenerator {

    /**
     * Maximum recommended length for {@link PageInfo#getContentPreview()}. Implementations that build their own
     * previews should truncate to this length so the index and prompts stay compact.
     */
    int MAX_PREVIEW_LENGTH = 100;

    /**
     * Generates wiki page content from a raw source document.
     *
     * <p>
     * The generated content should include YAML frontmatter (title, tags, source) and structured markdown with
     * cross-references to existing wiki pages where applicable.
     *
     * <p>
     * <b>Never-throw contract</b>: implementations <i>should</i> catch their own failures and return a non-empty
     * fallback. However, {@link DefaultWikiKnowledgeBase} defensively wraps any {@link RuntimeException} thrown
     * from here into an {@link java.io.IOException} and rejects null/empty return values as a contract violation
     * — so a misbehaving implementation fails loudly per ingested source instead of silently corrupting the wiki
     * with blank pages. Implementations are still expected to honour the contract; the defensive wrapping is a
     * safety net, not a free pass.
     *
     * @param scope
     *            the wiki scope this page belongs to (must not be null). Implementations may use it for tenancy
     *            isolation, observability/attribution (e.g., LLM usage tags), or logging — but it must not influence
     *            the generated content semantics.
     * @param sourceFilePath
     *            the path of the raw source document (must not be null)
     * @param sourceContent
     *            the content of the raw source document (must not be null)
     * @param existingPageNames
     *            file names (<i>not</i> titles or absolute paths) of existing wiki pages in the same scope, for
     *            cross-referencing. For example {@code ["summary-kubernetes.md", "summary-docker.md"]}.
     *            Implementations that want to emit {@code [[wiki-links]]} should resolve these to human-readable
     *            titles rather than linking the file name verbatim. Must not be null; may be empty.
     * @return the generated wiki page content (never null, never empty)
     */
    String generatePageContent(WikiScope scope, String sourceFilePath, String sourceContent,
            List<String> existingPageNames);

    /**
     * Extracts one or more wiki pages from a single raw source document.
     *
     * <p>
     * This is the multi-page entry point. Where {@link #generatePageContent} returns the body
     * of one summary page, {@code extractPages} can return any combination of {@link WikiPageType#SUMMARY},
     * {@link WikiPageType#ENTITY}, {@link WikiPageType#CONCEPT}, {@link WikiPageType#COMPARISON}, etc. so the LLM
     * can split a source document into the conceptual pages described in {@code docs/references/llm-wiki.md}.
     *
     * <p>
     * <b>Backward compatibility</b>: the default implementation calls {@link #generatePageContent} and wraps the
     * result in a single {@link GeneratedPage} of type {@link WikiPageType#SUMMARY}, with the source file name as the
     * slug and {@link GeneratedPage.UpdateStrategy#REPLACE} (matching the historical {@code overwrite}-controlled
     * behaviour). Existing implementations that only override {@code generatePageContent} therefore continue to work
     * unchanged through {@code DefaultWikiKnowledgeBase}.
     *
     * <p>
     * <b>Never-throw contract</b>: same as {@link #generatePageContent} — implementations should catch their own
     * failures and return at least one page (typically a fallback summary). The storage layer defensively wraps any
     * thrown {@link RuntimeException} into an {@link java.io.IOException} per source file so the rest of the ingest
     * loop can proceed.
     *
     * @param scope
     *            the wiki scope (must not be null)
     * @param sourceFilePath
     *            the path of the raw source document (must not be null)
     * @param sourceContent
     *            the content of the raw source document (must not be null)
     * @param existingPageNames
     *            file names of existing wiki pages in the same scope (must not be null; may be empty)
     * @return one or more generated pages (never null, never empty)
     */
    default List<GeneratedPage> extractPages(WikiScope scope, String sourceFilePath, String sourceContent,
            List<String> existingPageNames) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(sourceFilePath, "sourceFilePath must not be null");
        Objects.requireNonNull(sourceContent, "sourceContent must not be null");
        Objects.requireNonNull(existingPageNames, "existingPageNames must not be null");

        final String content = generatePageContent(scope, sourceFilePath, sourceContent, existingPageNames);
        // Strip directory + .md so the slug is the bare base name; buildPageFileName re-attaches both prefix and
        // extension. Falling back to the source path means a multi-source ingest will not collide on slug.
        final String fileName = sourceFilePath.contains("/")
                ? sourceFilePath.substring(sourceFilePath.lastIndexOf('/') + 1)
                : sourceFilePath;
        final String slug = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        // CREATE strategy preserves the historical behaviour of the single-page ingest path: if the target file
        // already exists, the storage layer skips this source unless IngestOptions.overwrite is set (in which case
        // the storage layer escalates every page to REPLACE globally). Using REPLACE here would silently break
        // overwrite=false because legacy implementations of generatePageContent never expected to be invoked when
        // the target page already existed.
        // Title is best-effort: the legacy generator embeds it in the body, and the storage layer doesn't actually
        // use this builder field for the file name — it only matters for diagnostics and the WikiPage round-trip.
        return Collections.singletonList(GeneratedPage.builder().type(WikiPageType.SUMMARY).slug(slug).title(slug)
                .content(content).derivedFrom(Collections.singletonList(sourceFilePath))
                .strategy(GeneratedPage.UpdateStrategy.CREATE).build());
    }

    /**
     * Generates a wiki index page from a list of page summaries.
     *
     * <p>
     * The generated index should be a categorized, human-readable markdown document listing all pages with
     * descriptions.
     *
     * <p>
     * <b>Format contract</b>: each indexed page MUST appear as a markdown list item matching the following
     * pattern, which is what {@link IndexParser} (and therefore {@link IndexFirstSearchStrategy}) expects:
     *
     * <pre>{@code
     * - [Title](page-path-as-given.md) — optional one-line summary {tag1, tag2}
     * * [Another Page](page-path-as-given.md) {tag1}
     * - [Plain Entry](page-path-as-given.md)
     * }</pre>
     *
     * <p>
     * The link target MUST be byte-identical to the {@link PageInfo#getPath()} value supplied for that page.
     * Search strategies (e.g. {@link IndexFirstSearchStrategy}, {@link LlmRerankSearchStrategy}) resolve the path
     * verbatim against the wiki file system; an entry whose path was rewritten (leading slash added, dot
     * stripped, prefix mangled, …) will silently fail the existence check and be dropped from search results.
     *
     * <p>
     * Rules the parser enforces (all other formatting is free):
     * <ul>
     * <li>List marker is {@code -} or {@code *}.
     * <li>The link uses the standard markdown form {@code [title](path)}.
     * <li>An optional summary may follow the link, preceded by an em-dash ({@code —}), en-dash ({@code –}),
     * hyphen ({@code -}), or colon ({@code :}).
     * <li>An optional trailing tag list in curly braces {@code {tag1, tag2}} (comma-separated). When the page has
     * tags, implementations MUST emit them here so index-first search can filter by tag without reopening pages.
     * </ul>
     *
     * <p>
     * Lines that do not match this pattern are silently ignored by the parser, so implementations are free to add
     * categorizing headings ({@code ## Entities}, {@code ## Concepts}, etc.), prose, and YAML frontmatter around
     * the list items.
     *
     * <p>
     * <b>Never-throw contract</b>: same as {@link #generatePageContent} — implementations <i>should</i> return a
     * non-empty fallback on internal failure, and {@link DefaultWikiKnowledgeBase} defensively wraps violations
     * into {@link java.io.IOException} so index generation failures are logged rather than silently corrupting the
     * wiki.
     *
     * @param scope
     *            the wiki scope this index belongs to (must not be null). Implementations may use it for tenancy
     *            isolation, observability/attribution, or logging — but it must not influence the generated content
     *            semantics.
     * @param scopeLabel
     *            an advisory display label for the wiki scope (e.g., {@code "agent/context/wiki"}). This is a
     *            human-readable hint for the index header, not a trusted identifier — implementations must not
     *            parse it back or rely on its structure.
     * @param pages
     *            summaries of wiki pages to include in the index (must not be null, may be empty)
     * @return the generated index content (never null, never empty)
     */
    String generateIndexContent(WikiScope scope, String scopeLabel, List<PageInfo> pages);

    /**
     * Summary information about a wiki page, used for index generation.
     *
     * <p>
     * Immutable value object. The {@code contentPreview} is optional and may be null; if provided it should be a
     * plain-text preview truncated to {@link #MAX_PREVIEW_LENGTH} characters (typically the first non-heading,
     * non-frontmatter line of the page). Tags are the structured wiki tags extracted from page frontmatter and
     * are the only reliable way for {@link IndexFirstSearchStrategy} to filter candidates by tag without
     * reopening the full page.
     */
    final class PageInfo {

        private final String path;
        private final String title;
        private final String contentPreview;
        private final List<String> tags;

        /**
         * Creates a page info with tags.
         *
         * @param path
         *            the page file path (must not be null)
         * @param title
         *            the page title (must not be null)
         * @param contentPreview
         *            a short plain-text preview, truncated to {@link #MAX_PREVIEW_LENGTH} characters (may be null
         *            if no preview is available)
         * @param tags
         *            tags extracted from the page's YAML frontmatter (must not be null; use an empty list when
         *            the page has no tags)
         */
        public PageInfo(String path, String title, String contentPreview, List<String> tags) {
            this.path = Objects.requireNonNull(path, "path must not be null");
            this.title = Objects.requireNonNull(title, "title must not be null");
            this.contentPreview = contentPreview;
            this.tags = Collections.unmodifiableList(Objects.requireNonNull(tags, "tags must not be null"));
        }

        /**
         * Returns the page file path.
         *
         * @return the path (never null)
         */
        public String getPath() {
            return path;
        }

        /**
         * Returns the page title.
         *
         * @return the title (never null)
         */
        public String getTitle() {
            return title;
        }

        /**
         * Returns a short content preview, or {@code null} if none is available.
         *
         * @return the preview, or null
         */
        public String getContentPreview() {
            return contentPreview;
        }

        /**
         * Returns the page's wiki tags.
         *
         * @return an unmodifiable list of tags (never null; empty means untagged)
         */
        public List<String> getTags() {
            return tags;
        }

        @Override
        public String toString() {
            return "PageInfo{path='" + path + "', title='" + title + "', tags=" + tags + '}';
        }
    }
}
