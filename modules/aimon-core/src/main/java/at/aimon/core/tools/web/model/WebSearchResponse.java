package at.aimon.core.tools.web.model;

import java.util.List;
import java.util.Objects;

/**
 * Response wrapper from a web search provider.
 *
 * <p>
 * Contains the provider name and an immutable list of search results.
 * Uses defensive copying to ensure immutability.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * WebSearchResponse response = WebSearchResponse.builder()
 *         .provider("brave")
 *         .results(resultList)
 *         .build();
 * }
 * </pre>
 */
public final class WebSearchResponse {

    private final String provider;
    private final List<WebSearchResult> results;

    private WebSearchResponse(Builder builder) {
        this.provider = Objects.requireNonNull(builder.provider, "provider cannot be null");
        this.results = builder.results != null ? List.copyOf(builder.results) : List.of();
    }

    /**
     * Creates a new builder.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getProvider() {
        return provider;
    }

    public List<WebSearchResult> getResults() {
        return results;
    }

    @Override
    public String toString() {
        return "WebSearchResponse{provider='" + provider + '\'' + ", results=" + results.size() + '}';
    }

    /**
     * Builder for WebSearchResponse.
     */
    public static final class Builder {

        private String provider;
        private List<WebSearchResult> results;

        private Builder() {
        }

        /**
         * Sets the provider name.
         *
         * @param provider
         *            the provider name (not null)
         * @return this builder
         */
        public Builder provider(String provider) {
            this.provider = Objects.requireNonNull(provider, "provider cannot be null");
            return this;
        }

        /**
         * Sets the search results.
         *
         * @param results
         *            the search results (not null, defensively copied)
         * @return this builder
         */
        public Builder results(List<WebSearchResult> results) {
            this.results = Objects.requireNonNull(results, "results cannot be null");
            return this;
        }

        /**
         * Builds the WebSearchResponse.
         *
         * @return a new WebSearchResponse
         */
        public WebSearchResponse build() {
            return new WebSearchResponse(this);
        }
    }
}
