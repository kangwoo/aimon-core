package at.aimon.core.llms.openai;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for OpenAI Embeddings API client.
 *
 * <p>
 * Contains API key, model selection, and client settings for embedding generation.
 *
 * <p>
 * Thread-safe and immutable.
 *
 * <pre>{@code
 * OpenAIEmbeddingConfig config = OpenAIEmbeddingConfig.builder()
 *         .apiKey(System.getenv("OPENAI_API_KEY"))
 *         .model("text-embedding-3-small")
 *         .build();
 * }</pre>
 */
public final class OpenAIEmbeddingConfig {

    /** Default embedding model. */
    public static final String DEFAULT_MODEL = "text-embedding-3-small";

    /** Default vector dimensions for text-embedding-3-small. */
    public static final int DEFAULT_DIMENSIONS = 1536;

    /** Default request timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final Duration timeout;

    private OpenAIEmbeddingConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = Objects.requireNonNull(builder.apiKey, "apiKey must not be null");
        if (builder.apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.model = Objects.requireNonNull(builder.model, "model must not be null");
        if (builder.dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be >= 1, got: " + builder.dimensions);
        }
        this.dimensions = builder.dimensions;
        this.timeout = Objects.requireNonNull(builder.timeout, "timeout must not be null");
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public Duration getTimeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return "OpenAIEmbeddingConfig{model='" + model + "', dimensions=" + dimensions + '}';
    }

    /**
     * Builder for {@link OpenAIEmbeddingConfig}.
     */
    public static final class Builder {
        private String baseUrl;
        private String apiKey;
        private String model = DEFAULT_MODEL;
        private int dimensions = DEFAULT_DIMENSIONS;
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder() {
        }

        /** Sets the base URL for OpenAI API (null to use default). */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Sets the OpenAI API key. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Sets the embedding model name. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the output vector dimensions. */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /** Sets the request timeout. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Builds the config.
         *
         * @return a new {@link OpenAIEmbeddingConfig} instance
         */
        public OpenAIEmbeddingConfig build() {
            return new OpenAIEmbeddingConfig(this);
        }
    }
}
