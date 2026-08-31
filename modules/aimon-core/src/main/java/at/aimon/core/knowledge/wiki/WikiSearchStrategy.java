package at.aimon.core.knowledge.wiki;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy for searching wiki pages within a scope.
 *
 * <p>
 * The llm-wiki.md pattern (see {@code docs/references/llm-wiki.md}) prescribes an <i>index-first, drill-down</i> search
 * flow: the LLM-maintained {@code index.md} is consulted first to locate relevant pages, and only the top candidates
 * are read in full. This interface exists so that alternative backends (e.g., BM25/vector search like
 * <a href="https://github.com/tobi/qmd">qmd</a>) can replace the built-in scanner without touching
 * {@link DefaultWikiKnowledgeBase}.
 *
 * <p>
 * Implementations must be thread-safe and stateless. They must never throw on transient I/O or parse failures —
 * degrade gracefully, log at {@code WARN}, and return an empty list instead. Lifecycle/configuration errors
 * (e.g., a mis-wired VFS) should still propagate so misconfiguration is not mistaken for "wiki is empty".
 *
 * @see IndexFirstSearchStrategy
 * @see FullScanSearchStrategy
 * @see WikiSearchContext
 */
public interface WikiSearchStrategy {

    /**
     * Returns pages matching the given query within the given context, ranked by descending relevance.
     *
     * @param query
     *            the search query (must not be null)
     * @param context
     *            resolved execution context (must not be null)
     * @return matching pages (never null, capped at {@link WikiSearchQuery#getMaxResults()})
     */
    List<WikiPage> search(WikiSearchQuery query, WikiSearchContext context);

    /**
     * Returns matching pages alongside the relevance score the strategy assigned to each result, ordered by
     * descending score. This is the score-aware variant of {@link #search(WikiSearchQuery, WikiSearchContext)}.
     *
     * <p>
     * The default implementation calls {@link #search(WikiSearchQuery, WikiSearchContext)} and wraps every
     * returned page with a score of {@code 0.0}. Concrete strategies that compute scores internally are
     * encouraged to override this method and surface real values so callers (e.g., LLM rerank pipelines, query
     * tuning UIs, or {@code answer()} flows that need confidence) can use them.
     *
     * <p>
     * Score scales are strategy-defined and not comparable across strategies — see {@link WikiSearchResult} for
     * the contract.
     *
     * @param query
     *            the search query (must not be null)
     * @param context
     *            resolved execution context (must not be null)
     * @return matching results with scores, never null, ordered by descending score
     */
    default List<WikiSearchResult> searchWithScores(WikiSearchQuery query, WikiSearchContext context) {
        final List<WikiPage> pages = search(query, context);
        final List<WikiSearchResult> wrapped = new ArrayList<>(pages.size());
        for (WikiPage page : pages) {
            wrapped.add(new WikiSearchResult(page, 0.0));
        }
        return wrapped;
    }
}
