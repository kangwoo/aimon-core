package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.base.text.CodeFences;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;

/**
 * LLM-powered implementation of {@link WikiPageGenerator}.
 *
 * <p>
 * Uses an {@link LlmClient} to generate structured wiki pages from raw source documents and to produce categorized wiki
 * indexes. Falls back to deterministic template-based content when the LLM call fails or returns blank output.
 *
 * <p>
 * Key safety features:
 * <ul>
 * <li>Source content is truncated to {@link #getMaxSourceContentLength()} characters before being sent to the LLM to
 * prevent context window overflow and excessive token costs.
 * <li>Index prompts are capped at {@link #getMaxIndexPromptLength()} characters.
 * <li>All LLM failures are caught and logged at WARN level; a fallback result is always returned.
 * </ul>
 *
 * <p>
 * Thread-safe and stateless. The provided {@link LlmClient} and {@link LlmModel} are not owned by this instance.
 *
 * <pre>{@code
 * WikiPageGenerator generator = LlmWikiPageGenerator.builder()
 *         .llmClient(llmClient)
 *         .modelConfig(LlmModel.builder().name("gpt-4o-mini").build())
 *         .maxSourceContentLength(32_000)
 *         .build();
 * }</pre>
 *
 * @see WikiPageGenerator
 * @see DefaultWikiKnowledgeBase
 */
public final class LlmWikiPageGenerator implements WikiPageGenerator {

    private static final Logger log = LoggerFactory.getLogger(LlmWikiPageGenerator.class);

    /** Default maximum characters of source content sent to the LLM. */
    public static final int DEFAULT_MAX_SOURCE_CONTENT_LENGTH = 32_000;

    /** Default maximum characters for the index generation prompt. */
    public static final int DEFAULT_MAX_INDEX_PROMPT_LENGTH = 16_000;

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

    /**
     * Single-line markdown list-entry pattern used by {@link #reconcileIndexPaths} to walk the LLM's index output
     * line by line. Group 1 captures the leading marker prefix ({@code "- "} or {@code "* "} with surrounding
     * whitespace) so the rewrite preserves the original indentation, group 2 captures the link title, group 3 the
     * link target path, and group 4 the trailing content (summary text and optional tag list). Lines that do not
     * match — headings, blank lines, prose — are passed through untouched.
     */
    private static final Pattern INDEX_ENTRY_LINE = Pattern
            .compile("^([ \\t]*[-*][ \\t]+)\\[([^\\]]+)\\]\\(([^)]+)\\)(.*)$");

    // @formatter:off
    private static final String DEFAULT_PAGE_SYSTEM_PROMPT = """
            You are a wiki knowledge base maintainer. Your task is to process raw source documents \
            and generate structured wiki pages.

            When given a source document, create a wiki page that:
            1. Summarizes the key information concisely
            2. Extracts important entities, concepts, and insights
            3. Includes YAML frontmatter with title, tags, and source reference
            4. Uses [[page-name]] links to cross-reference related existing wiki pages when applicable
            5. Maintains clear structure with headings and sections

            Output ONLY the wiki page content in this exact format (do NOT wrap in a code block):

            ---
            title: <descriptive title>
            tags: [tag1, tag2, tag3]
            source: <source file path>
            ---

            <wiki page content with ## sub-headings, bullet points, and [[cross-references]]>

            Do NOT include any explanation or commentary outside the wiki page content.""";

    private static final String DEFAULT_INDEX_SYSTEM_PROMPT = """
            You are a wiki index maintainer. Generate a categorized index of wiki pages \
            with one-line descriptions for each page.

            Group pages by topic or category when possible. \
            Include a total page count at the top.

            Output ONLY the index content in markdown format starting with "# Wiki Index".

            STRICT FORMAT for every page entry — parsers depend on this exact shape:

              - [Title](<exact Path value from input>) — one-line summary {tag1, tag2}

            Rules:
              * Use "-" or "*" as the list marker.
              * Use a real em-dash (—), en-dash (–), hyphen (-), or colon (:) between the link and the summary.
              * If the page has tags, append them as {tag1, tag2} at the end of the line (comma-separated, no hashes, \
            enclosed in curly braces). Omit the braces when the page has no tags.
              * One entry per line. Headings (## Entities, ## Concepts, …) and prose are allowed between lists.
              * The link target MUST be byte-identical to the `Path:` value provided in the user message for that \
            page. Do NOT add a leading slash, do NOT remove a leading dot, do NOT add or remove path segments, do \
            NOT rewrite the prefix. Copy the path verbatim from the input. Downstream search uses the path to look \
            the page up in the wiki file system; any modification will cause the entry to be silently dropped.

            Do NOT include any explanation or commentary outside the index content.""";

