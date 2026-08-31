package at.aimon.core.knowledge;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable search request against a {@link KnowledgeStore}.
 *
 * <p>
 * Use the {@link #builder()} to construct instances:
 *
 * <pre>{@code
 * SearchQuery query = SearchQuery.builder()
 *         .queryText("CrashLoopBackOff troubleshooting")
 *         .maxResults(5)
 *         .build();
 * }</pre>
 *
 * @see KnowledgeStore#search(KnowledgeScope, SearchQuery)
 * @see SearchResult
 */
public final class SearchQuery {

    /** Default maximum number of results. */
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** Default minimum relevance score. */
    public static final double DEFAULT_MIN_SCORE = 0.0;

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String queryText;
    private final int maxResults;
    private final double minScore;
    private final List<String> filePatterns;
    private final Map<String, String> metadata;
    private final boolean crossContext;

    private SearchQuery(Builder builder) {
        this.queryText = Objects.requireNonNull(builder.queryText, "queryText must not be null");
        if (builder.queryText.isEmpty()) {
            throw new IllegalArgumentException("queryText must not be empty");
        }
        if (builder.maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1, got: " + builder.maxResults);
        }
        this.maxResults = builder.maxResults;
        if (builder.minScore < 0.0 || builder.minScore > 1.0) {
            throw new IllegalArgumentException("minScore must be in [0.0, 1.0], got: " + builder.minScore);
        }
        this.minScore = builder.minScore;
        this.filePatterns = builder.filePatterns == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(builder.filePatterns);
        this.metadata = builder.metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(builder.metadata);
        this.crossContext = builder.crossContext;
    }

    /**
     * Returns the search query text.
     *
     * @return the query text (never null or empty)
     */
    public String getQueryText() {
        return queryText;
    }

    /**
     * Returns the maximum number of results to return.
     *
     * @return the max results (>= 1)
     */
    public int getMaxResults() {
        return maxResults;
    }

    /**
     * Returns the minimum relevance score threshold.
     *
     * @return the minimum score in [0.0, 1.0]
     */
    public double getMinScore() {
        return minScore;
    }

    /**
     * Returns the file glob patterns for filtering results.
     *
     * @return an unmodifiable list of file patterns (never null)
     */
    public List<String> getFilePatterns() {
        return filePatterns;
    }

    /**
     * Returns the metadata filters.
     *
     * @return an unmodifiable metadata map (never null)
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Returns whether to search across all contexts of the same agent.
     *
     * <p>
     * When {@code true}, scope-aware implementations (e.g., OpenSearch) filter by agent name only, ignoring the
     * context ID. This allows retrieving knowledge indexed by other agent runtimes of the same agent.
     *
     * @return {@code true} if cross-context search is enabled
     */
    public boolean isCrossContext() {
        return crossContext;
    }

    @Override
    public String toString() {
        return "SearchQuery{query='" + queryText + "', maxResults=" + maxResults + ", minScore=" + minScore + '}';
    }

    /**
     * Builder for {@link SearchQuery}.
     */
    public static final class Builder {
        private String queryText;
        private int maxResults = DEFAULT_MAX_RESULTS;
        private double minScore = DEFAULT_MIN_SCORE;
        private List<String> filePatterns;
        private Map<String, String> metadata;
        private boolean crossContext;

        private Builder() {
        }

        /**
         * Sets the search query text.
         *
         * @param queryText
         *            the query text (must not be null or empty)
         * @return this builder
         */
        public Builder queryText(String queryText) {
            this.queryText = queryText;
            return this;
        }

        /**
         * Sets the maximum number of results.
         *
         * @param maxResults
         *            the max results (must be >= 1)
         * @return this builder
         */
        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        /**
         * Sets the minimum relevance score threshold.
         *
         * @param minScore
         *            the minimum score in [0.0, 1.0]
         * @return this builder
         */
        public Builder minScore(double minScore) {
            this.minScore = minScore;
            return this;
        }

        /**
         * Sets the file patterns for filtering.
         *
         * @param filePatterns
         *            glob patterns to filter documents
         * @return this builder
         */
        public Builder filePatterns(List<String> filePatterns) {
            this.filePatterns = filePatterns;
            return this;
        }

        /**
         * Sets the metadata filters.
         *
         * @param metadata
         *            metadata key-value filters
         * @return this builder
         */
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Enables cross-context search. When true, scope-aware stores search across all contexts of the same agent.
         *
         * @param crossContext
         *            true to enable cross-context search
         * @return this builder
         */
        public Builder crossContext(boolean crossContext) {
            this.crossContext = crossContext;
            return this;
        }

        /**
         * Builds the search query.
         *
         * @return a new {@link SearchQuery} instance
         * @throws NullPointerException
         *             if queryText is null
         * @throws IllegalArgumentException
         *             if queryText is empty, maxResults &lt; 1, or minScore out of range
         */
        public SearchQuery build() {
            return new SearchQuery(this);
        }
    }
}
