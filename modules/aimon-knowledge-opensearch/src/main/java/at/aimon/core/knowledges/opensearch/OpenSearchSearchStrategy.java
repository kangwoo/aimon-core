package at.aimon.core.knowledges.opensearch;

import java.io.IOException;
import java.util.List;

import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;

/**
 * Strategy for executing searches against OpenSearch.
 *
 * <p>
 * Implementations provide different search algorithms (keyword, vector, hybrid) that map framework
 * {@link SearchQuery} objects to OpenSearch queries and convert the results back to {@link SearchResult} objects.
 *
 * <p>
 * Implementations must be thread-safe. Search operations may be called concurrently from multiple threads.
 *
 * @see KeywordSearchStrategy
 * @see VectorSearchStrategy
 * @see HybridSearchStrategy
 */
interface OpenSearchSearchStrategy {

    /**
     * Executes a search against the OpenSearch index.
     *
     * @param query
     *            the search query (must not be null)
     * @return a list of search results ranked by relevance (descending); never null
     * @throws IOException
     *             if the OpenSearch request fails
     */
    List<SearchResult> search(SearchQuery query) throws IOException;
}
