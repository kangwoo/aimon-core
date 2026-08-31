package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable options controlling the synthesis pass over an existing wiki scope.
 *
 * <p>
 * Synthesis is the second-pass workflow described in {@code docs/references/llm-wiki.md}: after a wiki has
 * accumulated enough entity / concept pages from raw sources, the LLM is asked to produce higher-level
 * {@link WikiPageType#OVERVIEW} pages (one per topic cluster) and a single {@link WikiPageType#SYNTHESIS} page
 * (cross-cluster insight). It is intentionally a separate API from
 * {@link WikiKnowledgeBase#ingest(WikiScope, WikiSource, IngestOptions)} so callers can run it on a schedule
 * independently of source ingestion.
 *
 * <p>
 * The default options ({@link #defaults()}) generate both overview and synthesis pages, cap the number of
 * clusters at 10, allow up to 12 LLM calls (10 overviews + 1 synthesis + 1 buffer), and do not overwrite
 * existing pages — re-running synthesis is therefore idempotent until the underlying entity / concept set
 * changes.
 *
 * <pre>{@code
 * SynthesizeOptions opts = SynthesizeOptions.builder()
 *         .types(EnumSet.of(WikiPageType.OVERVIEW))
 *         .maxClusters(5)
 *         .maxLlmCalls(8)
 *         .overwrite(true)
 *         .build();
 * }</pre>
 */
public final class SynthesizeOptions {

    /** Default cap on the number of overview clusters generated in a single synthesis pass. */
    public static final int DEFAULT_MAX_CLUSTERS = 10;

    /**
     * Default cap on the total number of LLM calls a synthesis pass may issue. Set conservatively because
     * synthesis is meant to be a periodic background pass — runaway clustering should never burn through tokens.
     */
    public static final int DEFAULT_MAX_LLM_CALLS = 12;

    /**
     * The default set of page types produced by a synthesis pass — both overview and synthesis pages. Callers
     * can shrink this set to skip one or the other.
     */
    public static final Set<WikiPageType> DEFAULT_TYPES = Collections
            .unmodifiableSet(EnumSet.of(WikiPageType.OVERVIEW, WikiPageType.SYNTHESIS));

    /**
     * Returns the default options.
     *
     * @return default options
     */
    public static SynthesizeOptions defaults() {
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

    private final Set<WikiPageType> types;
    private final int maxClusters;
    private final int maxLlmCalls;
    private final boolean overwrite;

    private SynthesizeOptions(Builder builder) {
        if (builder.maxClusters < 1) {
            throw new IllegalArgumentException("maxClusters must be >= 1, got: " + builder.maxClusters);
        }
        if (builder.maxLlmCalls < 1) {
            throw new IllegalArgumentException("maxLlmCalls must be >= 1, got: " + builder.maxLlmCalls);
        }
        if (builder.types != null && builder.types.isEmpty()) {
            throw new IllegalArgumentException("types must not be empty when set");
        }
        this.types = builder.types == null ? DEFAULT_TYPES : Collections.unmodifiableSet(EnumSet.copyOf(builder.types));
        this.maxClusters = builder.maxClusters;
        this.maxLlmCalls = builder.maxLlmCalls;
        this.overwrite = builder.overwrite;
    }

    /**
     * Returns the set of {@link WikiPageType}s to produce in this synthesis pass. The strategy is free to honor
     * a subset (for example, skipping {@link WikiPageType#SYNTHESIS} when only {@link WikiPageType#OVERVIEW} is
     * requested) but must not produce types not in this set.
     *
     * @return an unmodifiable set (never null, never empty)
     */
    public Set<WikiPageType> getTypes() {
        return types;
    }

    /**
     * Returns the maximum number of overview clusters to generate. Acts as a hard cap on the synthesis fan-out
     * even when the source page set would otherwise produce more clusters.
     *
     * @return the cluster cap (>= 1)
     */
    public int getMaxClusters() {
        return maxClusters;
    }

    /**
     * Returns the maximum total number of LLM calls a single synthesis pass may issue. Strategies must respect
     * this cap; once reached, the pass should stop and report any pending work as skipped.
     *
     * @return the LLM call cap (>= 1)
     */
    public int getMaxLlmCalls() {
        return maxLlmCalls;
    }

    /**
     * Returns whether existing overview / synthesis pages should be overwritten when re-generated. When
     * {@code false} (the default), a synthesis pass is idempotent — re-running it leaves existing pages alone
     * and only fills in any missing ones.
     *
     * @return {@code true} if overwrite is enabled
     */
    public boolean isOverwrite() {
        return overwrite;
    }

    @Override
    public String toString() {
        return "SynthesizeOptions{types=" + types + ", maxClusters=" + maxClusters + ", maxLlmCalls=" + maxLlmCalls
                + ", overwrite=" + overwrite + '}';
    }

    /** Builder for {@link SynthesizeOptions}. */
    public static final class Builder {

        private Set<WikiPageType> types;
        private int maxClusters = DEFAULT_MAX_CLUSTERS;
        private int maxLlmCalls = DEFAULT_MAX_LLM_CALLS;
        private boolean overwrite;

        private Builder() {
        }

        /**
         * Sets the set of types to produce. Optional — defaults to {@link #DEFAULT_TYPES}.
         *
         * @param types
         *            the types (must not be empty when non-null)
         * @return this builder
         */
        public Builder types(Set<WikiPageType> types) {
            this.types = types;
            return this;
        }

        /**
         * Sets the maximum number of overview clusters.
         *
         * @param maxClusters
         *            the cap (must be >= 1)
         * @return this builder
         */
        public Builder maxClusters(int maxClusters) {
            this.maxClusters = maxClusters;
            return this;
        }

        /**
         * Sets the maximum number of LLM calls.
         *
         * @param maxLlmCalls
         *            the cap (must be >= 1)
         * @return this builder
         */
        public Builder maxLlmCalls(int maxLlmCalls) {
            this.maxLlmCalls = maxLlmCalls;
            return this;
        }

        /**
         * Sets whether to overwrite existing overview / synthesis pages.
         *
         * @param overwrite
         *            {@code true} to overwrite
         * @return this builder
         */
        public Builder overwrite(boolean overwrite) {
            this.overwrite = overwrite;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return a new {@link SynthesizeOptions}
         * @throws IllegalArgumentException
         *             if any cap is &lt; 1 or types is empty when non-null
         */
        public SynthesizeOptions build() {
            return new SynthesizeOptions(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SynthesizeOptions other)) {
            return false;
        }
        return maxClusters == other.maxClusters && maxLlmCalls == other.maxLlmCalls && overwrite == other.overwrite
                && types.equals(other.types);
    }

    @Override
    public int hashCode() {
        return Objects.hash(types, maxClusters, maxLlmCalls, overwrite);
    }
}
