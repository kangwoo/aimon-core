package at.aimon.core.knowledges.opensearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.WildcardQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;

/**
 * BM25 keyword-based search strategy for OpenSearch.
 *
 * <p>
 * Uses OpenSearch's default BM25 text matching on the {@code chunk_content} field. Scores are normalized to [0.0, 1.0]
 * by dividing each score by the maximum score in the result set (single-pass).
 *
 * <p>
 * Automatically applies scope filtering via {@link ScopeFilter} to ensure multi-tenant data isolation.
 *
 * <p>
 * Thread-safe: this class holds no mutable state.
 */
final class KeywordSearchStrategy implements OpenSearchSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchStrategy.class);

    private final OpenSearchClient client;
    private final String indexName;
    private final ScopeFilter scopeFilter;

    KeywordSearchStrategy(OpenSearchClient client, String indexName, ScopeFilter scopeFilter) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.indexName = Objects.requireNonNull(indexName, "indexName must not be null");
        this.scopeFilter = Objects.requireNonNull(scopeFilter, "scopeFilter must not be null");
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List<SearchResult> search(SearchQuery query) throws IOException {
        Objects.requireNonNull(query, "query must not be null");

        final Query matchQuery = new Query.Builder()
                .match(new MatchQuery.Builder().field(OpenSearchDocumentMapper.FIELD_CHUNK_CONTENT)
                        .query(b -> b.stringValue(query.getQueryText())).build())
                .build();

        final Query finalQuery = applyFilters(matchQuery, query);

        final SearchRequest request = new SearchRequest.Builder().index(indexName).query(finalQuery)
                .size(query.getMaxResults()).build();

        final SearchResponse<Map> response = client.search(request, Map.class);

        return convertHits(response, query.getMinScore());
    }

    private Query applyFilters(Query baseQuery, SearchQuery query) {
        final BoolQuery.Builder boolBuilder = new BoolQuery.Builder().must(baseQuery);

        // Apply scope filters (agent_name, context_id)
        for (Query filter : scopeFilter.toFilterQueries(query.isCrossContext())) {
            boolBuilder.filter(filter);
        }

        // Apply file pattern filters
        if (query.getFilePatterns() != null && !query.getFilePatterns().isEmpty()) {
            for (String pattern : query.getFilePatterns()) {
                boolBuilder
                        .filter(new Query.Builder()
                                .wildcard(new WildcardQuery.Builder()
                                        .field(OpenSearchDocumentMapper.FIELD_DOCUMENT_PATH).value(pattern).build())
                                .build());
            }
        }

        return new Query.Builder().bool(boolBuilder.build()).build();
    }

    /**
     * Converts OpenSearch hits to SearchResults with single-pass score normalization. The first hit has the highest
     * score (OpenSearch returns results sorted by score descending), so it is used as the normalization denominator.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<SearchResult> convertHits(SearchResponse<Map> response, double minScore) {
        final List<Hit<Map>> hits = response.hits().hits();
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }

        final Double firstScore = hits.get(0).score();
        final double maxScore = (firstScore != null && firstScore > 0.0) ? firstScore : 1.0;

        final List<SearchResult> results = new ArrayList<>();
        for (Hit<Map> hit : hits) {
            final double rawScore = hit.score() != null ? hit.score() : 0.0;
            final double normalizedScore = rawScore / maxScore;

            if (normalizedScore < minScore) {
                continue;
            }

            if (hit.source() != null) {
                results.add(OpenSearchDocumentMapper.toSearchResult(hit.source(), normalizedScore));
            }
        }

        log.debug("Keyword search returned {} results", results.size());
        return Collections.unmodifiableList(results);
    }
}
