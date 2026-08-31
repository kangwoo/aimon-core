package at.aimon.core.knowledge.wiki;

import java.util.List;
import java.util.Optional;

import at.aimon.core.base.ApplicationScoped;

/**
 * Provides wiki page ingestion and search capabilities for agent knowledge bases.
 *
 * <p>
 * A wiki knowledge base manages a searchable index of wiki pages. Pages are read from a {@link WikiSource}, processed,
 * and indexed for efficient retrieval. Search queries return matching {@link WikiPage} instances ranked by relevance.
 *
 * <p>
 * Multi-tenant isolation is achieved via {@link WikiScope}. Each ingested page is tagged with the scope's identifying
 * dimensions. During search, the scope is used as a mandatory filter to ensure data isolation between tenants and
 * contexts.
 *
 * <p>
 * Implementations must ensure:
 * <ul>
 * <li>{@link #search(WikiScope, WikiSearchQuery)} is safe for concurrent calls
 * <li>{@link #getPage(WikiScope, String)} is safe for concurrent calls
 * <li>{@link #close()} is idempotent
 * </ul>
 *
 * <p>
 * Lifecycle: typically created at the application level and shared across agent runtimes. Each context
 * provides its own {@link WikiScope} when calling ingest/search methods.
 *
 * @see WikiKnowledgeBaseAdmin
 * @see WikiScope
 * @see WikiSource
 * @see WikiSearchQuery
 * @see WikiPage
 * @see WikiStatus
 */
public interface WikiKnowledgeBase extends ApplicationScoped, AutoCloseable {

    /**
     * Ingests wiki pages from the given source, tagged with the given scope.
     *
     * <p>
     * Reads pages from the source, processes them, and builds a search index. All ingested pages are tagged with the
     * scope's identifying dimensions for multi-tenant isolation.
     *
     * @param scope
     *            the wiki scope for page tagging (must not be null)
     * @param source
     *            the wiki source to ingest from (must not be null)
     * @param options
     *            ingestion options (must not be null)
     * @return the ingestion result with statistics
     * @throws NullPointerException
     *             if any parameter is null
     */
    IngestResult ingest(WikiScope scope, WikiSource source, IngestOptions options);

    /**
     * Searches the indexed wiki pages within the given scope.
     *
     * <p>
     * Returns pages matching the query. If no results match, returns an empty list. This method must be safe for
     * concurrent calls.
     *
     * @param scope
     *            the wiki scope for filtering (must not be null)
     * @param query
     *            the search query (must not be null)
     * @return a list of matching wiki pages; never null
     * @throws NullPointerException
     *             if any parameter is null
     */
    List<WikiPage> search(WikiScope scope, WikiSearchQuery query);

    /**
     * Returns matching wiki pages alongside the relevance score the search strategy assigned to each result.
     *
     * <p>
     * This is the score-aware variant of {@link #search(WikiScope, WikiSearchQuery)} — useful for callers that
     * need ranking confidence (e.g., LLM answer pipelines that decide whether to trust the top hit, or query
     * tuning UIs that show why each page ranked where it did). Score scales are strategy-defined and not
     * comparable across strategies; see {@link WikiSearchResult} for the contract.
     *
     * <p>
     * The default implementation calls {@link #search(WikiScope, WikiSearchQuery)} and wraps every returned page
     * with a score of {@code 0.0} so that existing {@link WikiKnowledgeBase} implementations stay
     * source-compatible. {@link DefaultWikiKnowledgeBase} overrides this to surface real scores from the
     * underlying {@link WikiSearchStrategy}.
     *
     * @param scope
     *            the wiki scope for filtering (must not be null)
     * @param query
     *            the search query (must not be null)
     * @return a list of matching results with scores; never null, ordered by descending score
     * @throws NullPointerException
     *             if any parameter is null
     */
    default List<WikiSearchResult> searchWithScores(WikiScope scope, WikiSearchQuery query) {
        final List<WikiPage> pages = search(scope, query);
        final java.util.List<WikiSearchResult> wrapped = new java.util.ArrayList<>(pages.size());
        for (WikiPage page : pages) {
            wrapped.add(new WikiSearchResult(page, 0.0));
        }
        return wrapped;
    }

