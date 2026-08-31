package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.List;

/**
 * Immutable result of a {@link WikiKnowledgeBase#synthesize(WikiScope, SynthesizeOptions)} pass.
 *
 * <p>
 * Reports how many overview / synthesis pages were created or updated, how many were skipped (typically because
 * the target file already existed and {@code overwrite=false}), how many LLM calls were issued (so callers can
 * see whether the {@link SynthesizeOptions#getMaxLlmCalls()} cap was reached), and any per-cluster errors that
 * did not abort the whole pass.
 *
 * <p>
 * Created and updated counts are partitioned: a page only contributes to one of the two. Errors are recorded
 * as opaque strings — strategies should include enough context (cluster name, slug) for the caller to act on.
 */
public final class SynthesizeResult {

    /**
     * Returns an empty result with all counters at zero.
     *
     * @return an empty result
     */
    public static SynthesizeResult empty() {
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

    private final int createdPageCount;
    private final int updatedPageCount;
    private final int skippedCount;
    private final int llmCallCount;
    private final long durationMs;
    private final List<String> errors;

    private SynthesizeResult(Builder builder) {
        if (builder.createdPageCount < 0) {
            throw new IllegalArgumentException("createdPageCount must be >= 0, got: " + builder.createdPageCount);
        }
        if (builder.updatedPageCount < 0) {
            throw new IllegalArgumentException("updatedPageCount must be >= 0, got: " + builder.updatedPageCount);
        }
        if (builder.skippedCount < 0) {
            throw new IllegalArgumentException("skippedCount must be >= 0, got: " + builder.skippedCount);
        }
        if (builder.llmCallCount < 0) {
            throw new IllegalArgumentException("llmCallCount must be >= 0, got: " + builder.llmCallCount);
        }
        if (builder.durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0, got: " + builder.durationMs);
        }
        this.createdPageCount = builder.createdPageCount;
        this.updatedPageCount = builder.updatedPageCount;
        this.skippedCount = builder.skippedCount;
        this.llmCallCount = builder.llmCallCount;
        this.durationMs = builder.durationMs;
        this.errors = builder.errors == null ? Collections.emptyList() : Collections.unmodifiableList(builder.errors);
    }

    /** Returns the number of new overview / synthesis pages created. */
    public int getCreatedPageCount() {
        return createdPageCount;
    }

    /** Returns the number of existing overview / synthesis pages overwritten. */
    public int getUpdatedPageCount() {
        return updatedPageCount;
    }

    /**
     * Returns the number of synthesized pages that were not written — typically because the target file
     * already existed and {@code overwrite=false}, or because the {@link SynthesizeOptions#getMaxLlmCalls()}
     * cap was reached before the cluster could be processed.
     */
    public int getSkippedCount() {
        return skippedCount;
    }

    /** Returns the total number of LLM calls issued by this synthesis pass. */
    public int getLlmCallCount() {
        return llmCallCount;
    }

    /** Returns the total duration of this synthesis pass in milliseconds. */
    public long getDurationMs() {
        return durationMs;
    }

    /** Returns the per-cluster error messages, or an empty list when the pass succeeded cleanly. */
    public List<String> getErrors() {
        return errors;
    }

    @Override
    public String toString() {
        return "SynthesizeResult{created=" + createdPageCount + ", updated=" + updatedPageCount + ", skipped="
                + skippedCount + ", llmCalls=" + llmCallCount + ", durationMs=" + durationMs + ", errors="
                + errors.size() + '}';
    }

    /** Builder for {@link SynthesizeResult}. */
    public static final class Builder {

        private int createdPageCount;
        private int updatedPageCount;
        private int skippedCount;
        private int llmCallCount;
        private long durationMs;
        private List<String> errors;

        private Builder() {
        }

        /** Sets the created page count. */
        public Builder createdPageCount(int createdPageCount) {
            this.createdPageCount = createdPageCount;
            return this;
        }

        /** Sets the updated page count. */
        public Builder updatedPageCount(int updatedPageCount) {
            this.updatedPageCount = updatedPageCount;
            return this;
        }

        /** Sets the skipped page count. */
        public Builder skippedCount(int skippedCount) {
            this.skippedCount = skippedCount;
            return this;
        }

        /** Sets the LLM call count. */
        public Builder llmCallCount(int llmCallCount) {
            this.llmCallCount = llmCallCount;
            return this;
        }

        /** Sets the duration in milliseconds. */
        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        /** Sets the error messages. */
        public Builder errors(List<String> errors) {
            this.errors = errors;
            return this;
        }

        /** Builds the result. */
        public SynthesizeResult build() {
            return new SynthesizeResult(this);
        }
    }
}
