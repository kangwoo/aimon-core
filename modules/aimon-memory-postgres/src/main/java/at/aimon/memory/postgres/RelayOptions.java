package at.aimon.memory.postgres;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable configuration for {@link KnowledgeStoreOutboxRelay}.
 *
 * <p>
 * Use {@link #defaults()} for sensible defaults, or build a custom instance via
 * {@link #builder()}. Field meanings:
 * <ul>
 * <li>{@code pollBatchSize} — max rows claimed per {@code drainOnce()} pass
 * (default 32).
 * <li>{@code claimDurationSeconds} — how long a claimed row remains hidden
 * from other workers via {@code claimed_until} (default 60).
 * <li>{@code pollIntervalMillis} — sleep between polls in the background loop
 * (default 500).
 * <li>{@code maxAttempts} — after this many failed dispatches the row is
 * marked as a poison pill and excluded from drain (default 10).
 * <li>{@code nodeId} — value written to {@code claimed_by} so concurrent
 * relays in different processes can be told apart (default {@code "node-" + UUID}).
 * </ul>
 */
public final class RelayOptions {

    /** Default batch size per drain pass. */
    public static final int DEFAULT_POLL_BATCH_SIZE = 32;

    /** Default claim duration in seconds. */
    public static final int DEFAULT_CLAIM_DURATION_SECONDS = 60;

    /** Default poll interval in milliseconds. */
    public static final long DEFAULT_POLL_INTERVAL_MILLIS = 500L;

    /** Default maximum dispatch attempts before poison-pilling a row. */
    public static final int DEFAULT_MAX_ATTEMPTS = 10;

    private final int pollBatchSize;
    private final int claimDurationSeconds;
    private final long pollIntervalMillis;
    private final int maxAttempts;
    private final String nodeId;

    private RelayOptions(Builder b) {
        if (b.pollBatchSize < 1) {
            throw new IllegalArgumentException("pollBatchSize must be >= 1, got: " + b.pollBatchSize);
        }
        if (b.claimDurationSeconds < 1) {
            throw new IllegalArgumentException("claimDurationSeconds must be >= 1, got: " + b.claimDurationSeconds);
        }
        if (b.pollIntervalMillis < 1L) {
            throw new IllegalArgumentException("pollIntervalMillis must be >= 1, got: " + b.pollIntervalMillis);
        }
        if (b.maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + b.maxAttempts);
        }
        this.pollBatchSize = b.pollBatchSize;
        this.claimDurationSeconds = b.claimDurationSeconds;
        this.pollIntervalMillis = b.pollIntervalMillis;
        this.maxAttempts = b.maxAttempts;
        this.nodeId = Objects.requireNonNull(b.nodeId, "nodeId must not be null");
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
    }

    /**
     * Returns options with documented defaults.
     *
     * @return default {@link RelayOptions}
     */
    public static RelayOptions defaults() {
        return builder().build();
    }

    /**
     * Returns a fresh builder pre-populated with default values.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public int getPollBatchSize() {
        return pollBatchSize;
    }

    public int getClaimDurationSeconds() {
        return claimDurationSeconds;
    }

    public long getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getNodeId() {
        return nodeId;
    }

    @Override
    public String toString() {
        return "RelayOptions{batch=" + pollBatchSize + ", claim=" + claimDurationSeconds + "s, poll="
                + pollIntervalMillis + "ms, maxAttempts=" + maxAttempts + ", nodeId='" + nodeId + "'}";
    }

    /** Builder for {@link RelayOptions}. */
    public static final class Builder {
        private int pollBatchSize = DEFAULT_POLL_BATCH_SIZE;
        private int claimDurationSeconds = DEFAULT_CLAIM_DURATION_SECONDS;
        private long pollIntervalMillis = DEFAULT_POLL_INTERVAL_MILLIS;
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private String nodeId = "node-" + UUID.randomUUID();

        private Builder() {
        }

        public Builder pollBatchSize(int pollBatchSize) {
            this.pollBatchSize = pollBatchSize;
            return this;
        }

        public Builder claimDurationSeconds(int claimDurationSeconds) {
            this.claimDurationSeconds = claimDurationSeconds;
            return this;
        }

        public Builder pollIntervalMillis(long pollIntervalMillis) {
            this.pollIntervalMillis = pollIntervalMillis;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public RelayOptions build() {
            return new RelayOptions(this);
        }
    }
}
