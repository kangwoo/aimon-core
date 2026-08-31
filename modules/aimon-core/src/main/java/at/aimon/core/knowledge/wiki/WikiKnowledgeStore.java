package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.knowledge.IndexOptions;
import at.aimon.core.knowledge.IndexResult;
import at.aimon.core.knowledge.IndexStatus;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeSource;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;

/**
 * Adapter that exposes a {@link WikiKnowledgeBase} through the {@link KnowledgeStore} interface.
 *
 * <p>
 * Enables wiki knowledge bases to integrate with the existing agent execution pipeline without modifying
 * {@code OrcaAgentRuntime}, {@code OrcaAgentExecutor}, or {@code OrcaAgentRuntimeFactory}. The
 * adapter
 * translates between {@link KnowledgeStore} types ({@link KnowledgeScope}, {@link KnowledgeSource}, etc.) and wiki
 * types
 * ({@link WikiScope}, {@link WikiSource}, etc.).
 *
 * <p>
 * Scope mapping: {@link KnowledgeScope#getAgentName()} maps to {@link WikiScope#getAgentName()}, and
 * {@link KnowledgeScope#getContextId()} maps to {@link WikiScope#getContextId()}, preserving per-context isolation.
 * The {@code defaultWikiName} provided at construction time is used as the wiki name for all operations.
 *
 * <p>
 * Score normalization: since wiki search returns an ordered list without explicit relevance scores, scores are assigned
 * by linear position — the first result receives {@code 1.0}, the last receives a value approaching {@code 0.0}, with
 * even spacing between.
 *
 * <p>
 * For wiki-specific operations (lint, getLog, getPage), access the underlying wiki via
 * {@link #getWikiKnowledgeBase()} or {@link #getWikiKnowledgeBaseAdmin()}.
 *
 * <p>
 * Thread safety: this class is thread-safe if the underlying {@link WikiKnowledgeBase} implementation is thread-safe.
 * The {@code lastWikiScope} field is {@code volatile} to ensure visibility across threads after index/reindex calls.
 *
 * <pre>{@code
 * WikiKnowledgeBase wiki = new DefaultWikiKnowledgeBase(...);
 * KnowledgeStore store = new WikiKnowledgeStore(wiki, "runbook-wiki");
 * store.index(scope, source, IndexOptions.defaults());
 * List<SearchResult> results = store.search(scope, SearchQuery.builder()
 *         .queryText("CrashLoopBackOff")
 *         .build());
 * }</pre>
 *
 * @see WikiKnowledgeBase
 * @see KnowledgeStore
 */
