package at.aimon.core.knowledge.wiki;

import java.util.Objects;

/**
 * An immutable {@link WikiPage} wrapped with the relevance score the search strategy assigned to it. Returned by
 * {@link WikiKnowledgeBase#searchWithScores(WikiScope, WikiSearchQuery)} when callers need ranking information
 * alongside the page itself.
 *
 * <p>
 * The score is opaque — every {@link WikiSearchStrategy} is free to define its own scoring scale, and the only
 * guaranteed property is that within a single result list, higher scores indicate higher relevance and the
 * results are ordered by score descending. Callers should not compare scores across different strategies or
 * different queries.
 *
 * <p>
 * The plain {@link WikiKnowledgeBase#search(WikiScope, WikiSearchQuery)} API continues to return raw
 * {@link WikiPage} instances and is unchanged — this value object exists purely for the score-aware path so the
 * existing call sites stay source-compatible.
 */
public final class WikiSearchResult {

    /**
     * Returns a new builder. Prefer this over the 2-arg constructor for new call sites — the builder makes
     * extending this value object in the future (adding e.g. match highlights or token-level scores) purely
     * additive. The direct constructor is retained because every existing strategy and test uses it.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final WikiPage page;
    private final double score;

    /**
     * Creates a search result via direct construction. Equivalent to
     * {@code builder().page(page).score(score).build()}; both forms coexist.
     *
     * @param page
     *            the matched wiki page (must not be null)
     * @param score
     *            the relevance score the strategy assigned. Strategies that don't compute a score (e.g., the
     *            default fall-through wrapper around {@link WikiKnowledgeBase#search}) report 0.0 here.
     * @throws NullPointerException
     *             if page is null
     */
    public WikiSearchResult(WikiPage page, double score) {
        this.page = Objects.requireNonNull(page, "page must not be null");
        this.score = score;
    }

    private WikiSearchResult(Builder builder) {
        this(builder.page, builder.score);
    }

    /** Returns the matched page. */
    public WikiPage getPage() {
        return page;
    }

    /**
     * Returns the strategy-defined relevance score. Higher means more relevant within the same result list.
     * Cross-strategy and cross-query comparisons are not meaningful — different strategies use different scales.
     */
    public double getScore() {
        return score;
    }

    @Override
    public String toString() {
        return "WikiSearchResult{path='" + page.getPath() + "', score=" + score + '}';
    }

    /** Builder for {@link WikiSearchResult}. */
    public static final class Builder {

        private WikiPage page;
        private double score;

        private Builder() {
        }

        /** Sets the matched page. Required, must not be null. */
        public Builder page(WikiPage page) {
            this.page = page;
            return this;
        }

        /** Sets the relevance score. Defaults to {@code 0.0}. */
        public Builder score(double score) {
            this.score = score;
            return this;
        }

        /**
         * Builds the search result.
         *
         * @return a new {@link WikiSearchResult}
         * @throws NullPointerException
         *             if page was not set
         */
        public WikiSearchResult build() {
            return new WikiSearchResult(this);
        }
    }
}
