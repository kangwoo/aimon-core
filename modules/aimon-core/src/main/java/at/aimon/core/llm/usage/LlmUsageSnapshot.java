package at.aimon.core.llm.usage;

import java.util.Objects;

import at.aimon.core.llm.TokenUsage;

/**
 * Point-in-time snapshot of aggregated usage for a single {@link LlmUsageKey}.
 *
 * <p>
 * Immutable value object returned by {@link InMemoryLlmUsageRecorder#snapshot()}.
 */
public final class LlmUsageSnapshot {
    private final LlmUsageKey key;
    private final long callCount;
    private final TokenUsage totalUsage;

    /**
     * Creates a new snapshot.
     *
     * @param key
     *            the aggregation key (must not be null)
     * @param callCount
     *            the number of calls aggregated (must be >= 0)
     * @param totalUsage
     *            the accumulated token usage (must not be null)
     */
    public LlmUsageSnapshot(LlmUsageKey key, long callCount, TokenUsage totalUsage) {
        if (callCount < 0) {
            throw new IllegalArgumentException("Call count cannot be negative: " + callCount);
        }
        this.key = Objects.requireNonNull(key, "Key cannot be null");
        this.callCount = callCount;
        this.totalUsage = Objects.requireNonNull(totalUsage, "Total usage cannot be null");
    }

    public LlmUsageKey getKey() {
        return key;
    }

    public long getCallCount() {
        return callCount;
    }

    public TokenUsage getTotalUsage() {
        return totalUsage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmUsageSnapshot that = (LlmUsageSnapshot) o;
        return callCount == that.callCount && key.equals(that.key) && totalUsage.equals(that.totalUsage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, callCount, totalUsage);
    }

    @Override
    public String toString() {
        return "LlmUsageSnapshot{" + "key=" + key + ", callCount=" + callCount + ", totalUsage=" + totalUsage + '}';
    }
}
