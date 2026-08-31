package at.aimon.core.agent.tool.search;

import java.util.List;

/**
 * Strategy interface for searching tools by query.
 *
 * <p>
 * Implementations define different search algorithms (keyword matching, BM25, embedding-based, etc.) while keeping the
 * search contract consistent.
 *
 * <p>
 * Implementations must be stateless and thread-safe, as a single strategy instance is shared across multiple
 * sessions.
 *
 * @see KeywordToolSearchStrategy
 * @see RequiredKeywordToolSearchStrategy
 */
public interface ToolSearchStrategy {

    /**
     * Searches the given candidates for tools matching the query.
     *
     * @param query
     *            the search query (must not be null)
     * @param candidates
     *            the list of candidate tools to search (must not be null)
     * @param maxResults
     *            the maximum number of results to return (must be &ge; 1)
     * @return a list of matching tools ordered by relevance (descending), never null, may be empty
     * @throws NullPointerException
     *             if {@code query} or {@code candidates} is null
     */
    List<SearchableTool> search(String query, List<SearchableTool> candidates, int maxResults);

}
