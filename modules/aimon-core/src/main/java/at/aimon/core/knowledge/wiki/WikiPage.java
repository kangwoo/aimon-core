package at.aimon.core.knowledge.wiki;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable representation of a wiki page.
 *
 * <p>
 * A wiki page is an LLM-generated markdown file within the wiki layer. It contains structured content with metadata,
 * inter-page links, and tags for categorization. Pages are created and maintained by the LLM during ingest and query
 * operations.
 *
 * <pre>{@code
 * WikiPage page = WikiPage.builder()
 *         .path("/wiki/concepts/kubernetes-pods.md")
 *         .title("Kubernetes Pods")
 *         .content("# Kubernetes Pods\n\nA Pod is the smallest deployable unit...")
 *         .tags(List.of("kubernetes", "containers"))
 *         .linkedPages(List.of("/wiki/concepts/containers.md"))
 *         .sourceRef("/raw/articles/k8s-guide.md")
 *         .lastUpdatedAt(Instant.now())
 *         .build();
 * }</pre>
 *
 * @see WikiKnowledgeBase
 */
public final class WikiPage {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String path;
    private final String title;
    private final String content;
    private final WikiPageType type;
    private final List<String> tags;
    private final List<String> linkedPages;
    private final List<String> derivedFrom;
    private final Map<String, String> metadata;
    private final String sourceRef;
    private final Instant lastUpdatedAt;

    private WikiPage(Builder builder) {
        this.path = Objects.requireNonNull(builder.path, "path must not be null");
        if (builder.path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        this.title = Objects.requireNonNull(builder.title, "title must not be null");
        this.content = Objects.requireNonNull(builder.content, "content must not be null");
        this.type = builder.type == null ? WikiPageType.DEFAULT : builder.type;
        this.tags = builder.tags == null ? Collections.emptyList() : Collections.unmodifiableList(builder.tags);
        this.linkedPages = builder.linkedPages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(builder.linkedPages);
        this.derivedFrom = builder.derivedFrom == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(builder.derivedFrom);
        this.metadata = builder.metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(builder.metadata);
        this.sourceRef = builder.sourceRef;
        this.lastUpdatedAt = builder.lastUpdatedAt;
    }

    /**
     * Returns the VFS path of this wiki page.
     *
     * @return the page path (never null or empty)
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the page title.
     *
     * @return the title (never null)
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the full markdown content.
     *
     * @return the content (never null)
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the semantic type of this page (summary, entity, concept, comparison, overview, synthesis, answer).
     *
     * <p>
     * For pages written before the type system was introduced, this defaults to {@link WikiPageType#DEFAULT} so the
     * change is invisible to existing wiki content.
     *
     * @return the page type (never null)
     */
    public WikiPageType getType() {
        return type;
    }

    /**
     * Returns the tags for categorization.
     *
     * @return an unmodifiable list of tags (never null)
     */
    public List<String> getTags() {
        return tags;
    }

    /**
     * Returns the paths of pages linked from this page.
     *
     * @return an unmodifiable list of linked page paths (never null)
     */
    public List<String> getLinkedPages() {
        return linkedPages;
    }

    /**
     * Returns the raw source document paths this page was derived from. Unlike {@link #getSourceRef()}, which is a
     * single legacy field pointing at the originating source, this list captures all sources that have contributed
     * content to the page — for example, an entity or concept page can merge information from multiple raw
     * documents over time.
     *
     * <p>
     * Empty for pages that predate the type system or for pages not produced from raw sources (e.g., purely synthetic
     * overview pages).
     *
     * @return an unmodifiable list of source document paths (never null)
     */
    public List<String> getDerivedFrom() {
        return derivedFrom;
    }

    /**
     * Returns additional metadata.
     *
     * @return an unmodifiable metadata map (never null)
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Returns the path of the raw source document that originated this page, or {@code null} if not tracked.
     *
     * @return the source reference path, or null
     */
    public String getSourceRef() {
        return sourceRef;
    }

    /**
     * Returns the time this page was last updated, or {@code null} if unknown.
     *
     * @return the last update time, or null
     */
    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    @Override
    public String toString() {
        return "WikiPage{path='" + path + "', title='" + title + "', type=" + type + ", tags=" + tags + '}';
    }

    /**
     * Builder for {@link WikiPage}.
     */
    public static final class Builder {

        private String path;
        private String title;
        private String content;
        private WikiPageType type;
        private List<String> tags;
        private List<String> linkedPages;
        private List<String> derivedFrom;
        private Map<String, String> metadata;
        private String sourceRef;
        private Instant lastUpdatedAt;

        private Builder() {
        }

        /**
         * Sets the VFS path of this wiki page.
         *
         * @param path
         *            the page path (must not be null or empty)
         * @return this builder
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * Sets the page title.
         *
         * @param title
         *            the title
         * @return this builder
         */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /**
         * Sets the full markdown content.
         *
         * @param content
         *            the content
         * @return this builder
         */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /**
         * Sets the semantic type of this page. If not set, {@link WikiPageType#DEFAULT} is used — this keeps existing
         * call sites, which typically only set {@code path/title/content}, working unchanged.
         *
         * @param type
         *            the page type (may be null to use the default)
         * @return this builder
         */
        public Builder type(WikiPageType type) {
            this.type = type;
            return this;
        }

        /**
         * Sets the tags for categorization.
         *
         * @param tags
         *            the tags
         * @return this builder
         */
        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        /**
         * Sets the paths of linked pages.
         *
         * @param linkedPages
         *            the linked page paths
         * @return this builder
         */
        public Builder linkedPages(List<String> linkedPages) {
            this.linkedPages = linkedPages;
            return this;
        }

        /**
         * Sets the raw source document paths this page was derived from. Used by merge-capable page types
         * (entity/concept/etc.) to track every source that has contributed content so far.
         *
         * @param derivedFrom
         *            the source paths (may be null for the empty list)
         * @return this builder
         */
        public Builder derivedFrom(List<String> derivedFrom) {
            this.derivedFrom = derivedFrom;
            return this;
        }

        /**
         * Sets additional metadata.
         *
         * @param metadata
         *            the metadata
         * @return this builder
         */
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Sets the source reference path.
         *
         * @param sourceRef
         *            the raw source document path
         * @return this builder
         */
        public Builder sourceRef(String sourceRef) {
            this.sourceRef = sourceRef;
            return this;
        }

        /**
         * Sets the last update time.
         *
         * @param lastUpdatedAt
         *            the last update time
         * @return this builder
         */
        public Builder lastUpdatedAt(Instant lastUpdatedAt) {
            this.lastUpdatedAt = lastUpdatedAt;
            return this;
        }

        /**
         * Builds the wiki page.
         *
         * @return a new {@link WikiPage} instance
         * @throws NullPointerException
         *             if path, title, or content is null
         * @throws IllegalArgumentException
         *             if path is empty
         */
        public WikiPage build() {
            return new WikiPage(this);
        }
    }
}
