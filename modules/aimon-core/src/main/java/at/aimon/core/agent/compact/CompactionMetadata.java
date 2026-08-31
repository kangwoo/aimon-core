package at.aimon.core.agent.compact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Captures observability metadata about a single {@link CompactionEngine#compact} invocation.
 *
 * <p>
 * Returned in {@link CompactionResult} regardless of success/failure so callers can record metrics, audit, and
 * troubleshoot without needing access to the engine's internals.
 *
 * <p>
 * Immutable value object.
 */
public final class CompactionMetadata {

    private final int preCompactTokenCount;
    private final int postCompactTokenCount;
    private final int messagesSummarized;
    private final CompactionTrigger trigger;
    private final Instant startedAt;
    private final Instant completedAt;
    private final List<String> discoveredToolNames;

    private CompactionMetadata(Builder builder) {
        this.preCompactTokenCount = builder.preCompactTokenCount;
        this.postCompactTokenCount = builder.postCompactTokenCount;
        this.messagesSummarized = builder.messagesSummarized;
        this.trigger = Objects.requireNonNull(builder.trigger, "Trigger cannot be null");
        this.startedAt = Objects.requireNonNull(builder.startedAt, "StartedAt cannot be null");
        this.completedAt = Objects.requireNonNull(builder.completedAt, "CompletedAt cannot be null");
        this.discoveredToolNames = builder.discoveredToolNames != null
                ? List.copyOf(builder.discoveredToolNames)
                : List.of();
        if (preCompactTokenCount < 0) {
            throw new IllegalArgumentException("preCompactTokenCount must be >= 0");
        }
        if (postCompactTokenCount < 0) {
            throw new IllegalArgumentException("postCompactTokenCount must be >= 0");
        }
        if (messagesSummarized < 0) {
            throw new IllegalArgumentException("messagesSummarized must be >= 0");
        }
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt cannot be before startedAt");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getPreCompactTokenCount() {
        return preCompactTokenCount;
    }

    public int getPostCompactTokenCount() {
        return postCompactTokenCount;
    }

    public int getMessagesSummarized() {
        return messagesSummarized;
    }

    public CompactionTrigger getTrigger() {
        return trigger;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<String> getDiscoveredToolNames() {
        return discoveredToolNames;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CompactionMetadata that = (CompactionMetadata) o;
        return preCompactTokenCount == that.preCompactTokenCount && postCompactTokenCount == that.postCompactTokenCount
                && messagesSummarized == that.messagesSummarized && trigger == that.trigger
                && startedAt.equals(that.startedAt) && completedAt.equals(that.completedAt)
                && discoveredToolNames.equals(that.discoveredToolNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(preCompactTokenCount, postCompactTokenCount, messagesSummarized, trigger, startedAt,
                completedAt, discoveredToolNames);
    }

    @Override
    public String toString() {
        return "CompactionMetadata{trigger=" + trigger + ", preTokens=" + preCompactTokenCount + ", postTokens="
                + postCompactTokenCount + ", messagesSummarized=" + messagesSummarized + ", durationMs="
                + (completedAt.toEpochMilli() - startedAt.toEpochMilli()) + '}';
    }

    /** Builder for {@link CompactionMetadata}. */
    public static final class Builder {
        private int preCompactTokenCount;
        private int postCompactTokenCount;
        private int messagesSummarized;
        private CompactionTrigger trigger;
        private Instant startedAt;
        private Instant completedAt;
        private List<String> discoveredToolNames;

        private Builder() {
        }

        public Builder preCompactTokenCount(int preCompactTokenCount) {
            this.preCompactTokenCount = preCompactTokenCount;
            return this;
        }

        public Builder postCompactTokenCount(int postCompactTokenCount) {
            this.postCompactTokenCount = postCompactTokenCount;
            return this;
        }

        public Builder messagesSummarized(int messagesSummarized) {
            this.messagesSummarized = messagesSummarized;
            return this;
        }

        public Builder trigger(CompactionTrigger trigger) {
            this.trigger = trigger;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder discoveredToolNames(List<String> discoveredToolNames) {
            this.discoveredToolNames = discoveredToolNames;
            return this;
        }

        public CompactionMetadata build() {
            return new CompactionMetadata(this);
        }
    }
}
