package at.aimon.core.knowledge.wiki;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of a wiki knowledge base's state.
 *
 * @see WikiKnowledgeBase#getStatus(WikiScope)
 */
public final class WikiStatus {

    /**
     * Possible states of the wiki knowledge base.
     */
    public enum State {
        /** Wiki is ready and can serve queries. */
        READY,
        /** Ingestion is in progress. */
        INGESTING,
        /** No documents have been ingested yet. */
        EMPTY,
        /** An error occurred. */
        ERROR
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final int pageCount;
    private final int sourceCount;
    private final Instant lastIngestedAt;
    private final String wikiDirectory;
    private final State state;

    private WikiStatus(Builder builder) {
        if (builder.pageCount < 0) {
            throw new IllegalArgumentException("pageCount must be >= 0, got: " + builder.pageCount);
        }
        if (builder.sourceCount < 0) {
            throw new IllegalArgumentException("sourceCount must be >= 0, got: " + builder.sourceCount);
        }
        this.pageCount = builder.pageCount;
        this.sourceCount = builder.sourceCount;
        this.lastIngestedAt = builder.lastIngestedAt;
        this.wikiDirectory = builder.wikiDirectory;
        this.state = Objects.requireNonNull(builder.state, "state must not be null");
    }

    /**
     * Returns the number of wiki pages.
     *
     * @return the page count (>= 0)
     */
    public int getPageCount() {
        return pageCount;
    }

    /**
     * Returns the number of ingested source documents.
     *
     * @return the source count (>= 0)
     */
    public int getSourceCount() {
        return sourceCount;
    }

    /**
     * Returns the time of the last successful ingestion, or {@code null} if never ingested.
     *
     * @return the last ingested time, or null
     */
    public Instant getLastIngestedAt() {
        return lastIngestedAt;
    }

    /**
     * Returns the wiki directory path, or {@code null} if not yet initialized.
     *
     * @return the directory path, or null
     */
    public String getWikiDirectory() {
        return wikiDirectory;
    }

    /**
     * Returns the current wiki state.
     *
     * @return the state (never null)
     */
    public State getState() {
        return state;
    }

    @Override
    public String toString() {
        return "WikiStatus{state=" + state + ", pages=" + pageCount + ", sources=" + sourceCount + ", dir='"
                + wikiDirectory + "'}";
    }

    /**
     * Builder for {@link WikiStatus}.
     */
    public static final class Builder {

        private int pageCount;
        private int sourceCount;
        private Instant lastIngestedAt;
        private String wikiDirectory;
        private State state = State.EMPTY;

        private Builder() {
        }

        /** Sets the wiki page count. */
        public Builder pageCount(int pageCount) {
            this.pageCount = pageCount;
            return this;
        }

        /** Sets the ingested source count. */
        public Builder sourceCount(int sourceCount) {
            this.sourceCount = sourceCount;
            return this;
        }

        /** Sets the last ingestion time. */
        public Builder lastIngestedAt(Instant lastIngestedAt) {
            this.lastIngestedAt = lastIngestedAt;
            return this;
        }

        /** Sets the wiki directory. */
        public Builder wikiDirectory(String wikiDirectory) {
            this.wikiDirectory = wikiDirectory;
            return this;
        }

        /** Sets the wiki state. */
        public Builder state(State state) {
            this.state = state;
            return this;
        }

        /**
         * Builds the wiki status.
         *
         * @return a new {@link WikiStatus} instance
         * @throws NullPointerException
         *             if state is null
         * @throws IllegalArgumentException
         *             if counts are negative
         */
        public WikiStatus build() {
            return new WikiStatus(this);
        }
    }
}