    /**
     * Returns a single wiki page by its path within the given scope.
     *
     * <p>
     * The page path is the canonical identifier for a wiki page within its source. Returns an empty Optional if no page
     * with the given path exists in the scope.
     *
     * @param scope
     *            the wiki scope for filtering (must not be null)
     * @param pagePath
     *            the canonical path of the wiki page (must not be null)
     * @return the wiki page, or {@link Optional#empty()} if not found
     * @throws NullPointerException
     *             if any parameter is null
     */
    Optional<WikiPage> getPage(WikiScope scope, String pagePath);

    /**
     * Returns the current status of the wiki knowledge base for the given scope.
     *
     * @param scope
     *            the wiki scope to query status for (must not be null)
     * @return the wiki status (never null)
     * @throws NullPointerException
     *             if scope is null
     */
    WikiStatus getStatus(WikiScope scope);

    /**
     * Files a synthesized answer back into the wiki as a new page, per the pattern in
     * {@code docs/references/llm-wiki.md} (line 39): "good answers can be filed back into the wiki as new pages."
     * This lets query explorations compound into the knowledge base just like ingested sources do.
     *
     * <p>
     * The implementation creates a page under {@code pages/} with an {@code answer-} prefix, updates the index,
     * appends a {@link WikiLogEntry.Operation#QUERY_FILED} entry to the log, and injects
     * {@code [[wiki-link]]} back-references to {@link FiledAnswer#getSourceRefs()} so the filed page participates
     * in the wiki graph.
     *
     * @param scope
     *            the wiki scope to file the answer in (must not be null)
     * @param answer
     *            the answer to file (must not be null)
     * @return the created {@link WikiPage}
     * @throws NullPointerException
     *             if any parameter is null
     */
    WikiPage fileAnswer(WikiScope scope, FiledAnswer answer);

    /**
     * Synthesizes an LLM-driven answer to a user question by searching the wiki, loading the top supporting
     * pages, and asking the wired {@link WikiAnswerStrategy} to combine them. Closes the natural query loop
     * described in {@code docs/references/llm-wiki.md} ("ask question → wiki gives answer → optionally file
     * back") in a single call so callers don't have to assemble the workflow by hand.
     *
     * <p>
     * The returned {@link Answer} carries both the synthesized text and the supporting wiki page paths so the
     * caller can either present it directly or file it back via
     * {@link #fileAnswer(WikiScope, FiledAnswer)} using {@link Answer#toFiledAnswer()}.
     *
     * <p>
     * The default implementation throws {@link UnsupportedOperationException} so existing
     * {@link WikiKnowledgeBase} implementations stay source-compatible — adding the method does not silently
     * change their semantics. {@link DefaultWikiKnowledgeBase} provides a real implementation when constructed
     * with a non-null {@link WikiAnswerStrategy}.
     *
     * @param scope
     *            the wiki scope (must not be null)
     * @param request
     *            the answer request (must not be null)
     * @return the synthesized answer (never null)
     * @throws UnsupportedOperationException
     *             if the implementation does not support answer synthesis
     * @throws NullPointerException
     *             if any parameter is null
     */
    default Answer answer(WikiScope scope, AnswerRequest request) {
        throw new UnsupportedOperationException("answer is not supported by " + getClass().getName());
    }

    /**
     * Runs a synthesis pass over the existing pages in the given scope, producing higher-level
     * {@link WikiPageType#OVERVIEW} and {@link WikiPageType#SYNTHESIS} pages via the wired
     * {@link SynthesisStrategy}. This is the second-pass workflow described in
     * {@code docs/references/llm-wiki.md}: after a wiki has accumulated enough entity / concept pages, the LLM
     * is asked to step back and write topic overviews and cross-cluster syntheses.
     *
     * <p>
     * The default implementation throws {@link UnsupportedOperationException} so existing
     * {@link WikiKnowledgeBase} implementations stay source-compatible — adding the method does not silently
     * change their semantics. {@link DefaultWikiKnowledgeBase} provides a real implementation when constructed
     * with a non-null {@link SynthesisStrategy}.
     *
     * @param scope
     *            the wiki scope (must not be null)
     * @param options
     *            the synthesis options controlling fan-out and cost (must not be null)
     * @return the synthesis result with statistics (never null)
     * @throws UnsupportedOperationException
     *             if the implementation does not support synthesis
     * @throws NullPointerException
     *             if any parameter is null
     */
    default SynthesizeResult synthesize(WikiScope scope, SynthesizeOptions options) {
        throw new UnsupportedOperationException("synthesize is not supported by " + getClass().getName());
    }

    /**
     * Releases resources held by this knowledge base. Idempotent.
     */
    @Override
    void close();
}
