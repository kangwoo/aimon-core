package at.aimon.core.agent.session.transcript;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Where a node seals session logs and how: the segment store, the fenced view deletes go through, and the three knobs
 * of session-log §5.
 *
 * <ul>
 * <li>{@code minSealTokens} (default {@value #DEFAULT_MIN_SEAL_TOKENS}) — a sealable run smaller than this stays in the
 * record; sealing costs a segment write, so it is not done for scraps
 * <li>{@code segmentGcGrace} (default one hour, never zero) — how old an orphan segment must be before garbage
 * collection deletes it. It protects a sealing in progress on a node that lost its lease and does not know it yet, so
 * it must be well above the lease TTL
 * <li>{@code maxReadTokens} (default {@value #DEFAULT_MAX_READ_TOKENS}) — the page size of {@link SessionLogReader}
 * </ul>
 *
 * <p>
 * {@code deleteStore} is the view deletes go through. Give it {@code SessionStore.segments(segmentStore)} when the
 * assembly has a {@code SessionStore}, so a node that lost a session cannot delete the new holder's segments; left
 * unset, deletes go to {@code segmentStore} itself, which is right only for an assembly with no lease.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class SessionLogStorage {

    /** Default {@code minSealTokens}. */
    public static final int DEFAULT_MIN_SEAL_TOKENS = 32_000;

    /** Default {@code maxReadTokens}. */
    public static final int DEFAULT_MAX_READ_TOKENS = 32_000;

    /** Default {@code segmentGcGrace}. */
    public static final Duration DEFAULT_SEGMENT_GC_GRACE = Duration.ofHours(1);

    private final SessionLogSegmentStore segmentStore;
    private final SessionLogSegmentStore deleteStore;
    private final int minSealTokens;
    private final Duration segmentGcGrace;
    private final int maxReadTokens;
    private final TokenEstimator tokenEstimator;
    private final Clock clock;

    private SessionLogStorage(Builder builder) {
        this.segmentStore = Objects.requireNonNull(builder.segmentStore, "segmentStore cannot be null");
        this.deleteStore = builder.deleteStore != null ? builder.deleteStore : builder.segmentStore;
        this.segmentGcGrace = Objects.requireNonNull(builder.segmentGcGrace, "segmentGcGrace cannot be null");
        this.tokenEstimator = Objects.requireNonNull(builder.tokenEstimator, "tokenEstimator cannot be null");
        this.clock = Objects.requireNonNull(builder.clock, "clock cannot be null");
        this.minSealTokens = builder.minSealTokens;
        this.maxReadTokens = builder.maxReadTokens;
        if (segmentGcGrace.isZero() || segmentGcGrace.isNegative()) {
            throw new IllegalArgumentException("segmentGcGrace must be positive: a zero grace deletes the segments of a"
                    + " sealing another node still has in progress, got " + segmentGcGrace);
        }
        if (minSealTokens < 0) {
            throw new IllegalArgumentException("minSealTokens cannot be negative, got " + minSealTokens);
        }
    }

    /**
     * @param segmentStore
     *            the store segments are written to and read from (must not be null)
     * @return a builder with the defaults (never null)
     */
    public static Builder builder(SessionLogSegmentStore segmentStore) {
        return new Builder().segmentStore(segmentStore);
    }

    /**
     * @return the store segments are written to and read from (never null)
     */
    public SessionLogSegmentStore getSegmentStore() {
        return segmentStore;
    }

    /**
     * @return the view deletes go through (never null)
     */
    public SessionLogSegmentStore getDeleteStore() {
        return deleteStore;
    }

    /**
     * @return the smallest run worth sealing, in estimated tokens
     */
    public int getMinSealTokens() {
        return minSealTokens;
    }

    /**
     * @return how old an orphan segment must be before it is deleted (never null, positive)
     */
    public Duration getSegmentGcGrace() {
        return segmentGcGrace;
    }

    /**
     * @return the page size of a log reader, in estimated tokens; zero or less means unbounded
     */
    public int getMaxReadTokens() {
        return maxReadTokens;
    }

    /**
     * @return the estimator the sizes are measured with (never null)
     */
    public TokenEstimator getTokenEstimator() {
        return tokenEstimator;
    }

    /**
     * @return the clock segment creation times and the grace period are read from (never null)
     */
    public Clock getClock() {
        return clock;
    }

    /** Builder for {@link SessionLogStorage}. */
    public static final class Builder {

        private SessionLogSegmentStore segmentStore;
        private SessionLogSegmentStore deleteStore;
        private int minSealTokens = DEFAULT_MIN_SEAL_TOKENS;
        private Duration segmentGcGrace = DEFAULT_SEGMENT_GC_GRACE;
        private int maxReadTokens = DEFAULT_MAX_READ_TOKENS;
        private TokenEstimator tokenEstimator = new HeuristicTokenEstimator();
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * @param segmentStore
         *            the store segments are written to and read from (must not be null)
         * @return this builder
         */
        public Builder segmentStore(SessionLogSegmentStore segmentStore) {
            this.segmentStore = segmentStore;
            return this;
        }

        /**
         * @param deleteStore
         *            the fenced view deletes go through (null: the segment store itself)
         * @return this builder
         */
        public Builder deleteStore(SessionLogSegmentStore deleteStore) {
            this.deleteStore = deleteStore;
            return this;
        }

        /**
         * @param minSealTokens
         *            the smallest run worth sealing, in estimated tokens (not negative)
         * @return this builder
         */
        public Builder minSealTokens(int minSealTokens) {
            this.minSealTokens = minSealTokens;
            return this;
        }

        /**
         * @param segmentGcGrace
         *            how old an orphan must be before it is deleted (must be positive)
         * @return this builder
         */
        public Builder segmentGcGrace(Duration segmentGcGrace) {
            this.segmentGcGrace = segmentGcGrace;
            return this;
        }

        /**
         * @param maxReadTokens
         *            the page size of a log reader; zero or less means unbounded
         * @return this builder
         */
        public Builder maxReadTokens(int maxReadTokens) {
            this.maxReadTokens = maxReadTokens;
            return this;
        }

        /**
         * @param tokenEstimator
         *            the estimator sizes are measured with (must not be null)
         * @return this builder
         */
        public Builder tokenEstimator(TokenEstimator tokenEstimator) {
            this.tokenEstimator = tokenEstimator;
            return this;
        }

        /**
         * @param clock
         *            the clock creation times and the grace period are read from (must not be null)
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * @return the storage settings (never null)
         * @throws IllegalArgumentException
         *             if the grace period is not positive or {@code minSealTokens} is negative
         */
        public SessionLogStorage build() {
            return new SessionLogStorage(this);
        }
    }
}
