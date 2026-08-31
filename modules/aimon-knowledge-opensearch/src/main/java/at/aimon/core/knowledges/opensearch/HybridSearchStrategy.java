package at.aimon.core.knowledges.opensearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;
import at.aimon.core.knowledge.embedding.EmbeddingClient;

/**
 * Hybrid search strategy combining BM25 keyword search and kNN vector search.
 *
 * <p>
 * Executes both keyword and vector searches, then merges results using weighted score combination. Documents appearing
 * in both result sets receive combined scores; documents in only one set receive the single score multiplied by its
 * weight.
 *
 * <p>
 * If one strategy fails with a transient error, the other's results are returned alone. If both fail, an
 * {@link IOException} is thrown.
 *
 * <p>
 * The final scores are normalized to [0.0, 1.0] by dividing by the maximum combined score.
 *
 * <p>
 * Thread-safe: this class holds no mutable state.
 */
final class HybridSearchStrategy implements OpenSearchSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchStrategy.class);

    private final KeywordSearchStrategy keywordStrategy;
    private final VectorSearchStrategy vectorStrategy;
    private final float keywordWeight;
    private final float vectorWeight;

    HybridSearchStrategy(org.opensearch.client.opensearch.OpenSearchClient client, OpenSearchConfig config,
            EmbeddingClient embeddingClient, ScopeFilter scopeFilter) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(embeddingClient, "embeddingClient must not be null");
        Objects.requireNonNull(scopeFilter, "scopeFilter must not be null");

        this.keywordStrategy = new KeywordSearchStrategy(client, config.getIndexName(), scopeFilter);
        this.vectorStrategy = new VectorSearchStrategy(client, config.getIndexName(), embeddingClient, scopeFilter);
        this.keywordWeight = config.getKeywordWeight();
        this.vectorWeight = config.getVectorWeight();
    }

    @Override
    public List<SearchResult> search(SearchQuery query) throws IOException {
        Objects.requireNonNull(query, "query must not be null");

        // Use a small overlap buffer instead of 2x expansion to reduce wasted work
        final int expandedMaxResults = Math.max(query.getMaxResults(), 20);
        final SearchQuery expandedQuery = SearchQuery.builder().queryText(query.getQueryText())
                .maxResults(expandedMaxResults).minScore(0.0).filePatterns(query.getFilePatterns())
                .metadata(query.getMetadata()).build();

        // Execute both strategies with fallback for transient failures
        List<SearchResult> keywordResults = null;
        IOException keywordError = null;
        try {
            keywordResults = keywordStrategy.search(expandedQuery);
        } catch (IOException e) {
            keywordError = e;
            log.warn("Keyword search failed in hybrid mode: {}", e.getMessage());
        }

        List<SearchResult> vectorResults = null;
        IOException vectorError = null;
        try {
            vectorResults = vectorStrategy.search(expandedQuery);
        } catch (IOException e) {
            vectorError = e;
            log.warn("Vector search failed in hybrid mode: {}", e.getMessage());
        }

        // If both strategies failed, propagate the first error
        if (keywordResults == null && vectorResults == null) {
            throw new IOException("Both keyword and vector search failed in hybrid mode",
                    keywordError != null ? keywordError : vectorError);
        }

        final List<SearchResult> safeKeyword = keywordResults != null ? keywordResults : Collections.emptyList();
        final List<SearchResult> safeVector = vectorResults != null ? vectorResults : Collections.emptyList();

        final List<SearchResult> merged = mergeResults(safeKeyword, safeVector, query.getMaxResults(),
                query.getMinScore());

        log.debug("Hybrid search: {} keyword + {} vector = {} merged results", safeKeyword.size(), safeVector.size(),
                merged.size());

        return merged;
    }

    /**
     * Merges keyword and vector results using weighted score combination and normalization.
     */
    private List<SearchResult> mergeResults(List<SearchResult> keywordResults, List<SearchResult> vectorResults,
            int maxResults, double minScore) {

        final Map<String, double[]> scoreMap = new HashMap<>();
        final Map<String, SearchResult> resultMap = new HashMap<>();

        for (SearchResult result : keywordResults) {
            final String key = resultKey(result);
            scoreMap.computeIfAbsent(key, k -> new double[2])[0] = result.getScore();
            resultMap.putIfAbsent(key, result);
        }

        for (SearchResult result : vectorResults) {
            final String key = resultKey(result);
            scoreMap.computeIfAbsent(key, k -> new double[2])[1] = result.getScore();
            resultMap.putIfAbsent(key, result);
        }

        // Compute combined scores and find max in a single pass
        double maxCombined = 0.0;
        final Map<String, Double> combinedScores = new HashMap<>(scoreMap.size());
        for (Map.Entry<String, double[]> entry : scoreMap.entrySet()) {
            final double combined = entry.getValue()[0] * keywordWeight + entry.getValue()[1] * vectorWeight;
            combinedScores.put(entry.getKey(), combined);
            if (combined > maxCombined) {
                maxCombined = combined;
            }
        }

        if (maxCombined == 0.0) {
            return Collections.emptyList();
        }

        // Normalize and build final results
        final List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, Double> entry : combinedScores.entrySet()) {
            final double normalizedScore = entry.getValue() / maxCombined;
            if (normalizedScore < minScore) {
                continue;
            }
            final SearchResult original = resultMap.get(entry.getKey());
            results.add(SearchResult.builder().documentPath(original.getDocumentPath())
                    .chunkContent(original.getChunkContent()).score(normalizedScore)
                    .chunkIndex(original.getChunkIndex()).metadata(original.getMetadata()).build());
        }

        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        if (results.size() > maxResults) {
            return Collections.unmodifiableList(results.subList(0, maxResults));
        }
        return Collections.unmodifiableList(results);
    }

    private static String resultKey(SearchResult result) {
        return result.getDocumentPath() + ":" + result.getChunkIndex();
    }
}
