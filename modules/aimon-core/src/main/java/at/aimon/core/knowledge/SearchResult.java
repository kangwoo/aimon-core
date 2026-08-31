package at.aimon.core.knowledge;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable search result from a {@link KnowledgeStore}.
 *
 * <p>
 * Each result represents a matched document chunk with a normalized relevance score. Scores are in the range [0.0, 1.0]
 * where higher values indicate greater relevance.
 *
 * @see KnowledgeStore#search(KnowledgeScope, SearchQuery)
 * @see SearchQuery
 */
public final class SearchResult {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String documentPath;
    private final String chunkContent;
    private final double score;
    private final int chunkIndex;
    private final Map<String, String> metadata;

    private SearchResult(Builder builder) {
        this.documentPath = Objects.requireNonNull(builder.documentPath, "documentPath must not be null");
        if (builder.documentPath.isEmpty()) {
            throw new IllegalArgumentException("documentPath must not be empty");
        }
        this.chunkContent = Objects.requireNonNull(builder.chunkContent, "chunkContent must not be null");
        if (builder.chunkContent.isEmpty()) {
            throw new IllegalArgumentException("chunkContent must not be empty");
        }
        if (builder.score < 0.0 || builder.score > 1.0) {
            throw new IllegalArgumentException("score must be in [0.0, 1.0], got: " + builder.score);
        }
        this.score = builder.score;
        if (builder.chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0, got: " + builder.chunkIndex);
        }
        this.chunkIndex = builder.chunkIndex;
        this.metadata = builder.metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(builder.metadata);
    }

    /**
     * Returns the VFS path of the matched document.
     *
     * @return the document path (never null or empty)
     */
    public String getDocumentPath() {
        return documentPath;
    }

    /**
     * Returns the text content of the matched chunk.
     *
     * @return the chunk content (never null or empty)
     */
    public String getChunkContent() {
        return chunkContent;
    }

    /**
     * Returns the normalized relevance score.
     *
     * @return the score in [0.0, 1.0]; higher is more relevant
     */
    public double getScore() {
        return score;
    }

    /**
     * Returns the zero-based index of the matched chunk within its document.
     *
     * @return the chunk index (>= 0)
     */
    public int getChunkIndex() {
        return chunkIndex;
    }

    /**
     * Returns additional metadata for this result.
     *
     * @return an unmodifiable metadata map (never null)
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "SearchResult{path='" + documentPath + "', score=" + String.format("%.2f", score) + ", chunk="
                + chunkIndex + '}';
    }

    /**
     * Builder for {@link SearchResult}.
     */
    public static final class Builder {
        private String documentPath;
        private String chunkContent;
        private double score;
        private int chunkIndex;
        private Map<String, String> metadata;

        private Builder() {
        }

        /**
         * Sets the document path.
         *
         * @param documentPath
         *            the VFS path of the matched document
         * @return this builder
         */
        public Builder documentPath(String documentPath) {
            this.documentPath = documentPath;
            return this;
        }

        /**
         * Sets the chunk content.
         *
         * @param chunkContent
         *            the text content of the matched chunk
         * @return this builder
         */
        public Builder chunkContent(String chunkContent) {
            this.chunkContent = chunkContent;
            return this;
        }

        /**
         * Sets the relevance score.
         *
         * @param score
         *            the normalized score in [0.0, 1.0]
         * @return this builder
         */
        public Builder score(double score) {
            this.score = score;
            return this;
        }

        /**
         * Sets the chunk index.
         *
         * @param chunkIndex
         *            the zero-based index within the document
         * @return this builder
         */
        public Builder chunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
            return this;
        }

        /**
         * Sets the metadata.
         *
         * @param metadata
         *            additional metadata
         * @return this builder
         */
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds the search result.
         *
         * @return a new {@link SearchResult} instance
         * @throws NullPointerException
         *             if documentPath or chunkContent is null
         * @throws IllegalArgumentException
         *             if values are out of valid range
         */
        public SearchResult build() {
            return new SearchResult(this);
        }
    }
}
