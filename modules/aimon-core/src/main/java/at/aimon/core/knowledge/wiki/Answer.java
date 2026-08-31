package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of {@link WikiKnowledgeBase#answer(WikiScope, AnswerRequest)}: the LLM-synthesized answer
 * text together with the wiki pages that supported it and a convenience helper for filing the answer back into
 * the wiki.
 *
 * <p>
 * The {@link #toFiledAnswer()} helper produces a ready-to-file {@link FiledAnswer} so the natural workflow
 * <pre>{@code
 * Answer ans = wiki.answer(scope, AnswerRequest.builder().question(q).build());
 * if (looksGood(ans)) {
 *     wiki.fileAnswer(scope, ans.toFiledAnswer());
 * }
 * }</pre>
 * stays a one-liner. Callers can also build a custom {@link FiledAnswer} from {@link #getText()} and
 * {@link #getSourceRefs()} when they want to add tags, rewrite the title, or skip filing entirely.
 */
public final class Answer {

    /**
     * Returns a new builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String question;
    private final String title;
    private final String text;
    private final List<String> sourceRefs;
    private final int llmCallCount;

    private Answer(Builder builder) {
        this.question = Objects.requireNonNull(builder.question, "question must not be null");
        this.title = Objects.requireNonNull(builder.title, "title must not be null");
        if (builder.title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.text = Objects.requireNonNull(builder.text, "text must not be null");
        if (builder.text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        this.sourceRefs = builder.sourceRefs == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(builder.sourceRefs);
        if (builder.llmCallCount < 0) {
            throw new IllegalArgumentException("llmCallCount must be >= 0, got: " + builder.llmCallCount);
        }
        this.llmCallCount = builder.llmCallCount;
    }

    /** Returns the original question this answer responds to. */
    public String getQuestion() {
        return question;
    }

    /**
     * Returns the human-readable title for the answer. Used as the page heading and frontmatter title when the
     * answer is filed back into the wiki via {@link #toFiledAnswer()}.
     */
    public String getTitle() {
        return title;
    }

    /** Returns the markdown body of the synthesized answer. */
    public String getText() {
        return text;
    }

    /**
     * Returns the wiki page paths that supported this answer. These become {@code [[wiki-link]]} back-references
     * when the answer is filed back via {@link #toFiledAnswer()} so the new page participates in the graph.
     *
     * @return an unmodifiable list (never null; empty means no supporting pages were used)
     */
    public List<String> getSourceRefs() {
        return sourceRefs;
    }

    /**
     * Returns the number of LLM calls the answer strategy issued. {@code 0} for deterministic strategies that
     * synthesize answers without an LLM (e.g., a template-based fallback).
     */
    public int getLlmCallCount() {
        return llmCallCount;
    }

    /**
     * Builds a {@link FiledAnswer} ready for {@link WikiKnowledgeBase#fileAnswer(WikiScope, FiledAnswer)}. The
     * filed answer carries this answer's title, text body, and source refs verbatim and no tags. Callers that
     * want tags or a custom title should construct a {@link FiledAnswer} directly from
     * {@link #getText()} / {@link #getSourceRefs()}.
     *
     * @return a new {@link FiledAnswer}
     */
    public FiledAnswer toFiledAnswer() {
        return FiledAnswer.builder().title(title).content(text).sourceRefs(sourceRefs).build();
    }

    @Override
    public String toString() {
        return "Answer{title='" + title + "', sourceRefs=" + sourceRefs.size() + ", llmCalls=" + llmCallCount + '}';
    }

    /** Builder for {@link Answer}. */
    public static final class Builder {

        private String question;
        private String title;
        private String text;
        private List<String> sourceRefs;
        private int llmCallCount;

        private Builder() {
        }

        /** Sets the original question. Required. */
        public Builder question(String question) {
            this.question = question;
            return this;
        }

        /** Sets the answer title. Required, non-blank. */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /** Sets the markdown body. Required, non-empty. */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /** Sets the supporting page paths. Optional, defaults to empty. */
        public Builder sourceRefs(List<String> sourceRefs) {
            this.sourceRefs = sourceRefs;
            return this;
        }

        /** Sets the LLM call count. Optional, defaults to 0. */
        public Builder llmCallCount(int llmCallCount) {
            this.llmCallCount = llmCallCount;
            return this;
        }

        /** Builds the answer. */
        public Answer build() {
            return new Answer(this);
        }
    }
}
