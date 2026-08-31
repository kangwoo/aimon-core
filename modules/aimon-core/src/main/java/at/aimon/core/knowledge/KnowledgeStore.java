package at.aimon.core.knowledge;

import java.util.List;

import at.aimon.core.base.ApplicationScoped;

/**
 * Provides document indexing and search capabilities for agent knowledge bases.
 *
 * <p>
 * A knowledge store manages a searchable index of documents. Documents are read from a {@link KnowledgeSource}, split
 * into chunks by a {@link DocumentChunker}, and indexed for efficient retrieval. Search queries return ranked chunks
 * with normalized relevance scores in [0.0, 1.0].
 *
 * <p>
 * Multi-tenant isolation is achieved via {@link KnowledgeScope}. Each indexed document is tagged with an agent name and
 * context ID. During search, the scope is used as a mandatory filter to ensure data isolation between agents and
 * contexts. Use {@link SearchQuery#isCrossContext()} to search across all contexts of the same agent.
 *
 * <p>
 * Implementations must ensure:
 * <ul>
 * <li>{@link #search(KnowledgeScope, SearchQuery)} is safe for concurrent calls
 * <li>Score normalization to [0.0, 1.0] range (higher = more relevant)
 * <li>{@link #close()} is idempotent
 * </ul>
 *
 * <p>
 * Lifecycle: typically created at the application level and shared across agent runtimes. Each context
 * provides its own {@link KnowledgeScope} when calling index/search methods.
 *
 * @see KeywordKnowledgeStore
 * @see KnowledgeScope
 * @see KnowledgeSource
 * @see SearchQuery
 * @see SearchResult
 */
public interface KnowledgeStore extends ApplicationScoped, AutoCloseable {

    /**
     * Indexes documents from the given source, tagged with the given scope.
     *
     * <p>
     * Reads files from the source's file system and directory, splits them into chunks, and builds a search index. All
     * indexed documents are tagged with the scope's agent name and context ID for multi-tenant isolation.
     *
     * @param scope
     *            the knowledge scope for document tagging (must not be null)
     * @param source
     *            the document source (file system + directory) to index from (must not be null)
     * @param options
     *            indexing options (must not be null)
     * @return the indexing result with statistics
     * @throws NullPointerException
     *             if any parameter is null
     */
    IndexResult index(KnowledgeScope scope, KnowledgeSource source, IndexOptions options);

    /**
     * Deletes the existing indexed documents for the given scope and re-indexes from the source.
     *
     * @param scope
     *            the knowledge scope for document tagging (must not be null)
     * @param source
     *            the document source to re-index from (must not be null)
     * @param options
     *            indexing options (must not be null)
     * @return the indexing result with statistics
     * @throws NullPointerException
     *             if any parameter is null
     */
    IndexResult reindex(KnowledgeScope scope, KnowledgeSource source, IndexOptions options);

    /**
     * Searches the indexed documents within the given scope.
     *
     * <p>
     * Returns chunks ranked by relevance. If no results match, returns an empty list. This method must be safe for
     * concurrent calls.
     *
     * <p>
     * By default, only documents matching both the scope's agent name and context ID are returned. Set
     * {@link SearchQuery#isCrossContext()} to {@code true} to search across all contexts of the same agent.
     *
     * @param scope
     *            the knowledge scope for filtering (must not be null)
     * @param query
     *            the search query (must not be null)
     * @return a list of search results ranked by score (descending); never null
     * @throws NullPointerException
     *             if any parameter is null
     */
    List<SearchResult> search(KnowledgeScope scope, SearchQuery query);

    /**
     * Returns the current status of the index.
     *
     * @return the index status (never null)
     */
    IndexStatus getStatus();

    /**
     * Releases index resources. Idempotent.
     */
    @Override
    void close();
}
