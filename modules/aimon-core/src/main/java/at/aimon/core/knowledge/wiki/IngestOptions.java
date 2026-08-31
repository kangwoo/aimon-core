package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.List;

/**
 * Immutable options for wiki source ingestion.
 *
 * <p>
 * Controls which files to process from the raw source and how the wiki should be updated.
 *
 * <pre>{@code
 * IngestOptions options = IngestOptions.builder()
 *         .filePatterns(List.of("*.md", "*.txt"))
 *         .overwrite(true)
 *         .maxDocuments(100)
 *         .build();
 * }</pre>
 *
 * @see WikiKnowledgeBase#ingest(WikiScope, WikiSource, IngestOptions)
 */
public final class IngestOptions {

    /** Default file patterns for ingestion. */
    public static final List<String> DEFAULT_FILE_PATTERNS = List.of("*.md", "*.txt");

    /** Default maximum number of documents to ingest. */
    public static final int DEFAULT_MAX_DOCUMENTS = 500;

    /**
     * Returns a default {@link IngestOptions} instance.
     *
     * @return default options
     */
    public static IngestOptions defaults() {
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
    private final boolean overwrite;
    private final boolean enableMerge;
    private final boolean autoSynthesize;
    private final int maxDocuments;

    private IngestOptions(Builder builder) {
        if (builder.maxDocuments < 1) {
            throw new IllegalArgumentException("maxDocuments must be >= 1, got: " + builder.maxDocuments);
        }
        this.filePatterns = builder.filePatterns == null
                ? DEFAULT_FILE_PATTERNS
                : Collections.unmodifiableList(builder.filePatterns);
        this.recursive = builder.recursive;
        this.overwrite = builder.overwrite;
        this.enableMerge = builder.enableMerge;
        this.autoSynthesize = builder.autoSynthesize;
        this.maxDocuments = builder.maxDocuments;
    }

    /**
     * Returns the file glob patterns for selecting source documents.
     *
     * @return an unmodifiable list of patterns (never null)
     */
    public List<String> getFilePatterns() {
        return filePatterns;
    }

    /**
     * Returns whether subdirectories should be included.
     *
     * @return {@code true} if recursive ingestion is enabled
     */
    public boolean isRecursive() {
        return recursive;
    }

    /**
     * Returns whether existing wiki pages should be overwritten during ingestion.
     *
     * @return {@code true} if overwrite is enabled
     */
    public boolean isOverwrite() {
        return overwrite;
    }

    /**
     * Returns whether the LLM-driven page merge path is enabled. When {@code true} and a {@link WikiPageMerger} is
     * wired into {@link DefaultWikiKnowledgeBase}, generated pages with strategy
     * {@link GeneratedPage.UpdateStrategy#MERGE} that target an existing on-disk page will be combined via the
     * merger instead of falling through to a plain replace. Defaults to {@code false} so the LLM cost of merge is
     * always opt-in.
     *
     * <p>
     * This flag is independent of {@link #isOverwrite()}: {@code overwrite=true} still globally escalates every
     * generated page to {@link GeneratedPage.UpdateStrategy#REPLACE}, bypassing the merger.
     *
     * <p>
     * <b>Doc compliance note:</b> {@code docs/references/llm-wiki.md} describes ingest as "writes a summary page
     * in the wiki, updates the index, <i>updates relevant entity and concept pages across the wiki</i>, and
     * appends an entry to the log." The highlighted "updates ... pages" behaviour requires this flag <b>and</b>
     * a non-null {@link WikiPageMerger}. With the default {@code enableMerge=false}, re-ingesting a source whose
     * entities/concepts already have pages will CREATE-skip them rather than merge new facts in. Callers that
     * expect a compounding, self-updating wiki per the doc's design must turn this flag on and wire a merger.
     *
     * @return {@code true} if LLM merge is enabled
     */
    public boolean isEnableMerge() {
        return enableMerge;
    }

    /**
     * Returns whether the synthesis pass should be triggered automatically at the end of a successful
     * ingest. When {@code true} and a {@link SynthesisStrategy} is wired into {@link DefaultWikiKnowledgeBase},
     * a {@link WikiKnowledgeBase#synthesize(WikiScope, SynthesizeOptions)} call is issued with default
     * synthesis options as the last step of {@link WikiKnowledgeBase#ingest(WikiScope, WikiSource, IngestOptions)}.
     * Defaults to {@code false} so the additional LLM cost is always opt-in.
     *
     * <p>
     * Synthesis errors do not abort the ingest — they are logged at WARN and the {@link IngestResult} reports
     * the same counts it would have without synthesis. To inspect synthesis details, call
     * {@link WikiKnowledgeBase#synthesize(WikiScope, SynthesizeOptions)} directly with custom options.
     *
     * @return {@code true} if auto-synthesis is enabled
     */
    public boolean isAutoSynthesize() {
        return autoSynthesize;
    }

    /**
     * Returns the maximum number of documents to ingest.
     *
     * @return the max documents (>= 1)
     */
    public int getMaxDocuments() {
        return maxDocuments;
    }

    @Override
    public String toString() {
        return "IngestOptions{patterns=" + filePatterns + ", recursive=" + recursive + ", overwrite=" + overwrite
                + ", enableMerge=" + enableMerge + ", autoSynthesize=" + autoSynthesize + ", maxDocs=" + maxDocuments
                + '}';
    }

    /**
     * Builder for {@link IngestOptions}.
     */
    public static final class Builder {

        private List<String> filePatterns;
        private boolean recursive = true;
        private boolean overwrite = false;
        private boolean enableMerge = false;
        private boolean autoSynthesize = false;
        private int maxDocuments = DEFAULT_MAX_DOCUMENTS;

        private Builder() {
        }

        /**
         * Sets the file patterns for selecting source documents.
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
         * Sets whether to overwrite existing wiki pages.
         *
         * @param overwrite
         *            {@code true} to overwrite existing pages
         * @return this builder
         */
        public Builder overwrite(boolean overwrite) {
            this.overwrite = overwrite;
            return this;
        }

        /**
         * Enables the LLM-driven page merge path. When set to {@code true}, generated pages with strategy
         * {@link GeneratedPage.UpdateStrategy#MERGE} that target an existing on-disk page will be combined via the
         * {@link WikiPageMerger} wired into {@link DefaultWikiKnowledgeBase} instead of falling through to a plain
         * replace. Defaults to {@code false} so the additional LLM cost is always opt-in.
         *
         * <p>
         * Turn this on — together with wiring a non-null {@link WikiPageMerger} — to get the full compounding-
         * wiki behaviour described in {@code docs/references/llm-wiki.md}: "updates relevant entity and concept
         * pages across the wiki" when re-ingesting a source.
         *
         * @param enableMerge
         *            {@code true} to enable LLM merge
         * @return this builder
         */
        public Builder enableMerge(boolean enableMerge) {
            this.enableMerge = enableMerge;
            return this;
        }

        /**
         * Triggers a synthesis pass automatically at the end of a successful ingest. Requires a
         * {@link SynthesisStrategy} to be wired into {@link DefaultWikiKnowledgeBase} — otherwise the
         * auto-synthesis call is silently skipped (logged at debug). Defaults to {@code false}.
         *
         * @param autoSynthesize
         *            {@code true} to enable auto-synthesis
         * @return this builder
         */
        public Builder autoSynthesize(boolean autoSynthesize) {
            this.autoSynthesize = autoSynthesize;
            return this;
        }

        /**
         * Sets the maximum number of documents to ingest.
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
         * Builds the ingest options.
         *
         * @return a new {@link IngestOptions} instance
         * @throws IllegalArgumentException
         *             if maxDocuments &lt; 1
         */
        public IngestOptions build() {
            return new IngestOptions(this);
        }
    }
}
