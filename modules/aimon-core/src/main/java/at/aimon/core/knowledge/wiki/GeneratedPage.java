package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable description of a single wiki page produced by a {@link WikiPageGenerator} during extraction.
 *
 * <p>
 * Where {@link WikiPage} represents a page already persisted to the wiki, {@code GeneratedPage} is the in-flight
 * intermediate result returned by an extractor — typically an LLM-driven one — before {@link DefaultWikiKnowledgeBase}
 * decides where on disk to write it. A single source document can yield multiple {@code GeneratedPage}s: for example,
 * one {@link WikiPageType#SUMMARY} of the source plus several {@link WikiPageType#ENTITY} or
 * {@link WikiPageType#CONCEPT} pages extracted from its content.
 *
 * <p>
 * The {@link UpdateStrategy} field tells the storage layer how to reconcile this generated page with any existing
 * page that has the same target file name:
 * <ul>
 * <li>{@link UpdateStrategy#CREATE} — only write if the target file does not exist yet
 * <li>{@link UpdateStrategy#MERGE} — combine with the existing page
 * <li>{@link UpdateStrategy#REPLACE} — unconditionally overwrite the existing page
 * </ul>
 *
 * <p>
 * The {@code slug} is the type-stripped, file-safe identifier used to build the on-disk file name via
 * {@link WikiIo#buildPageFileName(WikiPageType, String)}. It must be non-blank and should be stable across LLM calls
 * for the same conceptual subject so that {@link UpdateStrategy#MERGE} can find existing pages.
 */
public final class GeneratedPage {

    /**
     * Strategy for reconciling a generated page with any existing page at the same target file name.
     */
    public enum UpdateStrategy {

        /**
         * Write the page only if no file exists at the target path. If a file already exists, the storage layer
         * skips this generated page (and counts it as skipped, not as an error).
         */
        CREATE,

        /**
         * Combine this generated page with the existing page at the target path. When no {@code WikiPageMerger} is
         * wired the storage layer treats {@code MERGE} as {@link #REPLACE} but logs at DEBUG so the
         * difference is observable in test runs.
         */
        MERGE,

        /**
         * Unconditionally overwrite any existing page at the target path. Used for cases where the generator is
         * authoritative — for example a re-ingest with {@code overwrite=true}.
         */
        REPLACE
    }

    private final WikiPageType type;
    private final String slug;
    private final String title;
    private final String content;
    private final List<String> tags;
    private final List<String> derivedFrom;
    private final UpdateStrategy strategy;

    private GeneratedPage(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type must not be null");
        this.slug = Objects.requireNonNull(builder.slug, "slug must not be null");
        if (builder.slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        this.title = Objects.requireNonNull(builder.title, "title must not be null");
        this.content = Objects.requireNonNull(builder.content, "content must not be null");
        if (builder.content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        this.tags = builder.tags == null ? Collections.emptyList() : Collections.unmodifiableList(builder.tags);
        this.derivedFrom = builder.derivedFrom == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(builder.derivedFrom);
        this.strategy = builder.strategy == null ? UpdateStrategy.CREATE : builder.strategy;
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the semantic type of the generated page. */
    public WikiPageType getType() {
        return type;
    }

    /**
     * Returns the type-stripped, file-safe slug used to build the target file name. Combined with {@link #getType()}
     * via {@link WikiIo#buildPageFileName(WikiPageType, String)} to produce the on-disk name.
     */
    public String getSlug() {
        return slug;
    }

    /** Returns the human-readable title. */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the full markdown content (including YAML frontmatter) of the generated page.
     */
    public String getContent() {
        return content;
    }

    /** Returns the unmodifiable list of categorization tags. */
    public List<String> getTags() {
        return tags;
    }

    /**
     * Returns the raw source documents this generated page draws from. Empty for purely synthetic pages such as
     * overviews built from existing wiki content.
     */
    public List<String> getDerivedFrom() {
        return derivedFrom;
    }

    /** Returns the reconciliation strategy used by the storage layer when writing this page. */
    public UpdateStrategy getStrategy() {
        return strategy;
    }

    @Override
    public String toString() {
        return "GeneratedPage{type=" + type + ", slug='" + slug + "', strategy=" + strategy + ", tags=" + tags + '}';
    }

    /**
     * Builder for {@link GeneratedPage}.
     */
    public static final class Builder {

        private WikiPageType type;
        private String slug;
        private String title;
        private String content;
        private List<String> tags;
        private List<String> derivedFrom;
        private UpdateStrategy strategy;

        private Builder() {
        }

        /** Sets the {@link WikiPageType} for this generated page. Required. */
        public Builder type(WikiPageType type) {
            this.type = type;
            return this;
        }

        /** Sets the file-safe slug used to build the on-disk file name. Required, must be non-blank. */
        public Builder slug(String slug) {
            this.slug = slug;
            return this;
        }

        /** Sets the human-readable title. Required. */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /** Sets the full markdown content (including frontmatter). Required, must be non-empty. */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /** Sets categorization tags. Optional, defaults to empty list. */
        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        /** Sets the source documents this page draws from. Optional, defaults to empty list. */
        public Builder derivedFrom(List<String> derivedFrom) {
            this.derivedFrom = derivedFrom;
            return this;
        }

        /** Sets the reconciliation strategy. Optional, defaults to {@link UpdateStrategy#CREATE}. */
        public Builder strategy(UpdateStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /** Builds the immutable {@link GeneratedPage}. */
        public GeneratedPage build() {
            return new GeneratedPage(this);
        }
    }
}
