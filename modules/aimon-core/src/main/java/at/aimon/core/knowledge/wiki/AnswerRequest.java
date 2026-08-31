package at.aimon.core.knowledge.wiki;

import java.util.Objects;

/**
 * Immutable input to {@link WikiKnowledgeBase#answer(WikiScope, AnswerRequest)}: the user-facing question, the
 * search query used to find supporting wiki pages, and a few cost knobs.
 *
 * <p>
 * The natural shape of a wiki answer flow is "search → load → synthesize → optionally file back". This value
 * object is the input to that flow — it lets the caller pass either a single string (in which case the search
 * query is derived from the question) or a more elaborate {@link WikiSearchQuery} when the caller wants to
 * apply filters (tag, type, path) before the answer LLM ever sees the candidates.
 *
 * <pre>{@code
 * AnswerRequest req = AnswerRequest.builder()
 *         .question("How does kubernetes schedule pods across nodes?")
 *         .searchQuery(WikiSearchQuery.builder()
 *                 .queryText("kubernetes pod scheduling")
 *                 .includeTypes(EnumSet.of(WikiPageType.ENTITY, WikiPageType.CONCEPT))
 *                 .build())
 *         .maxContextPages(5)
 *         .build();
 * Answer ans = wiki.answer(scope, req);
 * }</pre>
 */
public final class AnswerRequest {

    /** Default maximum number of supporting pages loaded into the answer LLM prompt. */
    public static final int DEFAULT_MAX_CONTEXT_PAGES = 5;

    /**
     * Returns a new builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String question;
    private final WikiSearchQuery searchQuery;
    private final int maxContextPages;
    private final String format;

    private AnswerRequest(Builder builder) {
        this.question = Objects.requireNonNull(builder.question, "question must not be null");
        if (builder.question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (builder.maxContextPages < 1) {
            throw new IllegalArgumentException("maxContextPages must be >= 1, got: " + builder.maxContextPages);
        }
        // If the caller didn't supply an explicit search query, derive one from the question. The derived query
        // uses the question verbatim as queryText and inherits the maxContextPages cap as maxResults.
        this.searchQuery = builder.searchQuery != null
                ? builder.searchQuery
                : WikiSearchQuery.builder().queryText(builder.question).maxResults(builder.maxContextPages).build();
        this.maxContextPages = builder.maxContextPages;
        // Format is optional. Null and blank both mean "no hint — let the strategy produce a plain markdown
        // page." Blank is normalized to null so implementations only have to check one thing.
        this.format = builder.format == null || builder.format.isBlank() ? null : builder.format.trim();
    }

    /** Returns the user-facing question. */
    public String getQuestion() {
        return question;
    }

    /**
     * Returns the search query used to find supporting pages. When the caller didn't set one explicitly, this
     * is derived from {@link #getQuestion()} with {@code maxResults = maxContextPages}.
     */
    public WikiSearchQuery getSearchQuery() {
        return searchQuery;
    }

    /**
     * Returns the maximum number of supporting wiki pages the strategy should pass to the answer LLM. Acts as
     * a cost cap — pages beyond this limit are dropped from the prompt context.
     *
     * @return the cap (>= 1)
     */
    public int getMaxContextPages() {
        return maxContextPages;
    }

    /**
     * Returns the optional free-form format hint for the answer — e.g., {@code "plain markdown page"},
     * {@code "comparison table"}, {@code "Marp slide deck"}, {@code "matplotlib chart description"}. The hint
     * is passed into the answer LLM's prompt so the model can shape its output accordingly. When {@code null}
     * (the default), the strategy produces a plain markdown page.
     *
     * <p>
     * This honours the doc's note that "answers can take different forms depending on the question — a
     * markdown page, a comparison table, a slide deck (Marp), a chart (matplotlib), a canvas." The wiki
     * framework itself only deals with markdown — non-markdown outputs (images, slides) are the caller's
     * responsibility — but the hint lets a single prompt tailor the <i>shape</i> of the markdown that comes
     * out (e.g., a Marp-compatible slide deck is still markdown, just with slide-break syntax).
     *
     * @return the format hint, or {@code null} for no hint
     */
    public String getFormat() {
        return format;
    }

    @Override
    public String toString() {
        return "AnswerRequest{question='" + question + "', maxContextPages=" + maxContextPages + ", format='" + format
                + "'}";
    }

    /** Builder for {@link AnswerRequest}. */
    public static final class Builder {

        private String question;
        private WikiSearchQuery searchQuery;
        private int maxContextPages = DEFAULT_MAX_CONTEXT_PAGES;
        private String format;

        private Builder() {
        }

        /**
         * Sets the user-facing question. Required.
         *
         * @param question
         *            the question (must not be null or blank)
         * @return this builder
         */
        public Builder question(String question) {
            this.question = question;
            return this;
        }

        /**
         * Sets an explicit search query. Optional — when omitted, a query is derived from {@link #question(String)}.
         *
         * @param searchQuery
         *            the search query
         * @return this builder
         */
        public Builder searchQuery(WikiSearchQuery searchQuery) {
            this.searchQuery = searchQuery;
            return this;
        }

        /**
         * Sets the maximum number of supporting pages.
         *
         * @param maxContextPages
         *            the cap (must be >= 1)
         * @return this builder
         */
        public Builder maxContextPages(int maxContextPages) {
            this.maxContextPages = maxContextPages;
            return this;
        }

        /**
         * Sets the optional format hint passed to the answer LLM. Free-form — examples: {@code "comparison
         * table"}, {@code "Marp slide deck"}, {@code "step-by-step guide"}. Null or blank means "no hint".
         *
         * @param format
         *            the format hint (may be null or blank)
         * @return this builder
         */
        public Builder format(String format) {
            this.format = format;
            return this;
        }

        /**
         * Builds the request.
         *
         * @return a new {@link AnswerRequest}
         */
        public AnswerRequest build() {
            return new AnswerRequest(this);
        }
    }
}
