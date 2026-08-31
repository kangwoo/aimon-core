package at.aimon.core.knowledge.embedding;

import java.util.List;

/**
 * Client for generating text embeddings (vector representations).
 *
 * <p>
 * Implementations convert text into dense floating-point vectors suitable for similarity search. Typical backends
 * include OpenAI Embeddings API, Anthropic Voyage, or local models.
 *
 * <p>
 * Implementations must be thread-safe.
 *
 * <pre>{@code
 * EmbeddingClient client = new OpenAIEmbeddingClient(config);
 * EmbeddingResult result = client.embed("Kubernetes CrashLoopBackOff troubleshooting");
 * float[] vector = result.getVector(); // e.g., 1536-dimensional
 * }</pre>
 *
 * @see EmbeddingResult
 */
public interface EmbeddingClient {

    /**
     * Generates an embedding vector for the given text.
     *
     * @param text
     *            the input text (must not be null or empty)
     * @return the embedding result containing the vector and token usage
     * @throws NullPointerException
     *             if text is null
     * @throws IllegalArgumentException
     *             if text is empty
     * @throws EmbeddingException
     *             if the embedding operation fails
     */
    EmbeddingResult embed(String text);

    /**
     * Generates embedding vectors for multiple texts in a single batch.
     *
     * <p>
     * Batch embedding is more efficient than calling {@link #embed(String)} repeatedly, as it reduces the number of
     * API calls. However, implementations may enforce provider-specific limits on batch size and total tokens:
     * <ul>
     * <li>OpenAI: max ~100,000 tokens per request, no strict item limit
     * <li>Other providers may impose different limits
     * </ul>
     *
     * <p>
     * Callers should keep batch sizes reasonable (e.g., 50 items) to avoid hitting provider limits. The
     * {@code OpenSearchKnowledgeStore} automatically splits large batches into sub-batches.
     *
     * @param texts
     *            the input texts (must not be null or empty; each text must not be null or empty)
     * @return a list of embedding results in the same order as the input texts
     * @throws NullPointerException
     *             if texts is null or contains null elements
     * @throws IllegalArgumentException
     *             if texts is empty or contains empty strings
     * @throws EmbeddingException
     *             if the embedding operation fails
     */
    List<EmbeddingResult> embedBatch(List<String> texts);

    /**
     * Returns the number of dimensions in the embedding vectors produced by this client.
     *
     * @return the vector dimension count (> 0)
     */
    int getDimensions();
}