    /**
     * Multi-page extraction prompt. Asks the LLM to identify the conceptual pages contained in a single
     * source document and emit each as its own JSON object. The strict JSON contract avoids the brittleness of
     * splitting markdown blobs with custom delimiters and lets the storage layer route each generated page to the
     * correct file name without re-parsing the body.
     *
     * <p>
     * The model is told to default conservatively to a single SUMMARY when the source is too small or too generic
     * to warrant per-entity splitting — that keeps token cost predictable for boring inputs while still allowing
     * dense documents to fan out into multiple pages.
     */
    private static final String DEFAULT_EXTRACT_SYSTEM_PROMPT = """
            You are a wiki knowledge base maintainer. Your task is to read a raw source document and decompose it \
            into one or more wiki pages.

            For each page you produce, choose the most appropriate type:
              - "summary": a concise summary of the entire source document (always include exactly one).
              - "entity": a concrete named thing (a product, service, system, person, organization, …).
              - "concept": an abstract idea, pattern, or principle.
              - "comparison": a side-by-side analysis of two or more named subjects.

            Output STRICT JSON only, with no markdown fences and no commentary, in this exact shape:

            {
              "pages": [
                {
                  "type": "summary|entity|concept|comparison",
                  "slug": "kebab-case-identifier",
                  "title": "Human Readable Title",
                  "tags": ["tag1", "tag2"],
                  "strategy": "create|merge",
                  "body": "# Title\\n\\n<markdown body with [[wiki-links]]>"
                }
              ]
            }

            Rules:
              * The "pages" array MUST contain at least one entry. The first entry SHOULD be the summary.
              * "slug" MUST be lowercase, kebab-case, contain only [a-z0-9-], and be stable across runs for the \
            same conceptual subject (so future re-ingests can update it instead of duplicating it).
              * "body" is the markdown content of the page WITHOUT YAML frontmatter — the host writes the \
            frontmatter from the JSON fields. Use [[other-page-slug]] for cross references when applicable.
              * "strategy" describes how the host should reconcile this page with what is already on disk:
                  - "create" (default): leave existing pages alone; only write when no file with this slug exists.
                  - "merge": this page describes the SAME subject as an existing page in the wiki (the slug \
            matches one of the entries in the "Existing wiki pages" list given below). The host will combine the \
            two pages, preserving information from both. Prefer "merge" whenever you would otherwise reuse an \
            existing slug to add new facts.
                If you are unsure, use "create".
              * Be conservative: if the source is too small or too generic, return only one summary page rather \
            than inventing entity/concept pages.
              * Do NOT wrap the JSON in code fences. Do NOT add explanation before or after the JSON object.""";
    // @formatter:on

    private static final String DEFAULT_COMPONENT = "wiki-generator";
    private static final LlmCallMetadata DEFAULT_PAGE_METADATA = LlmCallMetadata.builder().component(DEFAULT_COMPONENT)
            .feature("page-generation").build();
    private static final LlmCallMetadata DEFAULT_INDEX_METADATA = LlmCallMetadata.builder().component(DEFAULT_COMPONENT)
            .feature("index-generation").build();
    private static final LlmCallMetadata DEFAULT_EXTRACT_METADATA = LlmCallMetadata.builder()
            .component(DEFAULT_COMPONENT).feature("page-extraction").build();

