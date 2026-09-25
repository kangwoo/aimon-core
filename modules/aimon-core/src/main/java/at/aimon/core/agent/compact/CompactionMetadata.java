package at.aimon.core.agent.compact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

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
    private final CompactionKind kind;
    private final int headTokens;
    private final int spanTokens;
    private final int tailTokens;
    private final int summaryTokens;
    private final long absorbedFromSeq;
    private final long absorbedToSeq;
    private final int blockingLimit;

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
        this.kind = Objects.requireNonNullElse(builder.kind, CompactionKind.FULL);
        this.headTokens = requireNonNegative(builder.headTokens, "headTokens");
        this.spanTokens = requireNonNegative(builder.spanTokens, "spanTokens");
        this.tailTokens = requireNonNegative(builder.tailTokens, "tailTokens");
        this.summaryTokens = requireNonNegative(builder.summaryTokens, "summaryTokens");
        if ((builder.absorbedFromSeq < 0) != (builder.absorbedToSeq < 0)
                || (builder.absorbedFromSeq >= 0 && builder.absorbedToSeq <= builder.absorbedFromSeq)) {
            throw new IllegalArgumentException("absorbed range must be empty or [from, to) with from < to, got ["
                    + builder.absorbedFromSeq + ", " + builder.absorbedToSeq + ")");
        }
        this.absorbedFromSeq = builder.absorbedFromSeq;
        this.absorbedToSeq = builder.absorbedToSeq;
        this.blockingLimit = requireNonNegative(builder.blockingLimit, "blockingLimit");
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

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return value;
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

    /** What the compaction did to the view. {@link CompactionKind#FULL} unless the engine said otherwise. */
    public CompactionKind getKind() {
        return kind;
    }

    /** Estimated tokens of the view's head after the compaction — a rolling engine's; {@code 0} otherwise. */
    public int getHeadTokens() {
        return headTokens;
    }

    /** Estimated tokens of the view's summary span (its marker pair) after the compaction; {@code 0} if unreported. */
    public int getSpanTokens() {
        return spanTokens;
    }

    /** Estimated tokens of the view's verbatim tail after the compaction; {@code 0} if unreported. */
    public int getTailTokens() {
        return tailTokens;
    }

    /** Estimated tokens of the summary text produced; {@code 0} when nothing was summarized or it was unreported. */
    public int getSummaryTokens() {
        return summaryTokens;
    }

    /** First seq of the log range the compaction newly took out of the verbatim view, when reported. */
    public OptionalLong getAbsorbedFromSeq() {
        return absorbedFromSeq < 0 ? OptionalLong.empty() : OptionalLong.of(absorbedFromSeq);
    }

    /** First seq after that range, when reported. */
    public OptionalLong getAbsorbedToSeq() {
        return absorbedToSeq < 0 ? OptionalLong.empty() : OptionalLong.of(absorbedToSeq);
    }

    /**
     * The blocking limit, in estimated tokens, the compaction was decided against; {@code 0} when the engine did not
     * report it. The rolling context engine reports it on every record it produces.
     */
    public int getBlockingLimit() {
        return blockingLimit;
    }

    /**
     * Whether the view was still at or above the blocking limit after this compaction. The rolling context engine
     * reaches that only at the blocking limit, when the messages the model has not answered yet keep the view there
     * after everything before them was absorbed; the view is then sent as it is (context-engine §13.10). Always
     * {@code false} when the limit was not reported.
     *
     * @return true when {@link #getBlockingLimit()} is reported and {@link #getPostCompactTokenCount()} reaches it
     */
    public boolean isOverBlockingLimit() {
        return blockingLimit > 0 && postCompactTokenCount >= blockingLimit;
    }

    /**
     * Returns a copy of this record with the blocking limit set.
     *
     * @param blockingLimit
     *            the blocking limit in estimated tokens (must be {@code >= 0}; {@code 0} means unreported)
     * @return the copy (never null)
     */
    public CompactionMetadata withBlockingLimit(int blockingLimit) {
        final Builder builder = builder().preCompactTokenCount(preCompactTokenCount)
                .postCompactTokenCount(postCompactTokenCount).messagesSummarized(messagesSummarized).trigger(trigger)
                .startedAt(startedAt).completedAt(completedAt).discoveredToolNames(discoveredToolNames).kind(kind)
                .viewShape(headTokens, spanTokens, tailTokens).summaryTokens(summaryTokens)
                .blockingLimit(blockingLimit);
        if (absorbedFromSeq >= 0) {
            builder.absorbedRange(absorbedFromSeq, absorbedToSeq);
        }
        return builder.build();
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
                && discoveredToolNames.equals(that.discoveredToolNames) && kind == that.kind
                && headTokens == that.headTokens && spanTokens == that.spanTokens && tailTokens == that.tailTokens
                && summaryTokens == that.summaryTokens && absorbedFromSeq == that.absorbedFromSeq
                && absorbedToSeq == that.absorbedToSeq && blockingLimit == that.blockingLimit;
    }

    @Override
    public int hashCode() {
        return Objects.hash(preCompactTokenCount, postCompactTokenCount, messagesSummarized, trigger, startedAt,
                completedAt, discoveredToolNames, kind, headTokens, spanTokens, tailTokens, summaryTokens,
                absorbedFromSeq, absorbedToSeq, blockingLimit);
    }

    @Override
    public String toString() {
        return "CompactionMetadata{trigger=" + trigger + ", kind=" + kind + ", preTokens=" + preCompactTokenCount
                + ", postTokens=" + postCompactTokenCount + ", messagesSummarized=" + messagesSummarized
                + ", durationMs=" + (completedAt.toEpochMilli() - startedAt.toEpochMilli()) + '}';
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
        private CompactionKind kind;
        private int headTokens;
        private int spanTokens;
        private int tailTokens;
        private int summaryTokens;
        private long absorbedFromSeq = -1;
        private long absorbedToSeq = -1;
        private int blockingLimit;

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

        /**
         * @param kind
         *            what the compaction did, or {@code null} for {@link CompactionKind#FULL}
         * @return this builder
         */
        public Builder kind(CompactionKind kind) {
            this.kind = kind;
            return this;
        }

        /**
         * Sets the view's shape after the compaction, in estimated tokens.
         *
         * @param head
         *            the head (must be {@code >= 0})
         * @param span
         *            the summary span's marker pair (must be {@code >= 0})
         * @param tail
         *            the verbatim tail (must be {@code >= 0})
         * @return this builder
         */
        public Builder viewShape(int head, int span, int tail) {
            this.headTokens = head;
            this.spanTokens = span;
            this.tailTokens = tail;
            return this;
        }

        public Builder summaryTokens(int summaryTokens) {
            this.summaryTokens = summaryTokens;
            return this;
        }

        /**
         * @param fromSeq
         *            the first seq newly absorbed
         * @param toSeq
         *            the first seq after it (must be greater than {@code fromSeq})
         * @return this builder
         */
        public Builder absorbedRange(long fromSeq, long toSeq) {
            this.absorbedFromSeq = fromSeq;
            this.absorbedToSeq = toSeq;
            return this;
        }

        /**
         * @param blockingLimit
         *            the blocking limit the compaction was decided against, in estimated tokens (must be {@code >= 0};
         *            {@code 0}, the default, means unreported)
         * @return this builder
         */
        public Builder blockingLimit(int blockingLimit) {
            this.blockingLimit = blockingLimit;
            return this;
        }

        public CompactionMetadata build() {
            return new CompactionMetadata(this);
        }
    }
}
