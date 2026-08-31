package at.aimon.core.knowledge.wiki;

import java.util.Collections;
import java.util.List;

/**
 * Immutable result of a {@link WikiKnowledgeBaseAdmin#migrateFrontmatter(WikiScope)} pass.
 *
 * <p>
 * Reports how many pages had their frontmatter updated, how many were already up-to-date, and any per-page
 * errors that did not abort the rest of the pass. The migration is intentionally idempotent: re-running it
 * on a fully-migrated wiki returns a result with {@code migratedCount=0} and {@code skippedCount} equal to
 * the total page count.
 */
public final class MigrationResult {

    /**
     * Returns an empty result with all counters at zero.
     *
     * @return an empty result
     */
    public static MigrationResult empty() {
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

    private final int migratedCount;
    private final int skippedCount;
    private final long durationMs;
    private final List<String> errors;

    private MigrationResult(Builder builder) {
        if (builder.migratedCount < 0) {
            throw new IllegalArgumentException("migratedCount must be >= 0, got: " + builder.migratedCount);
        }
        if (builder.skippedCount < 0) {
            throw new IllegalArgumentException("skippedCount must be >= 0, got: " + builder.skippedCount);
        }
        if (builder.durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0, got: " + builder.durationMs);
        }
        this.migratedCount = builder.migratedCount;
        this.skippedCount = builder.skippedCount;
        this.durationMs = builder.durationMs;
        this.errors = builder.errors == null ? Collections.emptyList() : Collections.unmodifiableList(builder.errors);
    }

    /** Returns the number of pages whose frontmatter was rewritten. */
    public int getMigratedCount() {
        return migratedCount;
    }

    /**
     * Returns the number of pages left untouched, either because they were already migrated or because they
     * had no recognizable frontmatter to update.
     */
    public int getSkippedCount() {
        return skippedCount;
    }

    /** Returns the total duration of the migration pass in milliseconds. */
    public long getDurationMs() {
        return durationMs;
    }

    /** Returns the per-page error messages, or an empty list when the pass succeeded cleanly. */
    public List<String> getErrors() {
        return errors;
    }

    @Override
    public String toString() {
        return "MigrationResult{migrated=" + migratedCount + ", skipped=" + skippedCount + ", durationMs=" + durationMs
                + ", errors=" + errors.size() + '}';
    }

    /** Builder for {@link MigrationResult}. */
    public static final class Builder {

        private int migratedCount;
        private int skippedCount;
        private long durationMs;
        private List<String> errors;

        private Builder() {
        }

        /** Sets the migrated page count. */
        public Builder migratedCount(int migratedCount) {
            this.migratedCount = migratedCount;
            return this;
        }

        /** Sets the skipped page count. */
        public Builder skippedCount(int skippedCount) {
            this.skippedCount = skippedCount;
            return this;
        }

        /** Sets the duration in milliseconds. */
        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        /** Sets the error messages. */
        public Builder errors(List<String> errors) {
            this.errors = errors;
            return this;
        }

        /** Builds the result. */
        public MigrationResult build() {
            return new MigrationResult(this);
        }
    }
}