public final class WikiKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(WikiKnowledgeStore.class);

    /** Maximum number of characters included in a {@link SearchResult}'s chunk content. */
    private static final int MAX_CHUNK_CONTENT_LENGTH = 2000;

    private final WikiKnowledgeBase wikiKnowledgeBase;
    private final String defaultWikiName;

    /** Minimum relevance score for any returned search result. */
    private static final double MIN_SCORE = 0.1;

    /**
     * The last wiki scope resolved from an index or search call. Used by {@link #getStatus()} which has no scope
     * parameter. Volatile for cross-thread visibility.
     */
    private volatile WikiScope lastWikiScope;

    /**
     * Creates a {@code WikiKnowledgeStore} with the wiki name {@code "default"}.
     *
     * @param wikiKnowledgeBase
     *            the underlying wiki knowledge base (must not be null)
     * @throws NullPointerException
     *             if {@code wikiKnowledgeBase} is null
     */
    public WikiKnowledgeStore(WikiKnowledgeBase wikiKnowledgeBase) {
        this(wikiKnowledgeBase, "default");
    }

    /**
     * Creates a {@code WikiKnowledgeStore} with the given wiki name.
     *
     * @param wikiKnowledgeBase
     *            the underlying wiki knowledge base (must not be null)
     * @param defaultWikiName
     *            the wiki name used for all scope translations (must not be null or empty)
     * @throws NullPointerException
     *             if any parameter is null
     * @throws IllegalArgumentException
     *             if {@code defaultWikiName} is empty
     */
    public WikiKnowledgeStore(WikiKnowledgeBase wikiKnowledgeBase, String defaultWikiName) {
        this.wikiKnowledgeBase = Objects.requireNonNull(wikiKnowledgeBase, "wikiKnowledgeBase must not be null");
        this.defaultWikiName = Objects.requireNonNull(defaultWikiName, "defaultWikiName must not be null");
        if (defaultWikiName.isEmpty()) {
            throw new IllegalArgumentException("defaultWikiName must not be empty");
        }
        this.lastWikiScope = new WikiScope("_default", "_default", defaultWikiName);
    }

    /**
     * Returns the underlying {@link WikiKnowledgeBase}.
     *
     * <p>
     * Use this accessor to invoke wiki-specific operations such as {@link WikiKnowledgeBase#getPage(WikiScope, String)}
     * that are not part of the {@link KnowledgeStore} contract.
     *
     * @return the wiki knowledge base (never null)
     */
    public WikiKnowledgeBase getWikiKnowledgeBase() {
        return wikiKnowledgeBase;
    }

    /**
     * Returns an {@link Optional} containing the underlying wiki cast as {@link WikiKnowledgeBaseAdmin}, or
     * {@link Optional#empty()} if the implementation does not support admin operations.
     *
     * @return an optional admin view of the wiki knowledge base
     */
    public Optional<WikiKnowledgeBaseAdmin> getWikiKnowledgeBaseAdmin() {
        if (wikiKnowledgeBase instanceof WikiKnowledgeBaseAdmin admin) {
            return Optional.of(admin);
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Translates the {@link KnowledgeScope} and {@link KnowledgeSource} to wiki types, sets {@code overwrite=true} in
     * {@link IngestOptions}, calls {@link WikiKnowledgeBase#ingest(WikiScope, WikiSource, IngestOptions)}, and converts
     * the {@link IngestResult} to an {@link IndexResult}.
     */
    @Override
    public IndexResult index(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return doIngest(scope, source, options, true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Identical to {@link #index(KnowledgeScope, KnowledgeSource, IndexOptions)} — both force {@code overwrite=true} in
     * the underlying {@link IngestOptions}.
     */
    @Override
    public IndexResult reindex(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return doIngest(scope, source, options, true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Translates the {@link KnowledgeScope} and {@link SearchQuery} to {@link WikiScope} and {@link WikiSearchQuery},
     * calls {@link WikiKnowledgeBase#search(WikiScope, WikiSearchQuery)}, and converts each {@link WikiPage} to a
     * {@link SearchResult}. Scores are assigned by linear position in the result list (first = 1.0).
     */
    @Override
    public List<SearchResult> search(KnowledgeScope scope, SearchQuery query) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(query, "query must not be null");
        try {
            WikiScope wikiScope = toWikiScope(scope);
            rememberScope(wikiScope);

            if (!query.getMetadata().isEmpty()) {
                log.warn("SearchQuery metadata filter is not supported by WikiKnowledgeStore and will be ignored");
            }
            if (query.isCrossContext()) {
                log.debug("SearchQuery crossContext flag is not supported by WikiKnowledgeStore");
            }

            WikiSearchQuery wikiQuery = WikiSearchQuery.builder().queryText(query.getQueryText())
                    .maxResults(query.getMaxResults())
                    .pagePathPatterns(query.getFilePatterns().isEmpty() ? null : query.getFilePatterns()).build();

            log.debug("Searching wiki scope={} query={}", wikiScope, wikiQuery);

            List<WikiPage> pages = wikiKnowledgeBase.search(wikiScope, wikiQuery);

            log.debug("Wiki search returned {} pages", pages.size());

            return toSearchResults(pages);
        } catch (Exception e) {
            log.error("Wiki search failed for scope={}: {}", scope, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Returns the status of the wiki knowledge base for the last scope seen by {@link #index} or {@link #search}. On
     * first call before any index/search, uses a synthetic scope derived from the {@code defaultWikiName}.
     */
    @Override
    public IndexStatus getStatus() {
        try {
            WikiScope wikiScope = lastWikiScope;
            WikiStatus wikiStatus = wikiKnowledgeBase.getStatus(wikiScope);
            return toIndexStatus(wikiStatus);
        } catch (Exception e) {
            log.error("Failed to get wiki status: {}", e.getMessage(), e);
            return IndexStatus.builder().state(IndexStatus.State.ERROR).build();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Delegates to {@link WikiKnowledgeBase#close()}.
     */
    @Override
    public void close() {
        try {
            wikiKnowledgeBase.close();
            log.debug("WikiKnowledgeStore closed");
        } catch (Exception e) {
            log.warn("Exception while closing wiki knowledge base: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private IndexResult doIngest(KnowledgeScope scope, KnowledgeSource source, IndexOptions options,
            boolean overwrite) {
        try {
            WikiScope wikiScope = toWikiScope(scope);
            rememberScope(wikiScope);

            WikiSource wikiSource = new WikiSource(source.getFileSystem(), source.getDirectory());

            IngestOptions ingestOptions = IngestOptions.builder().filePatterns(options.getFilePatterns())
                    .recursive(options.isRecursive()).maxDocuments(options.getMaxDocuments()).overwrite(overwrite)
                    .build();

            log.debug("Ingesting wiki scope={} source={} overwrite={}", wikiScope, wikiSource, overwrite);

            IngestResult ingestResult = wikiKnowledgeBase.ingest(wikiScope, wikiSource, ingestOptions);

            log.debug("Wiki ingest complete: {}", ingestResult);

            return toIndexResult(ingestResult);
        } catch (Exception e) {
            log.error("Wiki ingest failed for scope={}: {}", scope, e.getMessage(), e);
            return IndexResult.builder().errors(List.of("Ingest failed: " + e.getMessage())).build();
        }
    }

    private WikiScope toWikiScope(KnowledgeScope scope) {
        return new WikiScope(scope.getAgentName(), scope.getContextId(), defaultWikiName);
    }

    private void rememberScope(WikiScope wikiScope) {
        lastWikiScope = wikiScope;
    }

    private static IndexResult toIndexResult(IngestResult ingestResult) {
        int indexedChunkCount = ingestResult.getCreatedPageCount() + ingestResult.getUpdatedPageCount();
        return IndexResult.builder().indexedDocumentCount(ingestResult.getIngestedCount())
                .indexedChunkCount(indexedChunkCount).skippedDocumentCount(ingestResult.getSkippedCount())
                .durationMs(ingestResult.getDurationMs())
                .errors(ingestResult.getErrors().isEmpty() ? null : new ArrayList<>(ingestResult.getErrors())).build();
    }

    private static List<SearchResult> toSearchResults(List<WikiPage> pages) {
        if (pages.isEmpty()) {
            return List.of();
        }
        int total = pages.size();
        List<SearchResult> results = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            WikiPage page = pages.get(i);
            double score = total == 1 ? 1.0 : Math.max(MIN_SCORE, 1.0 - ((double) i / (total - 1)));
            SearchResult result = toSearchResult(page, score);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    private static SearchResult toSearchResult(WikiPage page, double score) {
        String content = page.getContent();
        if (content.isEmpty()) {
            log.debug("Skipping wiki page with empty content: {}", page.getPath());
            return null;
        }
        String chunkContent = content.length() > MAX_CHUNK_CONTENT_LENGTH
                ? content.substring(0, MAX_CHUNK_CONTENT_LENGTH)
                : content;

        Map<String, String> metadata = buildMetadata(page);

        return SearchResult.builder().documentPath(page.getPath()).chunkContent(chunkContent).score(score).chunkIndex(0)
                .metadata(metadata).build();
    }

    private static Map<String, String> buildMetadata(WikiPage page) {
        Map<String, String> metadata = new HashMap<>(page.getMetadata());
        metadata.put("title", page.getTitle());
        if (!page.getTags().isEmpty()) {
            metadata.put("tags", String.join(",", page.getTags()));
        }
        return metadata;
    }

    private static IndexStatus toIndexStatus(WikiStatus wikiStatus) {
        return IndexStatus.builder().documentCount(wikiStatus.getPageCount()).chunkCount(0)
                .lastIndexedAt(wikiStatus.getLastIngestedAt()).indexedDirectory(wikiStatus.getWikiDirectory())
                .state(toIndexState(wikiStatus.getState())).build();
    }

    private static IndexStatus.State toIndexState(WikiStatus.State wikiState) {
        return switch (wikiState) {
            case READY -> IndexStatus.State.READY;
            case INGESTING -> IndexStatus.State.INDEXING;
            case EMPTY -> IndexStatus.State.EMPTY;
            case ERROR -> IndexStatus.State.ERROR;
        };
    }
}
