package at.aimon.core.knowledge;

import java.util.Collections;
import java.util.List;

/**
 * Immutable result of a document indexing operation.
 *
 * @see KnowledgeStore#index(KnowledgeScope, KnowledgeSource, IndexOptions)
 */
public final class IndexResult {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final int indexedDocumentCount;
    private final int indexedChunkCount;
    private final int skippedDocumentCount;
    private final long durationMs;
    private final List<String> errors;

    private IndexResult(Builder builder) {
        if (builder.indexedDocumentCount < 0) {
            throw new IllegalArgumentException(
                    "indexedDocumentCount must be >= 0, got: " + builder.indexedDocumentCount);
        }
        if (builder.indexedChunkCount < 0) {
            throw new IllegalArgumentException("indexedChunkCount must be >= 0, got: " + builder.indexedChunkCount);
        }
        if (builder.skippedDocumentCount < 0) {
            throw new IllegalArgumentException(
                    "skippedDocumentCount must be >= 0, got: " + builder.skippedDocumentCount);
        }
        if (builder.durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0, got: " + builder.durationMs);
        }
        this.indexedDocumentCount = builder.indexedDocumentCount;
        this.indexedChunkCount = builder.indexedChunkCount;
        this.skippedDocumentCount = builder.skippedDocumentCount;
        this.durationMs = builder.durationMs;
        this.errors = builder.errors == null ? Collections.emptyList() : Collections.unmodifiableList(builder.errors);
    }

    /**
     * Returns the number of documents successfully indexed.
     *
     * @return the indexed document count (>= 0)
     */
    public int getIndexedDocumentCount() {
        return indexedDocumentCount;
    }

    /**
     * Returns the total number of chunks created.
     *
     * @return the indexed chunk count (>= 0)
     */
    public int getIndexedChunkCount() {
        return indexedChunkCount;
    }

    /**
     * Returns the number of documents skipped.
     *
     * @return the skipped document count (>= 0)
     */
    public int getSkippedDocumentCount() {
        return skippedDocumentCount;
    }

    /**
     * Returns the indexing duration in milliseconds.
     *
     * @return the duration (>= 0)
     */
    public long getDurationMs() {
        return durationMs;
    }

    /**
     * Returns the list of per-file indexing error messages.
     *
     * @return an unmodifiable list of error messages (never null; empty means all succeeded)
     */
    public List<String> getErrors() {
        return errors;
    }

    @Override
    public String toString() {
        return "IndexResult{docs=" + indexedDocumentCount + ", chunks=" + indexedChunkCount + ", skipped="
                + skippedDocumentCount + ", durationMs=" + durationMs + ", errors=" + errors.size() + '}';
    }

    /**
     * Builder for {@link IndexResult}.
     */
    public static final class Builder {
        private int indexedDocumentCount;
        private int indexedChunkCount;
        private int skippedDocumentCount;
        private long durationMs;
        private List<String> errors;

        private Builder() {
        }

        /** Sets the indexed document count. */
        public Builder indexedDocumentCount(int indexedDocumentCount) {
            this.indexedDocumentCount = indexedDocumentCount;
            return this;
        }

        /** Sets the indexed chunk count. */
        public Builder indexedChunkCount(int indexedChunkCount) {
            this.indexedChunkCount = indexedChunkCount;
            return this;
        }

        /** Sets the skipped document count. */
        public Builder skippedDocumentCount(int skippedDocumentCount) {
            this.skippedDocumentCount = skippedDocumentCount;
            return this;
        }

        /** Sets the indexing duration in milliseconds. */
        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        /** Sets the error messages. */
        public Builder errors(List<String> errors) {
            this.errors = errors;
            return this;
        }

        /**
         * Builds the index result.
         *
         * @return a new {@link IndexResult} instance
         * @throws IllegalArgumentException
         *             if any count or duration is negative
         */
        public IndexResult build() {
            return new IndexResult(this);
        }
    }
}
