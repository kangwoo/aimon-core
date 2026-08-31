package at.aimon.core.knowledge.wiki;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * {@link WikiStorageLocator}-backed implementation of {@link WikiKnowledgeBase} and {@link WikiKnowledgeBaseAdmin}.
 *
 * <p>
 * For each scope, the locator resolves both the {@link VirtualFileSystem} and its base directory. Under that base
 * directory, the following layout is maintained:
 * <ul>
 * <li>{@code {scopeDir}/pages/} — wiki page files generated during ingestion
 * <li>{@code {scopeDir}/index.md} — catalog of all wiki pages in the scope
 * <li>{@code {scopeDir}/log.md} — append-only change log for the scope
 * </ul>
 *
 * <p>
 * Content generation is delegated to a {@link WikiPageGenerator}. Search is delegated to a {@link WikiSearchStrategy}
 * — the default is {@link IndexFirstSearchStrategy} which implements the index-first drill-down pattern described in
 * {@code docs/references/llm-wiki.md}. Source documents are read from an externally provided {@link WikiSource}. This
 * class does not own the source VFS, the locator (or any VFS it returns), the page generator, or the search strategy —
 * all are managed by the caller.
 *
 * <p>
 * <b>Thread safety (single JVM)</b>: all public methods are safe for concurrent calls within a single JVM
 * instance. Page content and metadata are stored entirely in the VFS, so the only in-memory state is a
 * per-scope {@link java.util.concurrent.locks.ReentrantLock} map used to serialize read-modify-write cycles
 * (ingest, fileAnswer, synthesize, migrateFrontmatter) on the same scope. Cross-scope operations run
 * concurrently because each scope owns a distinct lock.
 *
 * <p>
 * <b>Multi-instance caveat</b>: the per-scope lock map is a process-local
 * {@link java.util.concurrent.ConcurrentHashMap}.
 * In a scale-out deployment where two JVM instances share the same underlying {@link VirtualFileSystem} and
 * both operate on the same scope, this map does NOT coordinate between JVMs — two concurrent ingests from
 * different JVMs on the same scope can produce torn index/log writes. Multi-instance deployments that need
 * true cross-process safety must either (a) route all writes for a given scope to a single JVM instance, or
 * (b) rely on atomic write semantics provided by the underlying VFS backend (e.g., a GridFS or S3
 * conditional-write implementation). This is consistent with the multi-instance design rule: the storage
 * interface is what makes scale-out possible, not this class's in-memory locks.
 *
 * <p>
 * <b>Failure model</b>: per-operation IO/parse failures degrade gracefully (errors are logged and surfaced via the
 * returned result, e.g., {@link IngestResult#getErrors()} or empty search results). However,
 * {@link IllegalStateException} thrown by {@link WikiStorageLocator#fileSystemFor(WikiScope)} — typically meaning the
 * agent runtime for the scope has not been created yet — is <i>not</i> swallowed and propagates to the
 * caller. Lifecycle/configuration errors must be loud so they are not mistaken for "wiki is empty".
 *
 * <pre>{@code
 * WikiPageGenerator generator = LlmWikiPageGenerator.builder().llmClient(llmClient).build();
 * WikiStorageLocator locator = new WikiStorageLocator() {
 *     public VirtualFileSystem fileSystemFor(WikiScope scope) { return myVfs; }
 *     public String directoryFor(WikiScope scope) { return "/wiki/" + scope.getWikiName(); }
 * };
 * DefaultWikiKnowledgeBase wiki = new DefaultWikiKnowledgeBase(locator, generator);
 * WikiScope scope = new WikiScope("ops-agent", "ctx-1", "runbook");
 * IngestResult result = wiki.ingest(scope, source, IngestOptions.defaults());
 * List<WikiPage> pages = wiki.search(scope, WikiSearchQuery.builder().queryText("kubernetes").build());
 * }</pre>
 *
 * @see WikiKnowledgeBase
 * @see WikiKnowledgeBaseAdmin
 * @see WikiStorageLocator
 * @see WikiPageGenerator
 * @see WikiSearchStrategy
 */
public final class DefaultWikiKnowledgeBase implements WikiKnowledgeBase, WikiKnowledgeBaseAdmin {

    private static final Logger log = LoggerFactory.getLogger(DefaultWikiKnowledgeBase.class);

    /**
     * New log entry format, inspired by {@code docs/references/llm-wiki.md} line 49 — a markdown heading so each
     * entry has a consistent, greppable prefix ({@code grep "^## \\[" log.md}). Stored as a single-line heading so
     * the reader does not need a block parser. Groups: timestamp, operation name, page path (may be blank),
     * summary (may be blank).
     */
    private static final Pattern LOG_ENTRY_PATTERN = Pattern.compile("^## \\[(\\S+)\\] (\\S+) \\| ([^|]*)\\| (.*)$",
            Pattern.MULTILINE);

    /**
     * Legacy log entry format kept for backward compatibility with pre-Phase-3 logs. Matches the bullet-list form
     * previously written by this class. New entries are never produced in this format — only read.
     */
    private static final Pattern LEGACY_LOG_ENTRY_PATTERN = Pattern
            .compile("^- (\\S+) \\| (\\S+) \\| ([^|]+) \\| (.*)$", Pattern.MULTILINE);

    private static final int CONTENT_PREVIEW_LENGTH = WikiPageGenerator.MAX_PREVIEW_LENGTH;

    private final WikiStorageLocator locator;
    private final WikiPageGenerator pageGenerator;
    private final WikiSearchStrategy searchStrategy;
    /**
     * Optional page merger. When non-null AND {@link IngestOptions#isEnableMerge()} is true AND the
     * incoming generated page uses {@link GeneratedPage.UpdateStrategy#MERGE}, the merger is consulted to combine
     * the existing page with the new content. When null (the default), MERGE strategies fall through to plain
     * REPLACE — preserving the pre-merge behaviour.
     */
    private final WikiPageMerger pageMerger;
    /**
     * Optional synthesis strategy. When non-null, {@link #synthesize(WikiScope, SynthesizeOptions)}
     * routes through it; when null, synthesis throws {@link UnsupportedOperationException}. This keeps wikis
     * that don't need the second-pass synthesis workflow free of any LLM-strategy dependency.
     */
    private final SynthesisStrategy synthesisStrategy;
    /**
     * Optional answer strategy (Query Improvement 3). When non-null, {@link #answer(WikiScope, AnswerRequest)}
     * routes through it; when null, answer throws {@link UnsupportedOperationException}. This keeps wikis that
     * only need search/ingest free of an LLM dependency for the answer path.
     */
    private final WikiAnswerStrategy answerStrategy;
    /**
     * Optional semantic lint strategy. When non-null, {@link #lint(WikiScope)} augments its structural
     * findings with LLM-driven checks (contradictions, stale claims, missing concepts, data gaps). When null,
     * lint returns only the structural findings. Keeping this optional means callers that don't want the
     * extra LLM cost can simply not wire it.
     */
    private final WikiLintStrategy lintStrategy;
    /**
     * Per-scope write lock shared by operations that read-modify-write scope-level files (log, index). Using a single
     * lock per scope prevents log/index interleaving and races between concurrent ingests on the same scope.
     */
    private final ConcurrentHashMap<WikiScope, Lock> scopeWriteLocks = new ConcurrentHashMap<>();

    /**
     * Creates a new wiki knowledge base with the default {@link IndexFirstSearchStrategy} and no page merger.
     *
     * @param locator
     *            the strategy resolving VFS and directory for each scope (must not be null)
     * @param pageGenerator
     *            the content generator for wiki pages and indexes (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultWikiKnowledgeBase(WikiStorageLocator locator, WikiPageGenerator pageGenerator) {
        this(locator, pageGenerator, new IndexFirstSearchStrategy(), null, null, null, null);
    }

    /**
     * Creates a new wiki knowledge base backed by the given storage locator and search strategy, without a page
     * merger. Equivalent to {@link #DefaultWikiKnowledgeBase(WikiStorageLocator, WikiPageGenerator,
     * WikiSearchStrategy, WikiPageMerger)} with a null merger.
     *
     * @param locator
     *            the strategy resolving VFS and directory for each scope (must not be null)
     * @param pageGenerator
     *            the content generator for wiki pages and indexes (must not be null)
     * @param searchStrategy
     *            the strategy used by {@link #search(WikiScope, WikiSearchQuery)} (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultWikiKnowledgeBase(WikiStorageLocator locator, WikiPageGenerator pageGenerator,
            WikiSearchStrategy searchStrategy) {
        this(locator, pageGenerator, searchStrategy, null, null, null, null);
    }

    /**
     * Creates a new wiki knowledge base backed by the given storage locator, search strategy, and optional page
     * merger. Equivalent to the five-argument constructor with a {@code null} synthesis strategy.
     *
     * @param locator
     *            the strategy resolving VFS and directory for each scope (must not be null)
     * @param pageGenerator
     *            the content generator for wiki pages and indexes (must not be null)
     * @param searchStrategy
     *            the strategy used by {@link #search(WikiScope, WikiSearchQuery)} (must not be null)
     * @param pageMerger
     *            the optional page merger (may be null)
     * @throws NullPointerException
     *             if locator, pageGenerator, or searchStrategy is null
     */
    public DefaultWikiKnowledgeBase(WikiStorageLocator locator, WikiPageGenerator pageGenerator,
            WikiSearchStrategy searchStrategy, WikiPageMerger pageMerger) {
        this(locator, pageGenerator, searchStrategy, pageMerger, null, null, null);
    }

    /**
     * Creates a new wiki knowledge base backed by the given storage locator, search strategy, optional page
     * merger, and optional synthesis strategy.
     *
     * <p>
     * The provided locator, page generator, search strategy, merger, and synthesis strategy are not owned by
     * this instance and will not be closed when {@link #close()} is called. Any {@link VirtualFileSystem}
     * returned by the locator is owned by the locator (or whoever provided it), not by this knowledge base.
     *
     * @param locator
     *            the strategy resolving VFS and directory for each scope (must not be null)
     * @param pageGenerator
     *            the content generator for wiki pages and indexes (must not be null)
     * @param searchStrategy
     *            the strategy used by {@link #search(WikiScope, WikiSearchQuery)} (must not be null)
     * @param pageMerger
     *            the optional page merger (may be null to disable LLM merge entirely — in that case
     *            {@link IngestOptions#isEnableMerge()} has no effect and MERGE strategies fall through to REPLACE)
     * @param synthesisStrategy
     *            the optional synthesis strategy (may be null to disable
     *            {@link #synthesize(WikiScope, SynthesizeOptions)} entirely)
     * @throws NullPointerException
     *             if locator, pageGenerator, or searchStrategy is null
     */
    public DefaultWikiKnowledgeBase(WikiStorageLocator locator, WikiPageGenerator pageGenerator,
            WikiSearchStrategy searchStrategy, WikiPageMerger pageMerger, SynthesisStrategy synthesisStrategy) {
        this(locator, pageGenerator, searchStrategy, pageMerger, synthesisStrategy, null, null);
    }

    /**
     * Six-argument constructor variant kept for source compatibility with callers that wired the answer
     * strategy before the semantic lint strategy was added. Delegates to the seven-argument form with a null
     * lint strategy.
     *
     * @param locator
     *            the strategy resolving VFS and directory for each scope (must not be null)
     * @param pageGenerator
     *            the content generator for wiki pages and indexes (must not be null)
     * @param searchStrategy
     *            the strategy used by {@link #search(WikiScope, WikiSearchQuery)} (must not be null)
     * @param pageMerger
     *            the optional page merger (may be null)
     * @param synthesisStrategy
     *            the optional synthesis strategy (may be null)
     * @param answerStrategy
     *            the optional answer strategy (may be null)
     * @throws NullPointerException
     *             if locator, pageGenerator, or searchStrategy is null
     */
    public DefaultWikiKnowledgeBase(WikiStorageLocator locator, WikiPageGenerator pageGenerator,
            WikiSearchStrategy searchStrategy, WikiPageMerger pageMerger, SynthesisStrategy synthesisStrategy,
            WikiAnswerStrategy answerStrategy) {
        this(locator, pageGenerator, searchStrategy, pageMerger, synthesisStrategy, answerStrategy, null);
    }

    /**
     * Creates a new wiki knowledge base with all optional strategies wired. This is the most flexible
     * constructor — every other constructor on this class delegates here with the strategies it doesn't take
     * set to {@code null}.
     *
     * @param locator
     *            the strategy resolving VFS and directory for each scope (must not be null)
     * @param pageGenerator
     *            the content generator for wiki pages and indexes (must not be null)
     * @param searchStrategy
     *            the strategy used by {@link #search(WikiScope, WikiSearchQuery)} (must not be null)
     * @param pageMerger
     *            the optional page merger (may be null)
     * @param synthesisStrategy
     *            the optional synthesis strategy (may be null)
     * @param answerStrategy
     *            the optional answer strategy used by {@link #answer(WikiScope, AnswerRequest)} (may be null —
     *            in that case the answer API throws {@link UnsupportedOperationException})
     * @param lintStrategy
     *            the optional semantic lint strategy used by {@link #lint(WikiScope)} to augment the
     *            structural findings (may be null — in that case lint returns only structural findings)
     * @throws NullPointerException
     *             if locator, pageGenerator, or searchStrategy is null
     */
    public DefaultWikiKnowledgeBase(WikiStorageLocator locator, WikiPageGenerator pageGenerator,
            WikiSearchStrategy searchStrategy, WikiPageMerger pageMerger, SynthesisStrategy synthesisStrategy,
            WikiAnswerStrategy answerStrategy, WikiLintStrategy lintStrategy) {
        this.locator = Objects.requireNonNull(locator, "locator must not be null");
        this.pageGenerator = Objects.requireNonNull(pageGenerator, "pageGenerator must not be null");
        this.searchStrategy = Objects.requireNonNull(searchStrategy, "searchStrategy must not be null");
        this.pageMerger = pageMerger;
        this.synthesisStrategy = synthesisStrategy;
        this.answerStrategy = answerStrategy;
        this.lintStrategy = lintStrategy;
    }

    /**
     * Returns a new {@link Builder} for fluent construction.
     *
     * <p>
     * Prefer the builder over the telescoping constructors for new call sites — with five optional strategies
     * the constructor arguments are hard to order correctly by sight, and the builder makes the intent
     * self-documenting:
     *
     * <pre>{@code
     * DefaultWikiKnowledgeBase wiki = DefaultWikiKnowledgeBase.builder()
     *         .locator(locator)
     *         .pageGenerator(generator)
     *         .pageMerger(merger)          // optional
     *         .synthesisStrategy(synth)    // optional
     *         .answerStrategy(answerer)    // optional
     *         .lintStrategy(lintStrategy)  // optional
     *         .build();
     * }</pre>
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link DefaultWikiKnowledgeBase}. Required: {@link #locator(WikiStorageLocator)} and
     * {@link #pageGenerator(WikiPageGenerator)}. All other settings are optional; omitted strategies use the
     * same defaults as the corresponding constructor overload:
     * <ul>
     * <li>{@link #searchStrategy(WikiSearchStrategy)} — defaults to a new {@link IndexFirstSearchStrategy}
     * <li>{@link #pageMerger(WikiPageMerger)} — null, disables LLM merge
     * <li>{@link #synthesisStrategy(SynthesisStrategy)} — null, {@link #synthesize} throws
     * <li>{@link #answerStrategy(WikiAnswerStrategy)} — null, {@link #answer} throws
     * <li>{@link #lintStrategy(WikiLintStrategy)} — null, lint returns only structural findings
     * </ul>
     */
    public static final class Builder {

        private WikiStorageLocator locator;
        private WikiPageGenerator pageGenerator;
        private WikiSearchStrategy searchStrategy;
        private WikiPageMerger pageMerger;
        private SynthesisStrategy synthesisStrategy;
        private WikiAnswerStrategy answerStrategy;
        private WikiLintStrategy lintStrategy;

        private Builder() {
        }

        /** Sets the storage locator. Required. */
        public Builder locator(WikiStorageLocator locator) {
            this.locator = locator;
            return this;
        }

        /** Sets the page generator. Required. */
        public Builder pageGenerator(WikiPageGenerator pageGenerator) {
            this.pageGenerator = pageGenerator;
            return this;
        }

        /**
         * Sets the search strategy. Optional — defaults to a new {@link IndexFirstSearchStrategy} when the
         * builder runs {@link #build()} without an explicit search strategy.
         */
        public Builder searchStrategy(WikiSearchStrategy searchStrategy) {
            this.searchStrategy = searchStrategy;
            return this;
        }

        /** Sets the page merger. Optional — null disables LLM merge. */
        public Builder pageMerger(WikiPageMerger pageMerger) {
            this.pageMerger = pageMerger;
            return this;
        }

        /** Sets the synthesis strategy. Optional — null disables {@link #synthesize}. */
        public Builder synthesisStrategy(SynthesisStrategy synthesisStrategy) {
            this.synthesisStrategy = synthesisStrategy;
            return this;
        }

        /** Sets the answer strategy. Optional — null disables {@link #answer}. */
        public Builder answerStrategy(WikiAnswerStrategy answerStrategy) {
            this.answerStrategy = answerStrategy;
            return this;
        }

        /** Sets the semantic lint strategy. Optional — null leaves lint with structural checks only. */
        public Builder lintStrategy(WikiLintStrategy lintStrategy) {
            this.lintStrategy = lintStrategy;
            return this;
        }

        /**
         * Builds the knowledge base. {@link #locator(WikiStorageLocator)} and
         * {@link #pageGenerator(WikiPageGenerator)} must have been set; everything else is optional. Defaults
         * the search strategy to a fresh {@link IndexFirstSearchStrategy} when it was not configured.
         *
         * @return a new {@link DefaultWikiKnowledgeBase}
         * @throws NullPointerException
         *             if locator or pageGenerator is null
         */
        public DefaultWikiKnowledgeBase build() {
            final WikiSearchStrategy resolvedSearchStrategy = searchStrategy != null
                    ? searchStrategy
                    : new IndexFirstSearchStrategy();
            return new DefaultWikiKnowledgeBase(locator, pageGenerator, resolvedSearchStrategy, pageMerger,
                    synthesisStrategy, answerStrategy, lintStrategy);
        }
    }

    @Override
    public IngestResult ingest(WikiScope scope, WikiSource source, IngestOptions options) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(options, "options must not be null");

        // Resolve VFS up front so context-lifecycle errors (IllegalStateException from the locator) propagate to the
        // caller instead of being silently swallowed by the soft-fail catch below. Other failures still degrade
        // gracefully via the inner try blocks.
        final VirtualFileSystem fs = fileSystemFor(scope);

        final long startTime = System.currentTimeMillis();
        final List<String> errors = new ArrayList<>();
        int ingestedCount = 0;
        int skippedCount = 0;
        int createdPageCount = 0;
        int updatedPageCount = 0;
        int mergedPageCount = 0;

        try {
            final VirtualFileSystem sourceVfs = source.getFileSystem();
            final String sourceDir = source.getDirectory();

            final List<String> sourceFiles = listSourceFiles(sourceVfs, sourceDir, options);
            log.debug("Found {} candidate source files in {} for scope {}", sourceFiles.size(), sourceDir, scope);

            // Collect existing page names once to avoid O(n^2) VFS listings
            final List<String> existingPageNames = collectExistingPageNames(fs, scope);

            for (int i = 0; i < sourceFiles.size(); i++) {
                final String sourceFilePath = sourceFiles.get(i);
                if (ingestedCount >= options.getMaxDocuments()) {
                    skippedCount += sourceFiles.size() - i;
                    break;
                }

                try {
                    final String sourceContent = WikiIo.readContent(sourceVfs, sourceFilePath);
                    if (sourceContent.isEmpty()) {
                        log.debug("Skipping empty source file: {}", sourceFilePath);
                        skippedCount++;
                        continue;
                    }

                    // Ask the generator for one or more pages per source. The default-method fallback in
                    // WikiPageGenerator wraps the legacy generatePageContent path so older implementations still
                    // round-trip through here unchanged.
                    final List<GeneratedPage> generated = extractPagesSafely(scope, sourceFilePath, sourceContent,
                            existingPageNames);

                    final SourceWriteOutcome outcome = writeGeneratedPages(fs, scope, sourceFilePath, generated,
                            options, existingPageNames);

                    createdPageCount += outcome.created;
                    updatedPageCount += outcome.updated;
                    mergedPageCount += outcome.merged;
                    if (outcome.created + outcome.updated + outcome.merged > 0) {
                        ingestedCount++;
                        // Append a per-source summary entry so the log carries both the page-level history
                        // (the existing PAGE_CREATED/PAGE_UPDATED entries written inside writeGeneratedPages)
                        // AND a one-line "what came in from this source" view, matching the docs/references/
                        // llm-wiki.md example `## [date] ingest | Article Title`. The page entries and this
                        // summary are complementary: per-page gives you the "which pages did this touch"
                        // question, per-source gives you the "which sources have we processed" question.
                        appendLogEntry(fs, scope, WikiLogEntry.Operation.SOURCE_INGESTED, sourceFilePath,
                                "Ingested " + sourceFilePath + " — created " + outcome.created + ", updated "
                                        + outcome.updated + ", merged " + outcome.merged);
                    } else {
                        // No page was written for this source — typically every generated page hit a CREATE-skip
                        // because the target file already exists. Count the source as skipped so the result
                        // explains why the disk state didn't change.
                        skippedCount++;
                    }

                } catch (Exception e) {
                    log.warn("Failed to ingest source file: {}", sourceFilePath, e);
                    errors.add(sourceFilePath + ": " + e.getMessage());
                    skippedCount++;
                }
            }

            updateIndex(fs, scope);

            // Optionally trigger a synthesis pass at the end of a successful ingest. We swallow
            // synthesis errors here intentionally — auto-synthesize is a best-effort second pass and a
            // failure must not poison the ingest result. Callers that need synthesis details should use the
            // synthesize() API directly.
            if (options.isAutoSynthesize() && synthesisStrategy != null) {
                try {
                    final SynthesizeResult sr = synthesize(scope, SynthesizeOptions.defaults());
                    log.debug("Auto-synthesis for scope {}: {}", scope, sr);
                } catch (Exception e) {
                    log.warn("Auto-synthesis failed for scope {}: {}", scope, e.getMessage());
                }
            } else if (options.isAutoSynthesize() && synthesisStrategy == null) {
                log.debug("autoSynthesize=true but no SynthesisStrategy is wired for scope {}; skipping", scope);
            }

        } catch (Exception e) {
            log.error("Unexpected error during ingest for scope {}: {}", scope, e.getMessage(), e);
            errors.add("Ingest failed: " + e.getMessage());
        }

        final long durationMs = System.currentTimeMillis() - startTime;
        log.debug("Ingest done for {}: ingested={}, skipped={}, created={}, updated={}, merged={}, ms={}", scope,
                ingestedCount, skippedCount, createdPageCount, updatedPageCount, mergedPageCount, durationMs);

        return IngestResult.builder().ingestedCount(ingestedCount).skippedCount(skippedCount)
                .createdPageCount(createdPageCount).updatedPageCount(updatedPageCount).mergedPageCount(mergedPageCount)
                .durationMs(durationMs).errors(errors).build();
    }

    @Override
    public List<WikiPage> search(WikiScope scope, WikiSearchQuery query) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(query, "query must not be null");

        // Resolve VFS up front so context-lifecycle errors propagate to the caller (see ingest()).
        final VirtualFileSystem fs = fileSystemFor(scope);

        final WikiSearchContext ctx = WikiSearchContext.builder().fileSystem(fs).scope(scope)
                .scopeDirectory(scopeDirectory(scope)).pagesDirectory(pagesDirectory(scope)).indexPath(indexPath(scope))
                .build();

        return searchStrategy.search(query, ctx);
    }

    @Override
    public List<WikiSearchResult> searchWithScores(WikiScope scope, WikiSearchQuery query) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(query, "query must not be null");

        // Resolve VFS up front so context-lifecycle errors propagate to the caller (see ingest()).
        final VirtualFileSystem fs = fileSystemFor(scope);

        final WikiSearchContext ctx = WikiSearchContext.builder().fileSystem(fs).scope(scope)
                .scopeDirectory(scopeDirectory(scope)).pagesDirectory(pagesDirectory(scope)).indexPath(indexPath(scope))
                .build();

        return searchStrategy.searchWithScores(query, ctx);
    }

    @Override
    public Optional<WikiPage> getPage(WikiScope scope, String pagePath) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(pagePath, "pagePath must not be null");

        // Path-scope check is cheap and doesn't need the VFS — do it first so we can early-return without forcing a
        // context lookup for clearly out-of-scope inputs. Reject any '..' segment before the prefix test: without it
        // a crafted path such as "<scopeDir>/pages/../../<other-scope>/pages/secret.md" satisfies startsWith(scopeDir)
        // yet resolves into a different scope (the backing VFS only guards its own root, not the per-scope subtree).
        final String scopeDir = scopeDirectory(scope);
        if (hasParentTraversalSegment(pagePath) || !pagePath.startsWith(scopeDir)) {
            log.warn("Page path {} is outside scope {}", pagePath, scope);
            return Optional.empty();
        }

        // Resolve VFS up front so context-lifecycle errors propagate to the caller (see ingest()).
        final VirtualFileSystem fs = fileSystemFor(scope);

        try {
            if (!fs.exists(pagePath)) {
                return Optional.empty();
            }

            final String content = WikiIo.readContent(fs, pagePath);
            return Optional.of(WikiIo.parseWikiPage(pagePath, content));

        } catch (Exception e) {
            log.warn("Failed to get page {} for scope {}: {}", pagePath, scope, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns whether {@code path} contains a {@code ".."} path segment (parent-directory traversal). Wiki page paths
     * are built from validated slugs and never legitimately contain one, so any occurrence indicates a crafted path
     * attempting to escape its scope subtree.
     */
    private static boolean hasParentTraversalSegment(String path) {
        for (final String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public WikiPage fileAnswer(WikiScope scope, FiledAnswer answer) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(answer, "answer must not be null");

        // Resolve VFS up front so context-lifecycle errors propagate to the caller (see ingest()).
        final VirtualFileSystem fs = fileSystemFor(scope);

        // Serialize per-scope so two concurrent fileAnswer() calls can't produce clashing slugs or torn indexes.
        final Lock lock = scopeWriteLocks.computeIfAbsent(scope, k -> new ReentrantLock());
        lock.lock();
        try {
            final String slug = slugify(answer.getTitle());
            String pageFileName = WikiIo.buildPageFileName(WikiPageType.ANSWER, slug);
            String pagePath = pagesDirectory(scope) + pageFileName;

            // Preserve existing filed answers — append a monotonic suffix if the slug collides.
            if (fs.exists(pagePath)) {
                pageFileName = WikiIo.buildPageFileName(WikiPageType.ANSWER, slug + "-" + Instant.now().toEpochMilli());
                pagePath = pagesDirectory(scope) + pageFileName;
            }

            final String pageContent = buildFiledAnswerContent(answer);
            fs.write(pagePath, pageContent);
            log.debug("Filed answer at {}", pagePath);

            // Refresh index so the newly filed answer is findable by the next index-first search.
            // ReentrantLock is reentrant, so updateIndex/appendLogEntry re-acquiring the same lock is safe.
            updateIndex(fs, scope);

            appendLogEntry(fs, scope, WikiLogEntry.Operation.QUERY_FILED, pagePath,
                    "Filed answer: " + answer.getTitle());

            return WikiIo.parseWikiPage(pagePath, pageContent);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Answer answer(WikiScope scope, AnswerRequest request) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (answerStrategy == null) {
            // Surface the configuration mistake loudly — silently returning a deterministic placeholder would
            // mask the fact that the wiki was constructed without an answer strategy.
            throw new UnsupportedOperationException(
                    "answer requires a WikiAnswerStrategy to be wired into DefaultWikiKnowledgeBase");
        }

        // Stage 1: search using either the explicit query or the question-derived default. The search itself
        // already validates the scope, so we don't need to re-resolve the VFS here for any reason other than
        // loading page bodies in stage 2.
        final WikiSearchQuery searchQuery = request.getSearchQuery();
        final List<WikiPage> searchResults = search(scope, searchQuery);

        // Stage 2: cap the supporting pages at maxContextPages so the prompt size stays predictable. The
        // search may have already capped at maxResults, but we re-apply here so callers that pass a custom
        // searchQuery with a higher maxResults still see a bounded prompt.
        final List<WikiPage> contextPages = searchResults.size() <= request.getMaxContextPages()
                ? searchResults
                : searchResults.subList(0, request.getMaxContextPages());

        // Stage 3: hand off to the strategy. The strategy is responsible for its own LLM calls, error
        // handling, and fallback content. We wrap any contract violation as an unchecked exception so a
        // misbehaving strategy fails loudly.
        //
        // The null check lives OUTSIDE the try/catch on purpose: otherwise the IllegalStateException we
        // throw on a null result would be caught by the RuntimeException handler below and re-wrapped in a
        // second "WikiAnswerStrategy threw an exception" IllegalStateException. The catch must only capture
        // exceptions thrown BY the strategy, not exceptions we just raised about the strategy's output.
        final Answer result;
        try {
            result = answerStrategy.answer(scope, request, contextPages);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "WikiAnswerStrategy threw an exception (contract violation): " + e.getMessage(), e);
        }
        if (result == null) {
            throw new IllegalStateException(
                    "WikiAnswerStrategy returned null (contract violation): " + answerStrategy.getClass().getName());
        }
        return result;
    }

    @Override
    public SynthesizeResult synthesize(WikiScope scope, SynthesizeOptions options) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(options, "options must not be null");

        if (synthesisStrategy == null) {
            // Surface the configuration mistake loudly — silently returning an empty result would mask the
            // fact that the wiki was constructed without a strategy.
            throw new UnsupportedOperationException(
                    "synthesize requires a SynthesisStrategy to be wired into DefaultWikiKnowledgeBase");
        }

        // Resolve VFS up front so context-lifecycle errors propagate (see ingest()).
        final VirtualFileSystem fs = fileSystemFor(scope);

        final long startTime = System.currentTimeMillis();
        final List<String> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int skipped = 0;

        // Hold the per-scope write lock for the whole synthesis pass so the read-modify-write cycle (load
        // existing pages → call LLM → write new pages) is atomic against concurrent ingests on the same scope.
        final Lock lock = scopeWriteLocks.computeIfAbsent(scope, k -> new ReentrantLock());
        lock.lock();
        try {
            final List<WikiPage> sourcePages = loadSynthesisSourcePages(fs, scope);
            log.debug("Synthesis input for scope {}: {} source pages (after filtering OVERVIEW/SYNTHESIS)", scope,
                    sourcePages.size());

            final List<GeneratedPage> generated;
            try {
                generated = synthesisStrategy.synthesize(scope, sourcePages, options);
            } catch (RuntimeException e) {
                log.warn("SynthesisStrategy threw for scope {}: {}", scope, e.getMessage());
                errors.add("SynthesisStrategy failed: " + e.getMessage());
                return SynthesizeResult.builder().createdPageCount(0).updatedPageCount(0).skippedCount(0)
                        .llmCallCount(synthesisStrategy.getLastCallCount())
                        .durationMs(System.currentTimeMillis() - startTime).errors(errors).build();
            }

            for (GeneratedPage page : generated) {
                final String pageFileName = WikiIo.buildPageFileName(page.getType(), page.getSlug());
                final String pagePath = pagesDirectory(scope) + pageFileName;
                final boolean pageExists = fs.exists(pagePath);

                if (pageExists && !options.isOverwrite()) {
                    log.debug("Skipping existing synthesized page (overwrite=false): {}", pagePath);
                    skipped++;
                    continue;
                }

                try {
                    fs.write(pagePath, page.getContent());
                    if (pageExists) {
                        updated++;
                        appendLogEntry(fs, scope, WikiLogEntry.Operation.PAGE_UPDATED, pagePath,
                                "Synthesized " + page.getType().getToken() + " page");
                    } else {
                        created++;
                        appendLogEntry(fs, scope, WikiLogEntry.Operation.PAGE_CREATED, pagePath,
                                "Synthesized " + page.getType().getToken() + " page");
                    }
                } catch (Exception e) {
                    log.warn("Failed to write synthesized page {}: {}", pagePath, e.getMessage());
                    errors.add(pagePath + ": " + e.getMessage());
                }
            }

            // Refresh the index so the newly synthesized overview/synthesis pages are findable.
            updateIndex(fs, scope);

            final long durationMs = System.currentTimeMillis() - startTime;
            log.debug("Synthesis done for {}: created={}, updated={}, skipped={}, llmCalls={}, ms={}", scope, created,
                    updated, skipped, synthesisStrategy.getLastCallCount(), durationMs);

            return SynthesizeResult.builder().createdPageCount(created).updatedPageCount(updated).skippedCount(skipped)
                    .llmCallCount(synthesisStrategy.getLastCallCount()).durationMs(durationMs).errors(errors).build();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Loads the wiki pages eligible as synthesis input. Filters out existing OVERVIEW and SYNTHESIS pages so
     * the strategy doesn't recursively synthesize its own outputs, and silently drops pages that fail to parse.
     */
    private List<WikiPage> loadSynthesisSourcePages(VirtualFileSystem fs, WikiScope scope) {
        final String pagesDir = pagesDirectory(scope);
        if (!fs.exists(pagesDir)) {
            return Collections.emptyList();
        }
        final List<WikiPage> result = new ArrayList<>();
        for (String pagePath : WikiIo.listPageFiles(fs, pagesDir)) {
            try {
                final String content = WikiIo.readContent(fs, pagePath);
                final WikiPage page = WikiIo.parseWikiPage(pagePath, content);
                if (page.getType() == WikiPageType.OVERVIEW || page.getType() == WikiPageType.SYNTHESIS) {
                    continue;
                }
                result.add(page);
            } catch (Exception e) {
                log.debug("Skipping unparseable page {}: {}", pagePath, e.getMessage());
            }
        }
        return result;
    }

    @Override
    public WikiStatus getStatus(WikiScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");

        // Resolve VFS up front so context-lifecycle errors propagate to the caller (see ingest()).
        final VirtualFileSystem fs = fileSystemFor(scope);

        try {
            final String scopeDir = scopeDirectory(scope);
            if (!fs.exists(scopeDir)) {
                return WikiStatus.builder().state(WikiStatus.State.EMPTY).wikiDirectory(scopeDir).build();
            }

            final String pagesDir = pagesDirectory(scope);
            int pageCount = 0;
            if (fs.exists(pagesDir)) {
                pageCount = WikiIo.listPageFiles(fs, pagesDir).size();
            }

            final int sourceCount = countLogEntriesOfOperation(fs, scope, WikiLogEntry.Operation.PAGE_CREATED)
                    + countLogEntriesOfOperation(fs, scope, WikiLogEntry.Operation.PAGE_UPDATED);

            final WikiStatus.State state = pageCount > 0 ? WikiStatus.State.READY : WikiStatus.State.EMPTY;

            return WikiStatus.builder().state(state).pageCount(pageCount).sourceCount(sourceCount)
                    .wikiDirectory(scopeDir).build();

        } catch (Exception e) {
            log.error("Unexpected error getting status for scope {}: {}", scope, e.getMessage(), e);
            return WikiStatus.builder().state(WikiStatus.State.ERROR).build();
        }
    }

    @Override
    public LintReport lint(WikiScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");

        // Resolve VFS up front so context-lifecycle errors propagate to the caller (see ingest()).
        final VirtualFileSystem fs = fileSystemFor(scope);

        final Instant checkedAt = Instant.now();
        final List<LintReport.Issue> issues = new ArrayList<>();
        int checkedPageCount = 0;

        // Lint is intentionally read-only and does NOT hold the per-scope write lock. Structural findings
        // (broken links, orphans, duplicate titles) are best-effort under concurrency: a concurrent ingest
        // may add pages to the directory after we snapshot `pageFiles`, which can manifest as a broken link
        // or orphan finding that disappears on the next run. This is the accepted tradeoff — holding the
        // write lock for a full lint pass would block ingest on large wikis, and lint results are advisory
        // anyway (INFO/WARNING, not ERROR).
        try {
            final String pagesDir = pagesDirectory(scope);
            if (!fs.exists(pagesDir)) {
                return LintReport.builder().issues(issues).checkedPageCount(0).checkedAt(checkedAt).build();
            }

            final List<String> pageFiles = WikiIo.listPageFiles(fs, pagesDir);
            final Set<String> allPagePaths = new HashSet<>(pageFiles);
            final Map<String, Set<String>> inboundLinks = new HashMap<>();
            for (String path : pageFiles) {
                inboundLinks.put(path, new HashSet<>());
            }
            // Build a set of valid wiki-link slugs so we can validate [[slug]] cross-references the
            // same way absolute markdown links are validated. The slug for "/wiki/.../entity-pod.md" is "pod" —
            // i.e., the on-disk file name without the type prefix and the .md extension.
            final Set<String> allSlugs = new HashSet<>();
            for (String path : pageFiles) {
                allSlugs.add(slugFromPagePath(path));
            }
            // Track normalized titles → page paths so we can flag duplicate concept pages (the same
            // human-readable title showing up under multiple slugs is a strong sign of an upcoming merge).
            final Map<String, List<String>> titleToPages = new HashMap<>();
            // Gap #2: collect parsed pages in-loop so an optional semantic-lint strategy can consume them at
            // the end of the pass without a second round of I/O.
            final List<WikiPage> parsedPages = new ArrayList<>();

            for (String pageFilePath : pageFiles) {
                checkedPageCount++;
                try {
                    final String content = WikiIo.readContent(fs, pageFilePath);

                    if (content.isEmpty()) {
                        issues.add(new LintReport.Issue(LintReport.Severity.WARNING, pageFilePath, "Page is empty"));
                        continue;
                    }

                    final WikiPage page = WikiIo.parseWikiPage(pageFilePath, content);
                    parsedPages.add(page);

                    if (page.getTags().isEmpty()) {
                        issues.add(new LintReport.Issue(LintReport.Severity.INFO, pageFilePath, "Page has no tags"));
                    }

                    titleToPages.computeIfAbsent(normalizeTitle(page.getTitle()), k -> new ArrayList<>())
                            .add(pageFilePath);

                    for (String linkedPath : page.getLinkedPages()) {
                        if (linkedPath.startsWith("/")) {
                            // Absolute markdown link form: validate against the full set of page paths.
                            if (!allPagePaths.contains(linkedPath)) {
                                issues.add(new LintReport.Issue(LintReport.Severity.WARNING, pageFilePath,
                                        "Broken link to: " + linkedPath));
                            } else {
                                inboundLinks.computeIfAbsent(linkedPath, k -> new HashSet<>()).add(pageFilePath);
                            }
                        } else {
                            // Wiki-link form ([[slug]]): validate against the slug set built above. Self-links
                            // are tolerated even though they don't strictly contribute to the inbound count.
                            if (!allSlugs.contains(linkedPath)) {
                                issues.add(new LintReport.Issue(LintReport.Severity.WARNING, pageFilePath,
                                        "Broken wiki-link to: [[" + linkedPath + "]]"));
                            } else {
                                // Find the page file matching this slug and credit it as an inbound link so the
                                // orphan-detection pass below also accounts for slug-based references.
                                for (String candidate : pageFiles) {
                                    if (slugFromPagePath(candidate).equals(linkedPath)) {
                                        inboundLinks.computeIfAbsent(candidate, k -> new HashSet<>()).add(pageFilePath);
                                        break;
                                    }
                                }
                            }
                        }
                    }

                } catch (Exception e) {
                    log.warn("Failed to lint page {}: {}", pageFilePath, e.getMessage());
                    issues.add(new LintReport.Issue(LintReport.Severity.ERROR, pageFilePath,
                            "Failed to read/parse page: " + e.getMessage()));
                }
            }

            for (Map.Entry<String, Set<String>> entry : inboundLinks.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    issues.add(new LintReport.Issue(LintReport.Severity.INFO, entry.getKey(),
                            "Orphan page: no inbound links"));
                }
            }

            // Report any normalized title that maps to two or more pages. INFO severity because the
            // duplication is a hint, not a hard error — a human reviewer decides whether to merge.
            for (Map.Entry<String, List<String>> entry : titleToPages.entrySet()) {
                if (entry.getValue().size() > 1) {
                    final String paths = String.join(", ", entry.getValue());
                    issues.add(new LintReport.Issue(LintReport.Severity.INFO, null,
                            "Duplicate title across pages: " + paths));
                }
            }

            // Gap #2: run the optional semantic lint strategy (contradictions, stale claims, missing
            // concepts, data gaps). Findings are appended to the same issues list so callers see structural
            // and semantic checks in a single report. The strategy is never-throw by contract; defensive
            // wrapping below converts a misbehaving implementation into a single ERROR issue so the whole
            // pass doesn't fail.
            if (lintStrategy != null && !parsedPages.isEmpty()) {
                try {
                    final List<LintReport.Issue> semantic = lintStrategy.lint(scope, parsedPages);
                    if (semantic != null) {
                        issues.addAll(semantic);
                    }
                } catch (RuntimeException e) {
                    log.warn("WikiLintStrategy threw for scope {}: {}", scope, e.getMessage());
                    issues.add(new LintReport.Issue(LintReport.Severity.ERROR, null,
                            "Semantic lint failed: " + e.getMessage()));
                }
            }

            appendLogEntry(fs, scope, WikiLogEntry.Operation.LINT_PERFORMED, null,
                    "Lint checked " + checkedPageCount + " pages, found " + issues.size() + " issues");

        } catch (Exception e) {
            log.error("Unexpected error during lint for scope {}: {}", scope, e.getMessage(), e);
            issues.add(new LintReport.Issue(LintReport.Severity.ERROR, null, "Lint failed: " + e.getMessage()));
        }

        return LintReport.builder().issues(issues).checkedPageCount(checkedPageCount).checkedAt(checkedAt).build();
    }

    @Override
    public MigrationResult migrateFrontmatter(WikiScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");

        // Resolve VFS up front so context-lifecycle errors propagate to the caller (see ingest()).
        final VirtualFileSystem fs = fileSystemFor(scope);

        final long startTime = System.currentTimeMillis();
        final List<String> errors = new ArrayList<>();
        int migrated = 0;
        int skipped = 0;

        // Hold the per-scope write lock so the read-modify-write cycle is atomic against concurrent ingests.
        final Lock lock = scopeWriteLocks.computeIfAbsent(scope, k -> new ReentrantLock());
        lock.lock();
        try {
            final String pagesDir = pagesDirectory(scope);
            if (!fs.exists(pagesDir)) {
                return MigrationResult.empty();
            }

            for (String pagePath : WikiIo.listPageFiles(fs, pagesDir)) {
                try {
                    final String content = WikiIo.readContent(fs, pagePath);
                    final String migratedContent = addTypeFrontmatterIfMissing(content, pagePath);
                    if (migratedContent == null) {
                        // Either no frontmatter at all or already has a type: field — leave the file alone.
                        skipped++;
                        continue;
                    }
                    fs.write(pagePath, migratedContent);
                    migrated++;
                } catch (Exception e) {
                    log.warn("Failed to migrate page {}: {}", pagePath, e.getMessage());
                    errors.add(pagePath + ": " + e.getMessage());
                }
            }

            // Append a single summary entry per migration pass so the log reflects the admin operation — per
            // docs/references/llm-wiki.md, the log is "an append-only record of what happened and when."
            // Migration touches many pages but is conceptually a single action, so one entry (not one per page)
            // matches the style of LINT_PERFORMED.
            if (migrated > 0 || !errors.isEmpty()) {
                appendLogEntry(fs, scope, WikiLogEntry.Operation.MIGRATION_PERFORMED, null,
                        "Migrated " + migrated + " pages, skipped " + skipped + ", errors " + errors.size());
            }
        } finally {
            lock.unlock();
        }

        final long durationMs = System.currentTimeMillis() - startTime;
        log.debug("Migration done for {}: migrated={}, skipped={}, errors={}, ms={}", scope, migrated, skipped,
                errors.size(), durationMs);

        return MigrationResult.builder().migratedCount(migrated).skippedCount(skipped).durationMs(durationMs)
                .errors(errors).build();
    }

    /**
     * Returns the page content with a {@code type:} frontmatter field inserted when it is missing, inferring
     * the type from the file-name prefix. Returns {@code null} when no edit is needed — either the page has no
     * frontmatter at all, or the {@code type:} field is already present.
     */
    static String addTypeFrontmatterIfMissing(String content, String pagePath) {
        if (content == null || !content.startsWith("---\n")) {
            return null;
        }
        final int closing = content.indexOf("\n---", 4);
        if (closing < 0) {
            // Unterminated frontmatter — leave the page alone rather than risk corrupting it.
            return null;
        }
        final String frontmatter = content.substring(4, closing);
        if (frontmatter.contains("\ntype:") || frontmatter.startsWith("type:")) {
            return null;
        }

        final WikiPageType inferredType = WikiPageType.fromFileName(WikiIo.extractFileName(pagePath));

        // Insert the type: line at the top of the frontmatter so it sits next to title:.
        final StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("type: ").append(inferredType.getToken()).append('\n');
        sb.append(frontmatter);
        if (!frontmatter.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append(content.substring(closing + 1)); // includes the closing "---\n..."
        return sb.toString();
    }

    @Override
    public WikiLog getLog(WikiScope scope, int limit) {
        Objects.requireNonNull(scope, "scope must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be > 0, got: " + limit);
        }

        // Resolve VFS up front so context-lifecycle errors propagate to the caller (see ingest()).
        final VirtualFileSystem fs = fileSystemFor(scope);

        try {
            final String logPath = logPath(scope);
            if (!fs.exists(logPath)) {
                return WikiLog.builder().entries(Collections.emptyList()).totalEntryCount(0).build();
            }

            final String logContent = WikiIo.readContent(fs, logPath);
            final List<WikiLogEntry> allEntries = parseLogEntries(logContent);

            final List<WikiLogEntry> limited = allEntries.stream().limit(limit).toList();

            return WikiLog.builder().entries(limited).totalEntryCount(allEntries.size()).build();

        } catch (Exception e) {
            log.error("Unexpected error reading log for scope {}: {}", scope, e.getMessage(), e);
            return WikiLog.builder().entries(Collections.emptyList()).totalEntryCount(0).build();
        }
    }

    @Override
    public void close() {
        // Does not own the locator, its VFS instances, the page generator, or the search strategy; nothing to close.
    }

    // -------------------------------------------------------------------------
    // Locator helpers
    // -------------------------------------------------------------------------

    private VirtualFileSystem fileSystemFor(WikiScope scope) {
        final VirtualFileSystem fs = locator.fileSystemFor(scope);
        if (fs == null) {
            throw new IllegalStateException("WikiStorageLocator returned null VFS for scope: " + scope);
        }
        return fs;
    }

    private String scopeDirectory(WikiScope scope) {
        final String dir = locator.directoryFor(scope);
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException("WikiStorageLocator returned null/empty directory for scope: " + scope);
        }
        return dir.endsWith("/") ? dir : dir + "/";
    }

    private String pagesDirectory(WikiScope scope) {
        return scopeDirectory(scope) + "pages/";
    }

    /**
     * Returns the bare slug for a wiki page file path, stripping the directory prefix, the {@code .md}
     * extension, and any recognized {@link WikiPageType} prefix. Used by lint to validate
     * {@code [[slug]]} cross-references against the set of pages that actually exist.
     */
    private static String slugFromPagePath(String pagePath) {
        final int lastSlash = pagePath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? pagePath.substring(lastSlash + 1) : pagePath;
        if (fileName.endsWith(".md")) {
            fileName = fileName.substring(0, fileName.length() - 3);
        }
        for (WikiPageType type : WikiPageType.values()) {
            final String prefix = type.getPrefix() + "-";
            if (fileName.startsWith(prefix)) {
                return fileName.substring(prefix.length());
            }
        }
        return fileName;
    }

    /**
     * Normalizes a title for duplicate detection — lowercase + collapsed whitespace. Two pages with titles
     * that differ only in case or extra whitespace are reported as duplicates by lint.
     */
    private static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String indexPath(WikiScope scope) {
        return scopeDirectory(scope) + "index.md";
    }

    private String logPath(WikiScope scope) {
        return scopeDirectory(scope) + "log.md";
    }

    // -------------------------------------------------------------------------
    // File listing helpers
    // -------------------------------------------------------------------------

    private List<String> listSourceFiles(VirtualFileSystem sourceVfs, String directory, IngestOptions options) {
        try {
            final List<String> candidates;
            if (options.isRecursive()) {
                candidates = sourceVfs.listRecursive(directory);
            } else {
                candidates = sourceVfs.list(directory).stream().filter(p -> !sourceVfs.isDirectory(p)).toList();
            }
            return candidates.stream().filter(p -> WikiIo.matchesFilePatterns(p, options.getFilePatterns())).toList();
        } catch (Exception e) {
            log.warn("Failed to list source files in {}: {}", directory, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> collectExistingPageNames(VirtualFileSystem fs, WikiScope scope) {
        try {
            final String pagesDir = pagesDirectory(scope);
            if (!fs.exists(pagesDir)) {
                return new ArrayList<>();
            }
            return new ArrayList<>(WikiIo.listPageFiles(fs, pagesDir).stream().map(WikiIo::extractFileName).toList());
        } catch (Exception e) {
            log.debug("Failed to collect existing page names: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // -------------------------------------------------------------------------
    // Index update
    // -------------------------------------------------------------------------

    private void updateIndex(VirtualFileSystem fs, WikiScope scope) {
        // Hold the scope write lock so concurrent ingests on the same scope cannot produce a torn index view
        // (collecting page files and writing index.md must be atomic with respect to other writers).
        final Lock lock = scopeWriteLocks.computeIfAbsent(scope, k -> new ReentrantLock());
        lock.lock();
        try {
            final String pagesDir = pagesDirectory(scope);
            final List<String> pageFiles = fs.exists(pagesDir)
                    ? WikiIo.listPageFiles(fs, pagesDir)
                    : Collections.emptyList();

            final String scopeLabel = scope.getAgentName() + "/" + scope.getContextId() + "/" + scope.getWikiName();

            final List<WikiPageGenerator.PageInfo> pageInfos = buildPageInfoList(fs, pageFiles);
            final String indexContent = generateIndexContentSafely(scope, scopeLabel, pageInfos);
            fs.write(indexPath(scope), indexContent);
            log.debug("Updated index for scope {} with {} pages", scope, pageFiles.size());

        } catch (Exception e) {
            log.warn("Failed to update index for scope {}: {}", scope, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Page generator invocation guards
    // -------------------------------------------------------------------------

    /**
     * Invokes {@link WikiPageGenerator#generatePageContent} while defensively enforcing the interface contract.
     *
     * <p>
     * The contract states that implementations must never throw exceptions and must return non-null, non-empty
     * content. We validate both conditions here — external {@code WikiPageGenerator} implementations cross a module
     * boundary (e.g., {@code LlmWikiPageGenerator} wraps a remote LLM call), and a silent contract violation would
     * corrupt the wiki with blank or partially-written pages. Violations are surfaced as {@link IOException} so the
     * surrounding ingest loop records them in {@link IngestResult#getErrors()} rather than proceeding with invalid
     * content.
     */
    private String generatePageContentSafely(WikiScope scope, String sourceFilePath, String sourceContent,
            List<String> existingPageNames) throws IOException {
        final String content;
        try {
            content = pageGenerator.generatePageContent(scope, sourceFilePath, sourceContent, existingPageNames);
        } catch (RuntimeException e) {
            throw new IOException("WikiPageGenerator threw an exception (contract violation): " + e.getMessage(), e);
        }
        if (content == null || content.isEmpty()) {
            throw new IOException("WikiPageGenerator returned null/empty content (contract violation)");
        }
        return content;
    }

    /**
     * Defensive wrapper around {@link WikiPageGenerator#extractPages} mirroring {@link #generatePageContentSafely}.
     * The same rationale applies: implementations cross a module boundary, so a contract violation (thrown
     * exception, null/empty list) is converted to {@link IOException} so the surrounding ingest loop records the
     * failure rather than continuing with invalid data.
     */
    private List<GeneratedPage> extractPagesSafely(WikiScope scope, String sourceFilePath, String sourceContent,
            List<String> existingPageNames) throws IOException {
        final List<GeneratedPage> pages;
        try {
            pages = pageGenerator.extractPages(scope, sourceFilePath, sourceContent, existingPageNames);
        } catch (RuntimeException e) {
            throw new IOException(
                    "WikiPageGenerator.extractPages threw an exception (contract violation): " + e.getMessage(), e);
        }
        if (pages == null || pages.isEmpty()) {
            throw new IOException("WikiPageGenerator.extractPages returned null/empty list (contract violation)");
        }
        return pages;
    }

    /**
     * Persists every {@link GeneratedPage} produced from a single source document and returns a count of
     * created/updated/merged pages so the caller can roll them up into the {@link IngestResult}.
     *
     * <p>
     * Strategy handling:
     * <ul>
     * <li>{@link GeneratedPage.UpdateStrategy#CREATE} — write only if no file exists at the target path; if a file
     * exists and the ingest options do not force overwrite, skip silently.
     * <li>{@link GeneratedPage.UpdateStrategy#REPLACE} — always write; counts as updated when overwriting an
     * existing file, created otherwise.
     * <li>{@link GeneratedPage.UpdateStrategy#MERGE} — when the wiki was constructed with a non-null
     * {@link WikiPageMerger} AND {@link IngestOptions#isEnableMerge()} is true AND a target file already exists,
     * the merger is invoked to combine the existing page with the incoming generated page; the result is then
     * written and counted as <i>merged</i>. Otherwise (no merger, opt-in flag off, or target file does not yet
     * exist) MERGE falls through to plain REPLACE — preserving the pre-merge behaviour and avoiding silent data
     * loss.
     * </ul>
     *
     * <p>
     * The {@code overwrite} ingest option still wins globally: when set, every page is treated as if it were
     * {@code REPLACE}, bypassing the merger entirely. This preserves the historical "force a clean re-ingest"
     * semantics.
     *
     * <p>
     * <b>Concurrency</b>: this method takes the per-scope write lock for its entire duration. This serializes
     * concurrent ingests within a single scope so that any read-modify-write cycle (MERGE in particular) is atomic
     * against other writers. Cross-scope ingests still run in parallel because each scope owns a distinct lock.
     */
    private SourceWriteOutcome writeGeneratedPages(VirtualFileSystem fs, WikiScope scope, String sourceFilePath,
            List<GeneratedPage> pages, IngestOptions options, List<String> existingPageNames) throws IOException {
        int created = 0;
        int updated = 0;
        int merged = 0;

        final Lock lock = scopeWriteLocks.computeIfAbsent(scope, k -> new ReentrantLock());
        lock.lock();
        try {
            for (GeneratedPage page : pages) {
                final String pageFileName = WikiIo.buildPageFileName(page.getType(), page.getSlug());
                final String pagePath = pagesDirectory(scope) + pageFileName;
                final boolean pageExists = fs.exists(pagePath);

                final GeneratedPage.UpdateStrategy effectiveStrategy = options.isOverwrite()
                        ? GeneratedPage.UpdateStrategy.REPLACE
                        : page.getStrategy();

                if (pageExists && effectiveStrategy == GeneratedPage.UpdateStrategy.CREATE) {
                    log.debug("Skipping existing page (CREATE strategy, overwrite=false): {}", pagePath);
                    continue;
                }

                final boolean shouldMerge = effectiveStrategy == GeneratedPage.UpdateStrategy.MERGE && pageExists
                        && pageMerger != null && options.isEnableMerge();

                final GeneratedPage pageToWrite;
                final boolean countAsMerged;
                if (shouldMerge) {
                    pageToWrite = mergePageSafely(scope, fs, pagePath, page);
                    countAsMerged = true;
                } else {
                    if (effectiveStrategy == GeneratedPage.UpdateStrategy.MERGE) {
                        // Either the merger is not wired, the opt-in flag is off, or the target file doesn't exist
                        // yet. Fall through to plain replace and record the reason at debug for observability.
                        log.debug("MERGE falling through to REPLACE for {} (mergerWired={}, enableMerge={}, exists={})",
                                pagePath, pageMerger != null, options.isEnableMerge(), pageExists);
                    }
                    pageToWrite = page;
                    countAsMerged = false;
                }

                fs.write(pagePath, pageToWrite.getContent());
                if (countAsMerged) {
                    merged++;
                    appendLogEntry(fs, scope, WikiLogEntry.Operation.PAGE_UPDATED, pagePath,
                            "Merged from source: " + sourceFilePath);
                } else if (pageExists) {
                    updated++;
                    appendLogEntry(fs, scope, WikiLogEntry.Operation.PAGE_UPDATED, pagePath,
                            "Updated from source: " + sourceFilePath);
                } else {
                    created++;
                    appendLogEntry(fs, scope, WikiLogEntry.Operation.PAGE_CREATED, pagePath,
                            "Created from source: " + sourceFilePath);
                }

                // Track newly created/updated page so subsequent extractions in this same ingest cycle can
                // cross-link to it. Duplicates are harmless — the prompt just dedupes downstream.
                if (!existingPageNames.contains(pageFileName)) {
                    existingPageNames.add(pageFileName);
                }
            }
        } finally {
            lock.unlock();
        }

        return new SourceWriteOutcome(created, updated, merged);
    }

    /**
     * Defensive wrapper around {@link WikiPageMerger#merge} mirroring the other safety wrappers in this class.
     * Reads the existing page from the VFS, hands both sides to the merger, and converts any contract violation
     * into an {@link IOException} so the surrounding ingest loop records the error without writing partial data.
     */
    private GeneratedPage mergePageSafely(WikiScope scope, VirtualFileSystem fs, String pagePath,
            GeneratedPage incoming) throws IOException {
        final String existingContent = WikiIo.readContent(fs, pagePath);
        final WikiPage existing = WikiIo.parseWikiPage(pagePath, existingContent);

        final GeneratedPage merged;
        try {
            merged = pageMerger.merge(scope, existing, incoming);
        } catch (RuntimeException e) {
            throw new IOException("WikiPageMerger.merge threw an exception (contract violation): " + e.getMessage(), e);
        }
        if (merged == null) {
            throw new IOException("WikiPageMerger.merge returned null (contract violation)");
        }
        if (merged.getContent() == null || merged.getContent().isEmpty()) {
            throw new IOException("WikiPageMerger.merge returned empty content (contract violation)");
        }
        return merged;
    }

    /**
     * Tiny aggregate carrying the per-source page counts back to the ingest loop. Package-private static class
     * (rather than a record) to keep the project's "prefer class over record" convention from CLAUDE.md.
     */
    private static final class SourceWriteOutcome {
        final int created;
        final int updated;
        final int merged;

        SourceWriteOutcome(int created, int updated, int merged) {
            this.created = created;
            this.updated = updated;
            this.merged = merged;
        }
    }

    /**
     * Invokes {@link WikiPageGenerator#generateIndexContent} while enforcing the same non-null/non-empty contract as
     * {@link #generatePageContentSafely}. Failures propagate to the caller, which already handles index write errors
     * gracefully via WARN logging without aborting ingest.
     */
    private String generateIndexContentSafely(WikiScope scope, String scopeLabel,
            List<WikiPageGenerator.PageInfo> pages) throws IOException {
        final String content;
        try {
            content = pageGenerator.generateIndexContent(scope, scopeLabel, pages);
        } catch (RuntimeException e) {
            throw new IOException("WikiPageGenerator threw an exception (contract violation): " + e.getMessage(), e);
        }
        if (content == null || content.isEmpty()) {
            throw new IOException("WikiPageGenerator returned null/empty index content (contract violation)");
        }
        return content;
    }

    private List<WikiPageGenerator.PageInfo> buildPageInfoList(VirtualFileSystem fs, List<String> pageFiles) {
        final List<WikiPageGenerator.PageInfo> result = new ArrayList<>();
        for (String pageFile : pageFiles) {
            try {
                final String content = WikiIo.readContent(fs, pageFile);
                final String title = WikiIo.extractTitle(content, pageFile);
                final String preview = extractFirstContentLine(content);
                final List<String> tags = WikiIo.extractTags(content);
                result.add(new WikiPageGenerator.PageInfo(pageFile, title, preview, tags));
            } catch (Exception e) {
                result.add(new WikiPageGenerator.PageInfo(pageFile, WikiIo.extractFileName(pageFile), null,
                        Collections.emptyList()));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // fileAnswer helpers
    // -------------------------------------------------------------------------

    /**
     * Renders a {@link FiledAnswer} to the markdown body that will be written under {@code pages/answer-*.md}.
     * Produces YAML frontmatter, the content body, and a "References" section containing {@code [[wiki-link]]}
     * back-references (so the filed answer participates in the graph alongside ingested pages).
     */
    private static String buildFiledAnswerContent(FiledAnswer answer) {
        final StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        // Sanitize frontmatter values so a title/tag carrying a newline or ']' cannot inject a spurious
        // type:/tags: line that WikiIo's MULTILINE first-match regexes would then honor (metadata spoofing).
        sb.append("title: ").append(WikiIo.sanitizeFrontmatterText(answer.getTitle())).append('\n');
        sb.append("tags: [");
        for (int i = 0; i < answer.getTags().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(WikiIo.sanitizeFrontmatterTag(answer.getTags().get(i)));
        }
        sb.append("]\n");
        sb.append("source: filed-answer\n");
        sb.append("---\n\n");
        sb.append("# ").append(WikiIo.sanitizeFrontmatterText(answer.getTitle())).append("\n\n");
        sb.append(answer.getContent());
        if (!answer.getContent().endsWith("\n")) {
            sb.append('\n');
        }
        if (!answer.getSourceRefs().isEmpty()) {
            sb.append("\n## References\n\n");
            for (String ref : answer.getSourceRefs()) {
                sb.append("- [[").append(ref).append("]]\n");
            }
        }
        return sb.toString();
    }

    /**
     * Converts a human-readable title into a filesystem-safe slug: lowercase alphanumerics, runs of other
     * characters collapsed to a single dash, leading/trailing dashes stripped. Empty input yields "untitled"
     * so {@code fileAnswer()} never produces a nameless file.
     */
    static String slugify(String title) {
        // Locale.ROOT matters: the default locale would turn 'I' into 'ı' on Turkish JVMs and corrupt the
        // resulting file-system slug. Every other toLowerCase() call in this package uses ROOT for the same
        // reason — this one had been missed.
        final String lower = title.toLowerCase(java.util.Locale.ROOT);
        final StringBuilder sb = new StringBuilder(lower.length());
        boolean lastDash = false;
        for (int i = 0; i < lower.length(); i++) {
            final char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastDash = false;
            } else if (!lastDash && sb.length() > 0) {
                sb.append('-');
                lastDash = true;
            }
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() == 0 ? "untitled" : sb.toString();
    }

    private static String extractFirstContentLine(String content) {
        final String[] lines = content.split("\n");
        for (String line : lines) {
            final String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---")
                    && !trimmed.startsWith("tags:") && !trimmed.startsWith("title:") && !trimmed.startsWith("source:")
                    && !trimmed.startsWith("<!--")) {
                return trimmed.length() > CONTENT_PREVIEW_LENGTH
                        ? trimmed.substring(0, CONTENT_PREVIEW_LENGTH) + "..."
                        : trimmed;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Log helpers
    // -------------------------------------------------------------------------

    private void appendLogEntry(VirtualFileSystem fs, WikiScope scope, WikiLogEntry.Operation operation,
            String pagePath, String summary) {
        final Lock lock = scopeWriteLocks.computeIfAbsent(scope, k -> new ReentrantLock());
        lock.lock();
        try {
            final String logFilePath = logPath(scope);
            final String existing = fs.exists(logFilePath) ? WikiIo.readContent(fs, logFilePath) : "";
            // Heading-style entry: `## [<timestamp>] <OP> | <path> | <summary>` followed by a blank line.
            // Matches the prefix-per-entry convention from docs/references/llm-wiki.md so entries are both
            // human-readable in Obsidian and machine-parseable with a single grep.
            final String entry = "## [" + Instant.now() + "] " + operation.name() + " | "
                    + (pagePath != null ? pagePath : "") + " | " + (summary != null ? summary : "") + "\n\n";
            // Prepend to keep most-recent-first order
            fs.write(logFilePath, entry + existing);
        } catch (Exception e) {
            log.warn("Failed to append log entry for scope {}: {}", scope, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Parses all log entries from the given content. Recognizes both the current heading format and the legacy
     * bullet format; results from both are merged and returned in most-recent-first order so callers always see a
     * consistent view regardless of which writer produced the log.
     */
    private static List<WikiLogEntry> parseLogEntries(String logContent) {
        final List<WikiLogEntry> entries = new ArrayList<>();

        final Matcher newMatcher = LOG_ENTRY_PATTERN.matcher(logContent);
        while (newMatcher.find()) {
            parseLogEntry(newMatcher.group(1), newMatcher.group(2), newMatcher.group(3), newMatcher.group(4),
                    newMatcher.group(0)).ifPresent(entries::add);
        }

        final Matcher legacyMatcher = LEGACY_LOG_ENTRY_PATTERN.matcher(logContent);
        while (legacyMatcher.find()) {
            parseLogEntry(legacyMatcher.group(1), legacyMatcher.group(2), legacyMatcher.group(3),
                    legacyMatcher.group(4), legacyMatcher.group(0)).ifPresent(entries::add);
        }

        entries.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
        return entries;
    }

    private static Optional<WikiLogEntry> parseLogEntry(String rawTimestamp, String rawOperation, String rawPagePath,
            String rawSummary, String rawLine) {
        try {
            final Instant timestamp = Instant.parse(rawTimestamp.trim());
            final WikiLogEntry.Operation operation = WikiLogEntry.Operation.valueOf(rawOperation.trim());
            final String pagePath = rawPagePath.trim().isEmpty() ? null : rawPagePath.trim();
            final String summary = rawSummary.trim().isEmpty() ? null : rawSummary.trim();
            return Optional.of(WikiLogEntry.builder().timestamp(timestamp).operation(operation).pagePath(pagePath)
                    .summary(summary).build());
        } catch (Exception e) {
            log.debug("Failed to parse log entry: {}", rawLine);
            return Optional.empty();
        }
    }

    private int countLogEntriesOfOperation(VirtualFileSystem fs, WikiScope scope, WikiLogEntry.Operation target) {
        try {
            final String logPath = logPath(scope);
            if (!fs.exists(logPath)) {
                return 0;
            }
            final String content = WikiIo.readContent(fs, logPath);
            int count = 0;
            for (WikiLogEntry entry : parseLogEntries(content)) {
                if (entry.getOperation() == target) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("Failed to count log entries for scope {}: {}", scope, e.getMessage());
            return 0;
        }
    }
}
