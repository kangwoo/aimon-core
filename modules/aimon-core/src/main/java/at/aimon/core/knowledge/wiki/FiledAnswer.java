package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable value object describing an LLM-synthesized answer to be filed back into a wiki as a new page.
 *
 * <p>
 * Per {@code docs/references/llm-wiki.md} (line 39): "good answers can be filed back into the wiki as new pages"
 * so explorations compound in the knowledge base just like ingested sources do. A {@code FiledAnswer} captures the
 * raw material needed to create such a page: a title, the synthesized markdown body, optional tags for
 * categorization, and optional {@code sourceRefs} identifying the wiki pages the answer was derived from —
 * these become {@code [[wiki-link]]} back-references injected into a "References" section.
 *
 * <pre>{@code
 * FiledAnswer answer = FiledAnswer.builder()
 *         .title("How OpenSearch handles mapping conflicts")
 *         .content("## Summary\n\nWhen two indices ...")
 *         .tags(List.of("opensearch", "mappings"))
 *         .sourceRefs(List.of("/wiki/.../pages/summary-docs-opensearch.md"))
 *         .build();
 * WikiPage filed = wiki.fileAnswer(scope, answer);
 * }</pre>
 *
 * @see WikiKnowledgeBase#fileAnswer(WikiScope, FiledAnswer)
 */
public final class FiledAnswer {

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    private final String title;
    private final String content;
    private final List<String> tags;
    private final List<String> sourceRefs;

    private FiledAnswer(Builder builder) {
        this.title = Objects.requireNonNull(builder.title, "title must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.content = Objects.requireNonNull(builder.content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        this.tags = builder.tags == null ? Collections.emptyList() : Collections.unmodifiableList(builder.tags);
        this.sourceRefs = builder.sourceRefs == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(builder.sourceRefs);
    }

    /**
     * Returns the answer title. Used as the page heading, frontmatter {@code title}, and basis for the generated
     * file slug.
     *
     * @return the title (never null or empty)
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the markdown body of the answer. Written verbatim under the page heading.
     *
     * @return the content (never null or empty)
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the tags for categorization.
     *
     * @return an unmodifiable list (never null; empty means no tags)
     */
    public List<String> getTags() {
        return tags;
    }

    /**
     * Returns the paths of wiki pages this answer was derived from. Each ref is rendered as a
     * {@code [[wiki-link]]} in a "References" section of the filed page so the new page participates in the graph.
     *
     * @return an unmodifiable list of page paths (never null; empty means no references)
     */
    public List<String> getSourceRefs() {
        return sourceRefs;
    }

    @Override
    public String toString() {
        return "FiledAnswer{title='" + title + "', tags=" + tags + ", sourceRefs=" + sourceRefs.size() + '}';
    }

    /** Builder for {@link FiledAnswer}. */
    public static final class Builder {

        private String title;
        private String content;
        private List<String> tags;
        private List<String> sourceRefs;

        private Builder() {
        }

        /** Sets the answer title. */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /** Sets the answer markdown body. */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /** Sets the page tags. */
        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        /** Sets the source page references. */
        public Builder sourceRefs(List<String> sourceRefs) {
            this.sourceRefs = sourceRefs;
            return this;
        }

        /** Builds the filed answer. */
        public FiledAnswer build() {
            return new FiledAnswer(this);
        }
    }
}
