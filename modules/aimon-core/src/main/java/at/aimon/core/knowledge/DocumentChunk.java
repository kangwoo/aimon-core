package at.aimon.core.knowledge;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A text chunk extracted from a document for indexing and search.
 *
 * <p>
 * Each chunk represents a meaningful segment of a document, identified by its source path and position within the
 * document. Chunks are the basic units of search: queries match against chunks, and search results return chunk
 * content.
 *
 * @see DocumentChunker
 * @see SearchResult
 */
public final class DocumentChunk {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String documentPath;
    private final String content;
    private final int chunkIndex;
    private final Map<String, String> metadata;

    private DocumentChunk(Builder builder) {
        this.documentPath = Objects.requireNonNull(builder.documentPath, "documentPath must not be null");
        if (builder.documentPath.isEmpty()) {
            throw new IllegalArgumentException("documentPath must not be empty");
        }
        this.content = Objects.requireNonNull(builder.content, "content must not be null");
        if (builder.content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        if (builder.chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0, got: " + builder.chunkIndex);
        }
        this.chunkIndex = builder.chunkIndex;
        this.metadata = builder.metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(builder.metadata);
    }

    /**
     * Returns the VFS path of the source document.
     *
     * @return the document path (never null or empty)
     */
    public String getDocumentPath() {
        return documentPath;
    }

    /**
     * Returns the text content of this chunk.
     *
     * @return the chunk content (never null or empty)
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the zero-based index of this chunk within its source document.
     *
     * @return the chunk index (>= 0)
     */
    public int getChunkIndex() {
        return chunkIndex;
    }

    /**
     * Returns additional metadata for this chunk.
     *
     * @return an unmodifiable metadata map (never null)
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DocumentChunk that)) {
            return false;
        }
        return chunkIndex == that.chunkIndex && documentPath.equals(that.documentPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentPath, chunkIndex);
    }

    @Override
    public String toString() {
        return "DocumentChunk{path='" + documentPath + "', index=" + chunkIndex + ", length=" + content.length() + '}';
    }

    /**
     * Builder for {@link DocumentChunk}.
     */
    public static final class Builder {
        private String documentPath;
        private String content;
        private int chunkIndex;
        private Map<String, String> metadata;

        private Builder() {
        }

        /**
         * Sets the document path.
         *
         * @param documentPath
         *            the VFS path of the source document
         * @return this builder
         */
        public Builder documentPath(String documentPath) {
            this.documentPath = documentPath;
            return this;
        }

        /**
         * Sets the chunk content.
         *
         * @param content
         *            the text content of this chunk
         * @return this builder
         */
        public Builder content(String content) {
            this.content = content;
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
         * Builds the document chunk.
         *
         * @return a new {@link DocumentChunk} instance
         * @throws NullPointerException
         *             if documentPath or content is null
         * @throws IllegalArgumentException
         *             if documentPath or content is empty, or chunkIndex is negative
         */
        public DocumentChunk build() {
            return new DocumentChunk(this);
        }
    }
}
