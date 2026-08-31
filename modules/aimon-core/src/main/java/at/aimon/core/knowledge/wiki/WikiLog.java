package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.List;

/**
 * An immutable snapshot of the wiki change log.
 *
 * <p>
 * Contains a list of {@link WikiLogEntry} instances ordered by timestamp (most recent first).
 *
 * @see WikiKnowledgeBaseAdmin#getLog(WikiScope, int)
 */
public final class WikiLog {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final List<WikiLogEntry> entries;
    private final int totalEntryCount;

    private WikiLog(Builder builder) {
        this.entries = builder.entries == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(builder.entries);
        if (builder.totalEntryCount < 0) {
            throw new IllegalArgumentException("totalEntryCount must be >= 0, got: " + builder.totalEntryCount);
        }
        this.totalEntryCount = builder.totalEntryCount;
    }

    /**
     * Returns the log entries (most recent first).
     *
     * @return an unmodifiable list of log entries (never null)
     */
    public List<WikiLogEntry> getEntries() {
        return entries;
    }

    /**
     * Returns the total number of log entries in the wiki, which may be larger than the size of {@link #getEntries()}
     * if
     * a limit was applied.
     *
     * @return the total entry count (>= 0)
     */
    public int getTotalEntryCount() {
        return totalEntryCount;
    }

    @Override
    public String toString() {
        return "WikiLog{entries=" + entries.size() + ", total=" + totalEntryCount + '}';
    }

    /**
     * Builder for {@link WikiLog}.
     */
    public static final class Builder {

        private List<WikiLogEntry> entries;
        private int totalEntryCount;

        private Builder() {
        }

        /**
         * Sets the log entries.
         *
         * @param entries
         *            the log entries (most recent first)
         * @return this builder
         */
        public Builder entries(List<WikiLogEntry> entries) {
            this.entries = entries;
            return this;
        }

        /**
         * Sets the total entry count.
         *
         * @param totalEntryCount
         *            the total number of log entries in the wiki
         * @return this builder
         */
        public Builder totalEntryCount(int totalEntryCount) {
            this.totalEntryCount = totalEntryCount;
            return this;
        }

        /**
         * Builds the wiki log.
         *
         * @return a new {@link WikiLog} instance
         * @throws IllegalArgumentException
         *             if totalEntryCount is negative
         */
        public WikiLog build() {
            return new WikiLog(this);
        }
    }
}
