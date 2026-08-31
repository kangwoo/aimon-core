package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.List;

/**
 * Immutable result of a wiki source ingestion operation.
 *
 * @see WikiKnowledgeBase#ingest(WikiScope, WikiSource, IngestOptions)
 */
public final class IngestResult {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final int ingestedCount;
    private final int skippedCount;
    private final int updatedPageCount;
    private final int createdPageCount;
    private final int mergedPageCount;
    private final long durationMs;
    private final List<String> errors;

    private IngestResult(Builder builder) {
        if (builder.ingestedCount < 0) {
            throw new IllegalArgumentException("ingestedCount must be >= 0, got: " + builder.ingestedCount);
        }
        if (builder.skippedCount < 0) {
            throw new IllegalArgumentException("skippedCount must be >= 0, got: " + builder.skippedCount);
        }
        if (builder.updatedPageCount < 0) {
            throw new IllegalArgumentException("updatedPageCount must be >= 0, got: " + builder.updatedPageCount);
        }
        if (builder.createdPageCount < 0) {
            throw new IllegalArgumentException("createdPageCount must be >= 0, got: " + builder.createdPageCount);
        }
        if (builder.mergedPageCount < 0) {
            throw new IllegalArgumentException("mergedPageCount must be >= 0, got: " + builder.mergedPageCount);
        }
        if (builder.durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0, got: " + builder.durationMs);
        }
        this.ingestedCount = builder.ingestedCount;
        this.skippedCount = builder.skippedCount;
        this.updatedPageCount = builder.updatedPageCount;
        this.createdPageCount = builder.createdPageCount;
        this.mergedPageCount = builder.mergedPageCount;
        this.durationMs = builder.durationMs;
        this.errors = builder.errors == null ? Collections.emptyList() : Collections.unmodifiableList(builder.errors);
    }

    /**
     * Returns the number of source documents successfully ingested.
     *
     * @return the ingested count (>= 0)
     */
    public int getIngestedCount() {
        return ingestedCount;
    }

    /**
     * Returns the number of source documents skipped.
     *
     * @return the skipped count (>= 0)
     */
    public int getSkippedCount() {
        return skippedCount;
    }

    /**
     * Returns the number of existing wiki pages updated.
     *
     * @return the updated page count (>= 0)
     */
    public int getUpdatedPageCount() {
        return updatedPageCount;
    }

    /**
     * Returns the number of new wiki pages created.
     *
     * @return the created page count (>= 0)
     */
    public int getCreatedPageCount() {
        return createdPageCount;
    }

    /**
     * Returns the number of existing wiki pages merged with new content via the LLM merge path.
     *
     * <p>
     * Merged pages are <i>not</i> double-counted in {@link #getUpdatedPageCount()} — the two counters partition
     * the set of pre-existing pages that the ingest touched. A page only contributes to {@code mergedPageCount}
     * when {@link IngestOptions#isEnableMerge()} is on, the wired {@link WikiPageMerger} is non-null, and the
     * generated page used {@link GeneratedPage.UpdateStrategy#MERGE} against an existing target.
     *
     * @return the merged page count (>= 0)
     */
    public int getMergedPageCount() {
        return mergedPageCount;
    }

    /**
     * Returns the ingestion duration in milliseconds.
     *
     * @return the duration (>= 0)
     */
    public long getDurationMs() {
        return durationMs;
    }

    /**
     * Returns the list of per-file ingestion error messages.
     *
     * @return an unmodifiable list of error messages (never null; empty means all succeeded)
     */
    public List<String> getErrors() {
        return errors;
    }

    @Override
    public String toString() {
        return "IngestResult{ingested=" + ingestedCount + ", skipped=" + skippedCount + ", created=" + createdPageCount
                + ", updated=" + updatedPageCount + ", merged=" + mergedPageCount + ", durationMs=" + durationMs
                + ", errors=" + errors.size() + '}';
    }

    /**
     * Builder for {@link IngestResult}.
     */
    public static final class Builder {

        private int ingestedCount;
        private int skippedCount;
        private int updatedPageCount;
        private int createdPageCount;
        private int mergedPageCount;
        private long durationMs;
        private List<String> errors;

        private Builder() {
        }

        /** Sets the ingested document count. */
        public Builder ingestedCount(int ingestedCount) {
            this.ingestedCount = ingestedCount;
            return this;
        }

        /** Sets the skipped document count. */
        public Builder skippedCount(int skippedCount) {
            this.skippedCount = skippedCount;
            return this;
        }

        /** Sets the updated wiki page count. */
        public Builder updatedPageCount(int updatedPageCount) {
            this.updatedPageCount = updatedPageCount;
            return this;
        }

        /** Sets the created wiki page count. */
        public Builder createdPageCount(int createdPageCount) {
            this.createdPageCount = createdPageCount;
            return this;
        }

        /** Sets the merged wiki page count. */
        public Builder mergedPageCount(int mergedPageCount) {
            this.mergedPageCount = mergedPageCount;
            return this;
        }

        /** Sets the ingestion duration in milliseconds. */
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
         * Builds the ingest result.
         *
         * @return a new {@link IngestResult} instance
         * @throws IllegalArgumentException
         *             if any count or duration is negative
         */
        public IngestResult build() {
            return new IngestResult(this);
        }
    }
}
