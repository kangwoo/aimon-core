package at.aimon.core.tools.web.fetch;

import java.util.Objects;

/**
 * HTTP fetch result.
 *
 * <p>
 * Immutable value object returned by {@link HttpContentFetcher} containing
 * the response body, HTTP status code, and content type.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * FetchResult result = FetchResult.builder()
 *         .body(responseBody)
 *         .statusCode(200)
 *         .contentType("text/html")
 *         .build();
 * }
 * </pre>
 */
public final class FetchResult {

    private final String body;
    private final int statusCode;
    private final String contentType;

    private FetchResult(Builder builder) {
        this.body = builder.body != null ? builder.body : "";
        this.statusCode = builder.statusCode;
        this.contentType = builder.contentType != null ? builder.contentType : "";
    }

    /**
     * Creates a new builder.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getBody() {
        return body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FetchResult other)) {
            return false;
        }
        return statusCode == other.statusCode && Objects.equals(contentType, other.contentType)
                && Objects.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(statusCode, contentType);
    }

    @Override
    public String toString() {
        return "FetchResult{statusCode=" + statusCode + ", contentType='" + contentType + '\'' + ", bodyLength="
                + body.length() + '}';
    }

    /**
     * Builder for FetchResult.
     */
    public static final class Builder {

        private String body;
        private int statusCode;
        private String contentType;

        private Builder() {
        }

        /**
         * Sets the response body.
         *
         * @param body
         *            the response body (null treated as empty string)
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * Sets the HTTP status code.
         *
         * @param statusCode
         *            the HTTP status code
         * @return this builder
         */
        public Builder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        /**
         * Sets the Content-Type header value.
         *
         * @param contentType
         *            the content type (null treated as empty string)
         * @return this builder
         */
        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        /**
         * Builds the FetchResult.
         *
         * @return a new FetchResult
         */
        public FetchResult build() {
            return new FetchResult(this);
        }
    }
}
