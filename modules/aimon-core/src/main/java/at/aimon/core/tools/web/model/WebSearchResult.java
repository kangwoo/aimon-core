package at.aimon.core.tools.web.model;

import java.util.Objects;

/**
 * An individual web search result item.
 *
 * <p>
 * Contains the title, URL, and optional snippet of a search result.
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * WebSearchResult result = WebSearchResult.builder()
 *         .title("Example Page")
 *         .url("https://example.com")
 *         .snippet("A sample page")
 *         .build();
 * }
 * </pre>
 */
public final class WebSearchResult {

    private final String title;
    private final String url;
    private final String snippet;

    private WebSearchResult(Builder builder) {
        this.title = Objects.requireNonNull(builder.title, "title cannot be null");
        this.url = builder.url;
        this.snippet = builder.snippet;
    }

    /**
     * Creates a new builder.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getTitle() {
        return title;
    }

    /**
     * Gets the result URL.
     *
     * @return the URL, or null if not available (e.g. Perplexity summarized answers)
     */
    public String getUrl() {
        return url;
    }

    public String getSnippet() {
        return snippet;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WebSearchResult that = (WebSearchResult) o;
        return Objects.equals(title, that.title) && Objects.equals(url, that.url)
                && Objects.equals(snippet, that.snippet);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, url, snippet);
    }

    @Override
    public String toString() {
        return "WebSearchResult{title='" + title + '\'' + ", url='" + url + '\'' + ", snippet='" + snippet + '\'' + '}';
    }

    /**
     * Builder for WebSearchResult.
     */
    public static final class Builder {

        private String title;
        private String url;
        private String snippet;

        private Builder() {
        }

        /**
         * Sets the result title.
         *
         * @param title
         *            the title (not null)
         * @return this builder
         */
        public Builder title(String title) {
            this.title = Objects.requireNonNull(title, "title cannot be null");
            return this;
        }

        /**
         * Sets the result URL.
         *
         * @param url
         *            the URL (nullable for results without a direct URL)
         * @return this builder
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Sets the result snippet.
         *
         * @param snippet
         *            the snippet (nullable)
         * @return this builder
         */
        public Builder snippet(String snippet) {
            this.snippet = snippet;
            return this;
        }

        /**
         * Builds the WebSearchResult.
         *
         * @return a new WebSearchResult
         */
        public WebSearchResult build() {
            return new WebSearchResult(this);
        }
    }
}
