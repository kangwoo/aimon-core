package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable search request against a {@link WikiKnowledgeBase}.
 *
 * <p>
 * Use the {@link #builder()} to construct instances:
 *
 * <pre>{@code
 * WikiSearchQuery query = WikiSearchQuery.builder()
 *         .queryText("kubernetes pod troubleshooting")
 *         .maxResults(10)
 *         .tags(List.of("kubernetes"))
 *         .build();
 * }</pre>
 *
 * @see WikiKnowledgeBase#search(WikiScope, WikiSearchQuery)
 */
public final class WikiSearchQuery {

    /** Default maximum number of results. */
    public static final int DEFAULT_MAX_RESULTS = 10;

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String queryText;
    private final int maxResults;
    private final List<String> tags;
    private final List<String> pagePathPatterns;
    private final Set<WikiPageType> includeTypes;
    private final Set<WikiPageType> excludeTypes;

    private WikiSearchQuery(Builder builder) {
        this.queryText = Objects.requireNonNull(builder.queryText, "queryText must not be null");
        if (builder.queryText.isEmpty()) {
            throw new IllegalArgumentException("queryText must not be empty");
        }
        if (builder.maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1, got: " + builder.maxResults);
        }
        this.maxResults = builder.maxResults;
        this.tags = builder.tags == null ? Collections.emptyList() : Collections.unmodifiableList(builder.tags);
        this.pagePathPatterns = builder.pagePathPatterns == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(builder.pagePathPatterns);
        this.includeTypes = builder.includeTypes == null || builder.includeTypes.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(builder.includeTypes));
        this.excludeTypes = builder.excludeTypes == null || builder.excludeTypes.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(builder.excludeTypes));
    }

    /**
     * Returns the search query text.
     *
     * @return the query text (never null or empty)
     */
    public String getQueryText() {
        return queryText;
    }

    /**
     * Returns the maximum number of results to return.
     *
     * @return the max results (>= 1)
     */
    public int getMaxResults() {
        return maxResults;
    }

    /**
     * Returns the tag filters. Only pages matching all specified tags are returned.
     *
     * @return an unmodifiable list of tags (never null; empty means no tag filtering)
     */
    public List<String> getTags() {
        return tags;
    }

    /**
     * Returns the page path glob patterns for filtering results.
     *
     * @return an unmodifiable list of path patterns (never null; empty means no path filtering)
     */
    public List<String> getPagePathPatterns() {
        return pagePathPatterns;
    }

    /**
     * Returns the {@link WikiPageType} include filter. Only pages whose type is in this set are returned. An
     * empty set (the default) disables the filter and lets all types through.
     *
     * <p>
     * Useful for type-aware drill-down — for example, "give me only entity pages about kubernetes" or "skip the
     * raw summary pages and only show concept pages." Pairs naturally with {@link #getExcludeTypes()} when the
     * caller wants the inverse.
     *
     * @return an unmodifiable set (never null; empty means no include filtering)
     */
    public Set<WikiPageType> getIncludeTypes() {
        return includeTypes;
    }

    /**
     * Returns the {@link WikiPageType} exclude filter. Pages whose type is in this set are dropped from results.
     * An empty set (the default) disables the filter.
     *
     * <p>
     * The most common use is {@code excludeTypes(EnumSet.of(OVERVIEW, SYNTHESIS))} to skip the high-level roll-up
     * pages when the caller wants to drill into specific entities or concepts directly.
     *
     * @return an unmodifiable set (never null; empty means no exclude filtering)
     */
    public Set<WikiPageType> getExcludeTypes() {
        return excludeTypes;
    }

    /**
     * Returns whether a {@link WikiPageType} passes the type filters on this query. {@code includeTypes} acts as
     * an allowlist when non-empty; {@code excludeTypes} as a denylist. Both empty means every type matches.
     * Strategies use this helper to keep the include/exclude semantics in one place.
     *
     * @param type
     *            the type to test (must not be null)
     * @return {@code true} if the type passes both filters
     */
    public boolean matchesType(WikiPageType type) {
        Objects.requireNonNull(type, "type must not be null");
        if (!includeTypes.isEmpty() && !includeTypes.contains(type)) {
            return false;
        }
        if (excludeTypes.contains(type)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "WikiSearchQuery{query='" + queryText + "', maxResults=" + maxResults + ", tags=" + tags + '}';
    }

    /**
     * Builder for {@link WikiSearchQuery}.
     */
    public static final class Builder {

        private String queryText;
        private int maxResults = DEFAULT_MAX_RESULTS;
        private List<String> tags;
        private List<String> pagePathPatterns;
        private Set<WikiPageType> includeTypes;
        private Set<WikiPageType> excludeTypes;

        private Builder() {
        }

        /**
         * Sets the search query text.
         *
         * @param queryText
         *            the query text (must not be null or empty)
         * @return this builder
         */
        public Builder queryText(String queryText) {
            this.queryText = queryText;
            return this;
        }

        /**
         * Sets the maximum number of results.
         *
         * @param maxResults
         *            the max results (must be >= 1)
         * @return this builder
         */
        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        /**
         * Sets the tag filters.
         *
         * @param tags
         *            tags to filter by
         * @return this builder
         */
        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        /**
         * Sets the page path glob patterns for filtering.
         *
         * @param pagePathPatterns
         *            glob patterns to match page paths
         * @return this builder
         */
        public Builder pagePathPatterns(List<String> pagePathPatterns) {
            this.pagePathPatterns = pagePathPatterns;
            return this;
        }

        /**
         * Sets the {@link WikiPageType} include filter. When non-empty, only pages whose type is in this set
         * are returned. Empty (the default) lets all types through.
         *
         * @param includeTypes
         *            the allowlist of page types
         * @return this builder
         */
        public Builder includeTypes(Set<WikiPageType> includeTypes) {
            this.includeTypes = includeTypes;
            return this;
        }

        /**
         * Sets the {@link WikiPageType} exclude filter. Pages whose type is in this set are dropped from the
         * results. Empty (the default) disables the filter.
         *
         * @param excludeTypes
         *            the denylist of page types
         * @return this builder
         */
        public Builder excludeTypes(Set<WikiPageType> excludeTypes) {
            this.excludeTypes = excludeTypes;
            return this;
        }

        /**
         * Builds the search query.
         *
         * @return a new {@link WikiSearchQuery} instance
         * @throws NullPointerException
         *             if queryText is null
         * @throws IllegalArgumentException
         *             if queryText is empty or maxResults &lt; 1
         */
        public WikiSearchQuery build() {
            return new WikiSearchQuery(this);
        }
    }
}
