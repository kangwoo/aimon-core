package at.aimon.core.knowledge.wiki;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable entry in the wiki change log.
 *
 * <p>
 * Each log entry records a single operation performed on the wiki, such as a page creation, update, or ingestion.
 *
 * @see WikiLog
 * @see WikiKnowledgeBaseAdmin#getLog(WikiScope, int)
 */
public final class WikiLogEntry {

    /**
     * Types of wiki operations.
     */
    public enum Operation {
        /** A new wiki page was created. */
        PAGE_CREATED,
        /** An existing wiki page was updated. */
        PAGE_UPDATED,
        /** A wiki page was deleted. */
        PAGE_DELETED,
        /** A source document was ingested. */
        SOURCE_INGESTED,
        /** A lint check was performed. */
        LINT_PERFORMED,
        /** A query answer was filed back into the wiki as a new page. */
        QUERY_FILED,
        /** A frontmatter migration pass was performed across the wiki. */
        MIGRATION_PERFORMED
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final Instant timestamp;
    private final Operation operation;
    private final String pagePath;
    private final String summary;

    private WikiLogEntry(Builder builder) {
        this.timestamp = Objects.requireNonNull(builder.timestamp, "timestamp must not be null");
        this.operation = Objects.requireNonNull(builder.operation, "operation must not be null");
        this.pagePath = builder.pagePath;
        this.summary = builder.summary;
    }

    /**
     * Returns the time this operation was performed.
     *
     * @return the timestamp (never null)
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the type of operation.
     *
     * @return the operation (never null)
     */
    public Operation getOperation() {
        return operation;
    }

    /**
     * Returns the affected page path, or {@code null} for non-page operations.
     *
     * @return the page path, or null
     */
    public String getPagePath() {
        return pagePath;
    }

    /**
     * Returns a human-readable summary of the operation, or {@code null} if not provided.
     *
     * @return the summary, or null
     */
    public String getSummary() {
        return summary;
    }

    @Override
    public String toString() {
        return "WikiLogEntry{time=" + timestamp + ", op=" + operation + ", page='" + pagePath + "'}";
    }

    /**
     * Builder for {@link WikiLogEntry}.
     */
    public static final class Builder {

        private Instant timestamp;
        private Operation operation;
        private String pagePath;
        private String summary;

        private Builder() {
        }

        /** Sets the timestamp. */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /** Sets the operation type. */
        public Builder operation(Operation operation) {
            this.operation = operation;
            return this;
        }

        /** Sets the affected page path. */
        public Builder pagePath(String pagePath) {
            this.pagePath = pagePath;
            return this;
        }

        /** Sets the summary. */
        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        /**
         * Builds the log entry.
         *
         * @return a new {@link WikiLogEntry} instance
         * @throws NullPointerException
         *             if timestamp or operation is null
         */
        public WikiLogEntry build() {
            return new WikiLogEntry(this);
        }
    }
}
