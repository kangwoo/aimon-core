package at.aimon.core.knowledges.opensearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.KnnQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.WildcardQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;
import at.aimon.core.knowledge.embedding.EmbeddingClient;
import at.aimon.core.knowledge.embedding.EmbeddingResult;

/**
 * kNN vector similarity search strategy for OpenSearch.
 *
 * <p>
 * Converts the query text to an embedding vector using the configured {@link EmbeddingClient}, then performs a
 * k-nearest neighbors search on the {@code embedding} field. Scores are already normalized by OpenSearch kNN to
 * [0.0, 1.0].
 *
 * <p>
 * Automatically applies scope filtering via {@link ScopeFilter} to ensure multi-tenant data isolation.
 *
 * <p>
 * Thread-safe: this class holds no mutable state.
 */
final class VectorSearchStrategy implements OpenSearchSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchStrategy.class);

    private final OpenSearchClient client;
    private final String indexName;
    private final EmbeddingClient embeddingClient;
    private final ScopeFilter scopeFilter;

    VectorSearchStrategy(OpenSearchClient client, String indexName, EmbeddingClient embeddingClient,
            ScopeFilter scopeFilter) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.indexName = Objects.requireNonNull(indexName, "indexName must not be null");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient must not be null");
        this.scopeFilter = Objects.requireNonNull(scopeFilter, "scopeFilter must not be null");
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List<SearchResult> search(SearchQuery query) throws IOException {
        Objects.requireNonNull(query, "query must not be null");

        final EmbeddingResult embeddingResult = embeddingClient.embed(query.getQueryText());
        final float[] queryVector = embeddingResult.getVector();

        final KnnQuery.Builder knnBuilder = new KnnQuery.Builder().field(OpenSearchDocumentMapper.FIELD_EMBEDDING)
                .vector(queryVector).k(query.getMaxResults());

        // Apply combined scope + file pattern filter
        final BoolQuery.Builder filterBuilder = new BoolQuery.Builder();

        for (Query filter : scopeFilter.toFilterQueries(query.isCrossContext())) {
            filterBuilder.filter(filter);
        }

        if (query.getFilePatterns() != null && !query.getFilePatterns().isEmpty()) {
            for (String pattern : query.getFilePatterns()) {
                filterBuilder
                        .filter(new Query.Builder()
                                .wildcard(new WildcardQuery.Builder()
                                        .field(OpenSearchDocumentMapper.FIELD_DOCUMENT_PATH).value(pattern).build())
                                .build());
            }
        }

        knnBuilder.filter(new Query.Builder().bool(filterBuilder.build()).build());

        final SearchRequest request = new SearchRequest.Builder().index(indexName)
                .query(new Query.Builder().knn(knnBuilder.build()).build()).size(query.getMaxResults()).build();

        final SearchResponse<Map> response = client.search(request, Map.class);

        return convertHits(response, query.getMinScore());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<SearchResult> convertHits(SearchResponse<Map> response, double minScore) {
        final List<Hit<Map>> hits = response.hits().hits();
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }

        final List<SearchResult> results = new ArrayList<>();
        for (Hit<Map> hit : hits) {
            final double score = hit.score() != null ? hit.score() : 0.0;
            if (score < minScore) {
                continue;
            }
            if (hit.source() != null) {
                results.add(OpenSearchDocumentMapper.toSearchResult(hit.source(), Math.min(score, 1.0)));
            }
        }

        log.debug("Vector search returned {} results", results.size());
        return Collections.unmodifiableList(results);
    }
}
