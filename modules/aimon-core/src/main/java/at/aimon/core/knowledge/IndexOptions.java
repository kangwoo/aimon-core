package at.aimon.core.knowledge;

import java.util.Collections;
import java.util.List;

/**
 * Immutable options for document indexing in a {@link KnowledgeStore}.
 *
 * <p>
 * Controls which files to index and sets resource limits to prevent memory exhaustion.
 *
 * <pre>{@code
 * IndexOptions options = IndexOptions.builder()
 *         .filePatterns(List.of("*.md", "*.txt"))
 *         .recursive(true)
 *         .maxDocuments(500)
 *         .build();
 * }</pre>
 *
 * @see KnowledgeStore#index(KnowledgeScope, KnowledgeSource, IndexOptions)
 */
public final class IndexOptions {

    /** Default file patterns for indexing. */
    public static final List<String> DEFAULT_FILE_PATTERNS = List.of("*.md", "*.txt");

    /** Default maximum number of documents to index. */
    public static final int DEFAULT_MAX_DOCUMENTS = 1000;

    /** Default maximum chunk size in characters. */
    public static final int DEFAULT_MAX_CHUNK_SIZE = 2000;

    /**
     * Returns a default {@link IndexOptions} instance.
     *
     * @return default options
     */
    public static IndexOptions defaults() {
        return new Builder().build();
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final List<String> filePatterns;
    private final boolean recursive;
    private final int maxDocuments;
    private final int maxChunkSize;

    private IndexOptions(Builder builder) {
        if (builder.maxDocuments < 1) {
            throw new IllegalArgumentException("maxDocuments must be >= 1, got: " + builder.maxDocuments);
        }
        if (builder.maxChunkSize < 1) {
            throw new IllegalArgumentException("maxChunkSize must be >= 1, got: " + builder.maxChunkSize);
        }
        this.filePatterns = builder.filePatterns == null
                ? DEFAULT_FILE_PATTERNS
                : Collections.unmodifiableList(builder.filePatterns);
        this.recursive = builder.recursive;
        this.maxDocuments = builder.maxDocuments;
        this.maxChunkSize = builder.maxChunkSize;
    }

    /**
     * Returns the file glob patterns for indexing.
     *
     * @return an unmodifiable list of patterns (never null)
     */
    public List<String> getFilePatterns() {
        return filePatterns;
    }

    /**
     * Returns whether subdirectories should be included.
     *
     * @return {@code true} if recursive indexing is enabled
     */
    public boolean isRecursive() {
        return recursive;
    }

    /**
     * Returns the maximum number of documents to index.
     *
     * @return the max documents (>= 1)
     */
    public int getMaxDocuments() {
        return maxDocuments;
    }

    /**
     * Returns the maximum chunk size in characters.
     *
     * @return the max chunk size (>= 1)
     */
    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    @Override
    public String toString() {
        return "IndexOptions{patterns=" + filePatterns + ", recursive=" + recursive + ", maxDocs=" + maxDocuments
                + ", maxChunkSize=" + maxChunkSize + '}';
    }

    /**
     * Builder for {@link IndexOptions}.
     */
    public static final class Builder {
        private List<String> filePatterns;
        private boolean recursive = true;
        private int maxDocuments = DEFAULT_MAX_DOCUMENTS;
        private int maxChunkSize = DEFAULT_MAX_CHUNK_SIZE;

        private Builder() {
        }

        /**
         * Sets the file patterns for indexing.
         *
         * @param filePatterns
         *            glob patterns to match files
         * @return this builder
         */
        public Builder filePatterns(List<String> filePatterns) {
            this.filePatterns = filePatterns;
            return this;
        }

        /**
         * Sets whether to include subdirectories.
         *
         * @param recursive
         *            {@code true} to recurse into subdirectories
         * @return this builder
         */
        public Builder recursive(boolean recursive) {
            this.recursive = recursive;
            return this;
        }

        /**
         * Sets the maximum number of documents to index.
         *
         * @param maxDocuments
         *            the limit (must be >= 1)
         * @return this builder
         */
        public Builder maxDocuments(int maxDocuments) {
            this.maxDocuments = maxDocuments;
            return this;
        }

        /**
         * Sets the maximum chunk size in characters.
         *
         * @param maxChunkSize
         *            the limit (must be >= 1)
         * @return this builder
         */
        public Builder maxChunkSize(int maxChunkSize) {
            this.maxChunkSize = maxChunkSize;
            return this;
        }

        /**
         * Builds the index options.
         *
         * @return a new {@link IndexOptions} instance
         * @throws IllegalArgumentException
         *             if maxDocuments or maxChunkSize &lt; 1
         */
        public IndexOptions build() {
            return new IndexOptions(this);
        }
    }
}
