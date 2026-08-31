package at.aimon.core.knowledges.opensearch;

/**
 * Search mode for OpenSearch knowledge store queries.
 *
 * <p>
 * Determines how documents are matched and ranked:
 * <ul>
 * <li>{@link #KEYWORD} — BM25 text search (no embedding client required)
 * <li>{@link #VECTOR} — kNN vector similarity search (requires embedding client)
 * <li>{@link #HYBRID} — combines BM25 and kNN with score normalization (requires embedding client)
 * </ul>
 */
public enum SearchMode {

    /** BM25-based keyword search. Does not require an embedding client. */
    KEYWORD,

    /** kNN vector similarity search. Requires an {@link at.aimon.core.knowledge.embedding.EmbeddingClient}. */
    VECTOR,

    /**
     * Hybrid search combining BM25 and kNN scores. Requires an
     * {@link at.aimon.core.knowledge.embedding.EmbeddingClient}.
     */
    HYBRID
}
