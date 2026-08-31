package at.aimon.core.knowledge;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of a {@link KnowledgeStore}'s index state.
 *
 * @see KnowledgeStore#getStatus()
 */
public final class IndexStatus {

    /**
     * Possible states of the knowledge index.
     */
    public enum State {
        /** Index is ready and can serve search queries. */
        READY,
        /** Indexing is in progress. Partial search results may be available. */
        INDEXING,
        /** No documents have been indexed yet. */
        EMPTY,
        /** An error occurred during indexing. */
        ERROR
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final int documentCount;
    private final int chunkCount;
    private final Instant lastIndexedAt;
    private final String indexedDirectory;
    private final State state;

    private IndexStatus(Builder builder) {
        if (builder.documentCount < 0) {
            throw new IllegalArgumentException("documentCount must be >= 0, got: " + builder.documentCount);
        }
        if (builder.chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must be >= 0, got: " + builder.chunkCount);
        }
        this.documentCount = builder.documentCount;
        this.chunkCount = builder.chunkCount;
        this.lastIndexedAt = builder.lastIndexedAt;
        this.indexedDirectory = builder.indexedDirectory;
        this.state = Objects.requireNonNull(builder.state, "state must not be null");
    }

    /**
     * Returns the number of indexed documents.
     *
     * @return the document count (>= 0)
     */
    public int getDocumentCount() {
        return documentCount;
    }

    /**
     * Returns the total number of chunks.
     *
     * @return the chunk count (>= 0)
     */
    public int getChunkCount() {
        return chunkCount;
    }

    /**
     * Returns the time of the last successful indexing, or {@code null} if never indexed.
     *
     * @return the last indexed time, or null
     */
    public Instant getLastIndexedAt() {
        return lastIndexedAt;
    }

    /**
     * Returns the indexed directory path, or {@code null} if not yet indexed.
     *
     * @return the directory path, or null
     */
    public String getIndexedDirectory() {
        return indexedDirectory;
    }

    /**
     * Returns the current index state.
     *
     * @return the state (never null)
     */
    public State getState() {
        return state;
    }

    @Override
    public String toString() {
        return "IndexStatus{state=" + state + ", docs=" + documentCount + ", chunks=" + chunkCount + ", dir='"
                + indexedDirectory + "'}";
    }

    /**
     * Builder for {@link IndexStatus}.
     */
    public static final class Builder {
        private int documentCount;
        private int chunkCount;
        private Instant lastIndexedAt;
        private String indexedDirectory;
        private State state = State.EMPTY;

        private Builder() {
        }

        /** Sets the document count. */
        public Builder documentCount(int documentCount) {
            this.documentCount = documentCount;
            return this;
        }

        /** Sets the chunk count. */
        public Builder chunkCount(int chunkCount) {
            this.chunkCount = chunkCount;
            return this;
        }

        /** Sets the last indexed time. */
        public Builder lastIndexedAt(Instant lastIndexedAt) {
            this.lastIndexedAt = lastIndexedAt;
            return this;
        }

        /** Sets the indexed directory. */
        public Builder indexedDirectory(String indexedDirectory) {
            this.indexedDirectory = indexedDirectory;
            return this;
        }

        /** Sets the state. */
        public Builder state(State state) {
            this.state = state;
            return this;
        }

        /**
         * Builds the index status.
         *
         * @return a new {@link IndexStatus} instance
         * @throws NullPointerException
         *             if state is null
         * @throws IllegalArgumentException
         *             if counts are negative
         */
        public IndexStatus build() {
            return new IndexStatus(this);
        }
    }
}
