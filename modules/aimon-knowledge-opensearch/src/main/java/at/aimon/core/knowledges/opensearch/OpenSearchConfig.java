package at.aimon.core.knowledges.opensearch;

import java.util.Objects;

/**
 * Immutable configuration for connecting to an OpenSearch cluster.
 *
 * <pre>{@code
 * OpenSearchConfig config = OpenSearchConfig.builder()
 *         .host("localhost")
 *         .port(9200)
 *         .indexName("knowledge")
 *         .searchMode(SearchMode.HYBRID)
 *         .build();
 * }</pre>
 *
 * @see OpenSearchKnowledgeStore
 */
public final class OpenSearchConfig {

    /** Default OpenSearch port. */
    public static final int DEFAULT_PORT = 9200;

    /** Default URL scheme. */
    public static final String DEFAULT_SCHEME = "https";

    /** Default index name. */
    public static final String DEFAULT_INDEX_NAME = "aimon-knowledge";

    /** Default search mode. */
    public static final SearchMode DEFAULT_SEARCH_MODE = SearchMode.KEYWORD;

    /**
     * Default vector dimensions. Matches OpenAI text-embedding-3-small (1536). Other embedding providers may require
     * different dimensions — always set this explicitly when using a non-OpenAI embedding model.
     */
    public static final int DEFAULT_VECTOR_DIMENSIONS = 1536;

    /** Default keyword weight for hybrid search. */
    public static final float DEFAULT_KEYWORD_WEIGHT = 0.3f;

    /** Default vector weight for hybrid search. */
    public static final float DEFAULT_VECTOR_WEIGHT = 0.7f;

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String host;
    private final int port;
    private final String scheme;
    private final String indexName;
    private final String username;
    private final String password;
    private final SearchMode searchMode;
    private final int vectorDimensions;
    private final float keywordWeight;
    private final float vectorWeight;

    private OpenSearchConfig(Builder builder) {
        this.host = Objects.requireNonNull(builder.host, "host must not be null");
        if (builder.host.isEmpty()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (builder.port < 1 || builder.port > 65535) {
            throw new IllegalArgumentException("port must be in [1, 65535], got: " + builder.port);
        }
        this.port = builder.port;
        this.scheme = Objects.requireNonNull(builder.scheme, "scheme must not be null");
        this.indexName = Objects.requireNonNull(builder.indexName, "indexName must not be null");
        if (builder.indexName.isEmpty()) {
            throw new IllegalArgumentException("indexName must not be empty");
        }
        this.username = builder.username;
        this.password = builder.password;
        this.searchMode = Objects.requireNonNull(builder.searchMode, "searchMode must not be null");
        if (builder.vectorDimensions < 1) {
            throw new IllegalArgumentException("vectorDimensions must be >= 1, got: " + builder.vectorDimensions);
        }
        this.vectorDimensions = builder.vectorDimensions;
        if (builder.keywordWeight < 0.0f || builder.keywordWeight > 1.0f) {
            throw new IllegalArgumentException("keywordWeight must be in [0.0, 1.0], got: " + builder.keywordWeight);
        }
        if (builder.vectorWeight < 0.0f || builder.vectorWeight > 1.0f) {
            throw new IllegalArgumentException("vectorWeight must be in [0.0, 1.0], got: " + builder.vectorWeight);
        }
        final float weightSum = builder.keywordWeight + builder.vectorWeight;
        if (Math.abs(weightSum - 1.0f) > 0.001f) {
            throw new IllegalArgumentException("keywordWeight + vectorWeight must equal 1.0, got: " + weightSum);
        }
        this.keywordWeight = builder.keywordWeight;
        this.vectorWeight = builder.vectorWeight;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getScheme() {
        return scheme;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public SearchMode getSearchMode() {
        return searchMode;
    }

    public int getVectorDimensions() {
        return vectorDimensions;
    }

    public float getKeywordWeight() {
        return keywordWeight;
    }

    public float getVectorWeight() {
        return vectorWeight;
    }

    /**
     * Returns whether authentication credentials are configured.
     *
     * @return true if both username and password are set
     */
    public boolean hasCredentials() {
        return username != null && !username.isEmpty() && password != null && !password.isEmpty();
    }

    /**
     * Returns whether vector search capabilities are required.
     *
     * @return true if search mode is VECTOR or HYBRID
     */
    public boolean requiresEmbedding() {
        return searchMode == SearchMode.VECTOR || searchMode == SearchMode.HYBRID;
    }

    @Override
    public String toString() {
        return "OpenSearchConfig{host='" + host + "', port=" + port + ", index='" + indexName + "', authenticated="
                + hasCredentials() + ", mode=" + searchMode + '}';
    }

    /**
     * Builder for {@link OpenSearchConfig}.
     */
    public static final class Builder {
        private String host;
        private int port = DEFAULT_PORT;
        private String scheme = DEFAULT_SCHEME;
        private String indexName = DEFAULT_INDEX_NAME;
        private String username;
        private String password;
        private SearchMode searchMode = DEFAULT_SEARCH_MODE;
        private int vectorDimensions = DEFAULT_VECTOR_DIMENSIONS;
        private float keywordWeight = DEFAULT_KEYWORD_WEIGHT;
        private float vectorWeight = DEFAULT_VECTOR_WEIGHT;

        private Builder() {
        }

        /** Sets the OpenSearch host. */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /** Sets the OpenSearch port. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Sets the URL scheme (http or https). */
        public Builder scheme(String scheme) {
            this.scheme = scheme;
            return this;
        }

        /** Sets the index name. */
        public Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        /** Sets the authentication username. */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /** Sets the authentication password. */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /** Sets the search mode. */
        public Builder searchMode(SearchMode searchMode) {
            this.searchMode = searchMode;
            return this;
        }

        /** Sets the vector dimensions for kNN index. */
        public Builder vectorDimensions(int vectorDimensions) {
            this.vectorDimensions = vectorDimensions;
            return this;
        }

        /** Sets the keyword weight for hybrid search (0.0 to 1.0). */
        public Builder keywordWeight(float keywordWeight) {
            this.keywordWeight = keywordWeight;
            return this;
        }

        /** Sets the vector weight for hybrid search (0.0 to 1.0). */
        public Builder vectorWeight(float vectorWeight) {
            this.vectorWeight = vectorWeight;
            return this;
        }

        /**
         * Builds the config.
         *
         * @return a new {@link OpenSearchConfig} instance
         */
        public OpenSearchConfig build() {
            return new OpenSearchConfig(this);
        }
    }
}