    /**
     * Shared, thread-safe Jackson reader for parsing the JSON envelope returned by {@link #extractPages}. Kept as a
     * single static instance because {@link ObjectMapper} is documented as thread-safe after configuration and we
     * never reconfigure it after construction.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Restricts slugs to file-system-safe lowercase kebab-case. Anything outside this character set is rejected
     * during JSON parsing so a malformed LLM response cannot produce paths with traversal sequences or odd
     * characters that would break {@link DefaultWikiKnowledgeBase} on write.
     */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private final LlmClient llmClient;
    private final LlmModel modelConfig;
    private final int maxSourceContentLength;
    private final int maxIndexPromptLength;
    private final String pageSystemPrompt;
    private final String indexSystemPrompt;
    private final String extractSystemPrompt;
    private final LlmCallMetadata pageCallMetadata;
    private final LlmCallMetadata indexCallMetadata;
    private final LlmCallMetadata extractCallMetadata;

    private LlmWikiPageGenerator(Builder builder) {
        this.llmClient = Objects.requireNonNull(builder.llmClient, "llmClient must not be null");
        this.modelConfig = builder.modelConfig != null ? builder.modelConfig : LlmModel.builder().build();
        this.maxSourceContentLength = builder.maxSourceContentLength;
        this.maxIndexPromptLength = builder.maxIndexPromptLength;
        this.pageSystemPrompt = builder.pageSystemPrompt != null
                ? builder.pageSystemPrompt
                : DEFAULT_PAGE_SYSTEM_PROMPT;
        this.indexSystemPrompt = builder.indexSystemPrompt != null
                ? builder.indexSystemPrompt
                : DEFAULT_INDEX_SYSTEM_PROMPT;
        this.extractSystemPrompt = builder.extractSystemPrompt != null
                ? builder.extractSystemPrompt
                : DEFAULT_EXTRACT_SYSTEM_PROMPT;
        this.pageCallMetadata = builder.llmCallMetadata != null
                ? builder.llmCallMetadata.withDefaults(DEFAULT_PAGE_METADATA)
                : DEFAULT_PAGE_METADATA;
        this.indexCallMetadata = builder.llmCallMetadata != null
                ? builder.llmCallMetadata.withDefaults(DEFAULT_INDEX_METADATA)
                : DEFAULT_INDEX_METADATA;
        this.extractCallMetadata = builder.llmCallMetadata != null
                ? builder.llmCallMetadata.withDefaults(DEFAULT_EXTRACT_METADATA)
                : DEFAULT_EXTRACT_METADATA;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the configured maximum source content length.
     *
     * @return the maximum characters of source content sent to the LLM
     */
    public int getMaxSourceContentLength() {
        return maxSourceContentLength;
    }

    /**
     * Returns the configured maximum index prompt length.
     *
     * @return the maximum characters for the index generation prompt
     */
    public int getMaxIndexPromptLength() {
        return maxIndexPromptLength;
    }

    @Override
    public String generatePageContent(WikiScope scope, String sourceFilePath, String sourceContent,
            List<String> existingPageNames) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(sourceFilePath, "sourceFilePath must not be null");
        Objects.requireNonNull(sourceContent, "sourceContent must not be null");
        Objects.requireNonNull(existingPageNames, "existingPageNames must not be null");

        try {
            final String truncatedContent = truncateContent(sourceContent, maxSourceContentLength, sourceFilePath);

            final StringBuilder userMessage = new StringBuilder();
            userMessage.append("Source file: ").append(sourceFilePath).append("\n\n");
            userMessage.append("Content:\n").append(truncatedContent);

            if (!existingPageNames.isEmpty()) {
                userMessage.append("\n\nExisting wiki pages:\n");
                for (final String pageName : existingPageNames) {
                    userMessage.append("- ").append(pageName).append("\n");
                }
            }

            final LlmResponse response = llmClient.sendMessage(pageSystemPrompt,
                    List.of(Message.user(userMessage.toString())), Collections.emptyList(), modelConfig,
                    pageCallMetadata.withTags(scopeTags(scope)));

            if (response.hasTextContent() && !response.getTextContent().isBlank()) {
                log.debug("LLM generated wiki page for source: {}", sourceFilePath);
                return response.getTextContent();
            }

            log.warn("LLM returned empty content for source: {}, using fallback", sourceFilePath);
        } catch (Exception e) {
            log.warn("LLM page generation failed for source: {}, using fallback: {}", sourceFilePath, e.getMessage());
        }

        return buildFallbackPageContent(sourceFilePath, sourceContent);
    }

    @Override
    public String generateIndexContent(WikiScope scope, String scopeLabel, List<PageInfo> pages) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(scopeLabel, "scopeLabel must not be null");
        Objects.requireNonNull(pages, "pages must not be null");

        if (pages.isEmpty()) {
            return "# Wiki Index\n\nNo pages yet.\n";
        }

        try {
            final String userMessage = buildIndexPrompt(scopeLabel, pages);

            final LlmResponse response = llmClient.sendMessage(indexSystemPrompt, List.of(Message.user(userMessage)),
                    Collections.emptyList(), modelConfig, indexCallMetadata.withTags(scopeTags(scope)));

            if (response.hasTextContent() && !response.getTextContent().isBlank()) {
                log.debug("LLM generated index for scope: {}", scopeLabel);
                return reconcileIndexPaths(response.getTextContent(), pages, scopeLabel);
            }

            log.warn("LLM returned empty index content for scope: {}, using fallback", scopeLabel);
        } catch (Exception e) {
            log.warn("LLM index generation failed for scope: {}, using fallback: {}", scopeLabel, e.getMessage());
        }

        return buildFallbackIndex(scopeLabel, pages);
    }

    @Override
    public List<GeneratedPage> extractPages(WikiScope scope, String sourceFilePath, String sourceContent,
            List<String> existingPageNames) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(sourceFilePath, "sourceFilePath must not be null");
        Objects.requireNonNull(sourceContent, "sourceContent must not be null");
        Objects.requireNonNull(existingPageNames, "existingPageNames must not be null");

        try {
            final String truncatedContent = truncateContent(sourceContent, maxSourceContentLength, sourceFilePath);

            final StringBuilder userMessage = new StringBuilder();
            userMessage.append("Source file: ").append(sourceFilePath).append("\n\n");
            userMessage.append("Content:\n").append(truncatedContent);

            if (!existingPageNames.isEmpty()) {
                userMessage.append("\n\nExisting wiki pages (re-use slugs when describing the same subject):\n");
                for (final String pageName : existingPageNames) {
                    userMessage.append("- ").append(pageName).append("\n");
                }
            }

            final LlmResponse response = llmClient.sendMessage(extractSystemPrompt,
                    List.of(Message.user(userMessage.toString())), Collections.emptyList(), modelConfig,
                    extractCallMetadata.withTags(scopeTags(scope)));

            if (response.hasTextContent() && !response.getTextContent().isBlank()) {
                final List<GeneratedPage> parsed = parseExtractResponse(response.getTextContent(), sourceFilePath);
                if (!parsed.isEmpty()) {
                    log.debug("LLM extracted {} pages for source: {}", parsed.size(), sourceFilePath);
                    return parsed;
                }
                log.warn("LLM extraction returned no usable pages for source: {}, using single-page fallback",
                        sourceFilePath);
            } else {
                log.warn("LLM extraction returned empty content for source: {}, using single-page fallback",
                        sourceFilePath);
            }
        } catch (Exception e) {
            log.warn("LLM extraction failed for source: {}, using single-page fallback: {}", sourceFilePath,
                    e.getMessage());
        }

        // Fallback path: defer to the legacy single-page generator (which has its own deterministic fallback) so
        // multi-page extraction never reduces the resilience of the existing single-page flow.
        return WikiPageGenerator.super.extractPages(scope, sourceFilePath, sourceContent, existingPageNames);
    }

    /**
     * Parses the JSON envelope returned by the extraction prompt into {@link GeneratedPage} instances.
     *
     * <p>
     * The parser is intentionally permissive about cosmetic LLM mistakes: leading/trailing whitespace, an
     * accidental Markdown code-fence wrapper, or extra unknown fields all survive. It is strict about the things
     * that affect storage safety: slug shape (validated against {@link #SLUG_PATTERN}), required fields, and
     * non-empty body.
     *
     * <p>
     * Pages that fail individual validation are skipped with a warn log; if no page survives, the empty list
     * returned here causes the caller to fall back to the legacy single-page path.
     */
    static List<GeneratedPage> parseExtractResponse(String rawResponse, String sourceFilePath) {
        final String stripped = CodeFences.strip(rawResponse);
        final JsonNode root;
        try {
            root = JSON.readTree(stripped);
        } catch (Exception e) {
            log.warn("Failed to parse extraction JSON for source {}: {}", sourceFilePath, e.getMessage());
            return Collections.emptyList();
        }

        final JsonNode pagesNode = root.path("pages");
        if (!pagesNode.isArray() || pagesNode.isEmpty()) {
            log.warn("Extraction response for source {} has no 'pages' array", sourceFilePath);
            return Collections.emptyList();
        }

        final List<GeneratedPage> pages = new ArrayList<>();
        for (final JsonNode pageNode : pagesNode) {
            final GeneratedPage page = parseSinglePage(pageNode, sourceFilePath);
            if (page != null) {
                pages.add(page);
            }
        }
        return pages;
    }

    private static GeneratedPage parseSinglePage(JsonNode pageNode, String sourceFilePath) {
        final String typeToken = pageNode.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        final String slug = pageNode.path("slug").asText("").trim().toLowerCase(Locale.ROOT);
        // Sanitize the (potentially untrusted, LLM/source-derived) title so an injected newline + "type:"/"tags:"
        // line cannot spoof the frontmatter that WikiIo re-parses with MULTILINE first-match regexes.
        final String title = WikiIo.sanitizeFrontmatterText(pageNode.path("title").asText("").trim());
        final String body = pageNode.path("body").asText("");

        if (slug.isEmpty() || !SLUG_PATTERN.matcher(slug).matches()) {
            log.warn("Skipping extracted page with invalid slug '{}' from source {}", slug, sourceFilePath);
            return null;
        }
        if (title.isEmpty() || body.isEmpty()) {
            log.warn("Skipping extracted page '{}' with missing title/body from source {}", slug, sourceFilePath);
            return null;
        }

        final WikiPageType type = WikiPageType.fromToken(typeToken);
        // ANSWER pages must not be produced by ingest extraction — they have a separate file-answer code path. If
        // the LLM emits one, downgrade to SUMMARY rather than reject the whole page so we don't lose content.
        final WikiPageType safeType = type == WikiPageType.ANSWER ? WikiPageType.SUMMARY : type;

        final List<String> tags = new ArrayList<>();
        final JsonNode tagsNode = pageNode.path("tags");
        if (tagsNode.isArray()) {
            for (final JsonNode tag : tagsNode) {
                final String t = WikiIo.sanitizeFrontmatterTag(tag.asText("").trim());
                if (!t.isEmpty()) {
                    tags.add(t);
                }
            }
        }

        final String fullContent = buildPageMarkdown(safeType, title, tags, sourceFilePath, body);

        // The "strategy" field is optional in the JSON envelope. We accept "create" and "merge" only — REPLACE is
        // never produced by the LLM (the storage layer escalates to REPLACE itself when overwrite is forced). An
        // unrecognized or missing value falls back to CREATE so a malformed response cannot accidentally clobber a
        // long-lived page.
        final String strategyToken = pageNode.path("strategy").asText("").trim().toLowerCase(Locale.ROOT);
        final GeneratedPage.UpdateStrategy strategy = "merge".equals(strategyToken)
                ? GeneratedPage.UpdateStrategy.MERGE
                : GeneratedPage.UpdateStrategy.CREATE;

        return GeneratedPage.builder().type(safeType).slug(slug).title(title).content(fullContent).tags(tags)
                .derivedFrom(Collections.singletonList(sourceFilePath)).strategy(strategy).build();
    }

    /**
     * Wraps a JSON-extracted body in the canonical wiki frontmatter so the on-disk file uses the same format as
     * pages produced by {@link #generatePageContent}. This is the single place that knows how to render the new
     * {@code type:} and {@code derived_from:} fields.
     */
    private static String buildPageMarkdown(WikiPageType type, String title, List<String> tags, String sourceFilePath,
            String body) {
        final StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(title).append('\n');
        sb.append("type: ").append(type.getToken()).append('\n');
        sb.append("tags: [");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tags.get(i));
        }
        sb.append("]\n");
        sb.append("derived_from: [").append(sourceFilePath).append("]\n");
        sb.append("source: ").append(sourceFilePath).append('\n');
        sb.append("---\n\n");
        sb.append(body);
        if (!body.endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Builds the per-call attribution tags from a {@link WikiScope}. Kept centrally so the tag keys stay consistent
     * across the page and index code paths and remain in sync with {@link LlmRerankSearchStrategy}.
     */
    private static Map<String, String> scopeTags(WikiScope scope) {
        return Map.of(WikiScope.TAG_AGENT, scope.getAgentName(), WikiScope.TAG_CONTEXT, scope.getContextId(),
                WikiScope.TAG_NAME, scope.getWikiName());
    }

    // -------------------------------------------------------------------------
    // Truncation
    // -------------------------------------------------------------------------

    private String truncateContent(String content, int maxLength, String context) {
        if (content.length() <= maxLength) {
            return content;
        }
        log.warn("Truncating source content from {} to {} characters for: {}", content.length(), maxLength, context);
        return content.substring(0, maxLength) + "\n\n[... content truncated at " + maxLength + " characters]";
    }

    // -------------------------------------------------------------------------
    // Index prompt building with size cap
    // -------------------------------------------------------------------------

    private String buildIndexPrompt(String scopeLabel, List<PageInfo> pages) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Wiki scope: ").append(scopeLabel).append("\n\n");
        sb.append("Pages to index:\n");

        for (int i = 0; i < pages.size(); i++) {
            final String entry = buildPageEntry(pages.get(i));
            if (sb.length() + entry.length() > maxIndexPromptLength) {
                sb.append("- ... and ").append(pages.size() - i).append(" more pages (truncated)\n");
                break;
            }
            sb.append(entry);
        }

        return sb.toString();
    }

    private static String buildPageEntry(PageInfo page) {
        final StringBuilder entry = new StringBuilder();
        entry.append("- Path: ").append(page.getPath()).append(" | Title: ").append(page.getTitle());
        if (page.getContentPreview() != null) {
            entry.append(" | Preview: ").append(page.getContentPreview());
        }
        if (!page.getTags().isEmpty()) {
            entry.append(" | Tags: ").append(String.join(", ", page.getTags()));
        }
        entry.append("\n");
        return entry.toString();
    }

    // -------------------------------------------------------------------------
    // Index path reconciliation
    // -------------------------------------------------------------------------

    /**
     * Replays the LLM's index output and rewrites every entry's link target back to the canonical
     * {@link PageInfo#getPath()} value. Without this pass, models routinely emit hallucinated paths (extra
     * leading slash, dropped leading dot, invented prefix segments) that no longer resolve via
     * {@code fs.exists(path)}, causing index-first and rerank search to silently drop the affected pages.
     *
     * <p>
     * Resolution order, per entry:
     * <ol>
     * <li><b>Exact path match</b> — the LLM produced the verbatim {@code Path:} value; pass through.
     * <li><b>Basename match</b> — the LLM rewrote the prefix but kept the file name (the common failure mode).
     * Skipped when two pages share the same basename, since basename collisions make the lookup ambiguous.
     * <li><b>Title match</b> — falls back to the link title (case-insensitive). Skipped when two pages share the
     * same title, again to avoid silently routing to the wrong page.
     * <li><b>Drop</b> — none of the above resolves; remove the entry and emit a warn log so operators can
     * investigate genuinely unresolvable LLM output.
     * </ol>
     *
     * <p>
     * Non-entry lines (headings, prose, blank lines) are passed through unchanged.
     */
    static String reconcileIndexPaths(String llmOutput, List<PageInfo> pages, String scopeLabel) {
        if (llmOutput == null || llmOutput.isEmpty() || pages == null || pages.isEmpty()) {
            return llmOutput;
        }

        final Set<String> validPaths = new HashSet<>();
        final Map<String, String> byBasename = new HashMap<>();
        final Set<String> ambiguousBasenames = new HashSet<>();
        final Map<String, String> byTitle = new HashMap<>();
        final Set<String> ambiguousTitles = new HashSet<>();

        for (final PageInfo p : pages) {
            validPaths.add(p.getPath());
            final String base = basenameOf(p.getPath());
            if (!base.isEmpty()) {
                final String prev = byBasename.put(base, p.getPath());
                if (prev != null && !prev.equals(p.getPath())) {
                    ambiguousBasenames.add(base);
                }
            }
            final String titleKey = p.getTitle().toLowerCase(Locale.ROOT).trim();
            if (!titleKey.isEmpty()) {
                final String prev = byTitle.put(titleKey, p.getPath());
                if (prev != null && !prev.equals(p.getPath())) {
                    ambiguousTitles.add(titleKey);
                }
            }
        }

        // Preserve the original line break layout including a trailing newline by splitting with limit=-1.
        final String[] lines = llmOutput.split("\n", -1);
        final StringBuilder out = new StringBuilder(llmOutput.length());
        int dropped = 0;
        int rewritten = 0;
        boolean first = true;
        for (final String original : lines) {
            String line = original;
            final Matcher m = INDEX_ENTRY_LINE.matcher(line);
            if (m.matches()) {
                final String marker = m.group(1);
                final String title = m.group(2);
                final String path = m.group(3);
                final String trailing = m.group(4);
                final String canonical = resolveCanonicalPath(title, path, validPaths, byBasename, ambiguousBasenames,
                        byTitle, ambiguousTitles);
                if (canonical == null) {
                    log.warn(
                            "Dropping unresolvable wiki index entry for scope '{}': title='{}', path='{}' "
                                    + "(no exact path / basename / title match against {} pages)",
                            scopeLabel, title, path, pages.size());
                    dropped++;
                    continue;
                }
                if (!canonical.equals(path)) {
                    line = marker + "[" + title + "](" + canonical + ")" + trailing;
                    rewritten++;
                }
            }
            if (!first) {
                out.append('\n');
            }
            out.append(line);
            first = false;
        }
        if (rewritten > 0 || dropped > 0) {
            log.info("Reconciled wiki index for scope '{}': rewrote {} entries, dropped {} unresolvable", scopeLabel,
                    rewritten, dropped);
        }
        return out.toString();
    }

    private static String resolveCanonicalPath(String title, String path, Set<String> validPaths,
            Map<String, String> byBasename, Set<String> ambiguousBasenames, Map<String, String> byTitle,
            Set<String> ambiguousTitles) {
        if (validPaths.contains(path)) {
            return path;
        }
        final String base = basenameOf(path);
        if (!base.isEmpty() && !ambiguousBasenames.contains(base)) {
            final String byBase = byBasename.get(base);
            if (byBase != null) {
                return byBase;
            }
        }
        final String titleKey = title == null ? "" : title.toLowerCase(Locale.ROOT).trim();
        if (!titleKey.isEmpty() && !ambiguousTitles.contains(titleKey)) {
            final String byT = byTitle.get(titleKey);
            if (byT != null) {
                return byT;
            }
        }
        return null;
    }

    private static String basenameOf(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        final int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    // -------------------------------------------------------------------------
    // Fallback content generation
    // -------------------------------------------------------------------------

    static String buildFallbackPageContent(String sourceFilePath, String sourceContent) {
        final String title = WikiIo.sanitizeFrontmatterText(deriveTitleFromContent(sourceFilePath, sourceContent));
        final String truncated = sourceContent.length() > DEFAULT_MAX_SOURCE_CONTENT_LENGTH
                ? sourceContent.substring(0, DEFAULT_MAX_SOURCE_CONTENT_LENGTH) + "\n\n[... content truncated]"
                : sourceContent;
        return "---\ntitle: " + title + "\ntags: []\nsource: " + sourceFilePath + "\n---\n\n# " + title + "\n\n"
                + truncated;
    }

    static String buildFallbackIndex(String scopeLabel, List<PageInfo> pages) {
        final StringBuilder sb = new StringBuilder();
        sb.append("# Wiki Index\n\n");
        sb.append("Scope: `").append(scopeLabel).append("`\n\n");
        sb.append("Total pages: ").append(pages.size()).append("\n\n");
        sb.append("## Pages\n\n");
        for (final PageInfo page : pages) {
            sb.append("- [").append(page.getTitle()).append("](").append(page.getPath()).append(')');
            if (page.getContentPreview() != null && !page.getContentPreview().isEmpty()) {
                sb.append(" — ").append(page.getContentPreview());
            }
            if (!page.getTags().isEmpty()) {
                sb.append(" {").append(String.join(", ", page.getTags())).append('}');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String deriveTitleFromContent(String sourceFilePath, String sourceContent) {
        final Matcher m = HEADING_PATTERN.matcher(sourceContent);
        if (m.find()) {
            return m.group(1).trim();
        }
        final String fileName = extractFileName(sourceFilePath);
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extractFileName(String path) {
        final int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {

        private LlmClient llmClient;
        private LlmModel modelConfig;
        private int maxSourceContentLength = DEFAULT_MAX_SOURCE_CONTENT_LENGTH;
        private int maxIndexPromptLength = DEFAULT_MAX_INDEX_PROMPT_LENGTH;
        private String pageSystemPrompt;
        private String indexSystemPrompt;
        private String extractSystemPrompt;
        private LlmCallMetadata llmCallMetadata;

        private Builder() {
        }

        public Builder llmClient(LlmClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        /**
         * Sets the LLM model configuration. If not set, the provider default is used.
         *
         * @param modelConfig
         *            the model configuration (may be null for provider default)
         * @return this builder
         */
        public Builder modelConfig(LlmModel modelConfig) {
            this.modelConfig = modelConfig;
            return this;
        }

        /**
         * Sets the maximum characters of source content sent to the LLM. Content exceeding this limit is truncated with
         * a warning. Default: {@value #DEFAULT_MAX_SOURCE_CONTENT_LENGTH}.
         *
         * @param maxSourceContentLength
         *            the maximum length (must be positive)
         * @return this builder
         */
        public Builder maxSourceContentLength(int maxSourceContentLength) {
            if (maxSourceContentLength <= 0) {
                throw new IllegalArgumentException("maxSourceContentLength must be positive");
            }
            this.maxSourceContentLength = maxSourceContentLength;
            return this;
        }

        /**
         * Sets the maximum characters for the index generation prompt. Page entries exceeding this limit are omitted
         * with a truncation notice. Default: {@value #DEFAULT_MAX_INDEX_PROMPT_LENGTH}.
         *
         * @param maxIndexPromptLength
         *            the maximum length (must be positive)
         * @return this builder
         */
        public Builder maxIndexPromptLength(int maxIndexPromptLength) {
            if (maxIndexPromptLength <= 0) {
                throw new IllegalArgumentException("maxIndexPromptLength must be positive");
            }
            this.maxIndexPromptLength = maxIndexPromptLength;
            return this;
        }

        /**
         * Overrides the default system prompt for page generation. If not set, the built-in default prompt is used.
         *
         * @param pageSystemPrompt
         *            the custom system prompt (may be null for default)
         * @return this builder
         */
        public Builder pageSystemPrompt(String pageSystemPrompt) {
            this.pageSystemPrompt = pageSystemPrompt;
            return this;
        }

        /**
         * Overrides the default system prompt for index generation. If not set, the built-in default prompt is used.
         *
         * @param indexSystemPrompt
         *            the custom system prompt (may be null for default)
         * @return this builder
         */
        public Builder indexSystemPrompt(String indexSystemPrompt) {
            this.indexSystemPrompt = indexSystemPrompt;
            return this;
        }

        /**
         * Overrides the default system prompt used by the multi-page extraction path. If not set, the built-in
         * default prompt — which produces a strict-JSON envelope of {@link GeneratedPage} entries — is used.
         *
         * @param extractSystemPrompt
         *            the custom system prompt (may be null for default)
         * @return this builder
         */
        public Builder extractSystemPrompt(String extractSystemPrompt) {
            this.extractSystemPrompt = extractSystemPrompt;
            return this;
        }

        /**
         * Overrides the LLM call metadata used for usage attribution. Caller-supplied fields win on collision; any
         * unset fields fall back to the defaults ({@code component=wiki-generator},
         * {@code feature=page-generation|index-generation}).
         *
         * @param llmCallMetadata
         *            the metadata (may be null to use defaults)
         * @return this builder
         */
        public Builder llmCallMetadata(LlmCallMetadata llmCallMetadata) {
            this.llmCallMetadata = llmCallMetadata;
            return this;
        }

        public LlmWikiPageGenerator build() {
            return new LlmWikiPageGenerator(this);
        }
    }
}
