package at.aimon.core.agent.session.transcript;

import java.util.List;
import java.util.Objects;

/**
 * The one range of a session log the view shows as a summary instead of the entries themselves.
 *
 * <p>
 * The view replaces {@code [fromSeq, toSeq)} with the marker pair a compaction has always produced — a boundary
 * message and a summary message, both suffixed with {@link #getBoundaryId()}. Everything the pair is built from is
 * stored here: the summary text, which was produced non-deterministically and so must be kept rather than
 * recomputed, and the boundary's metadata ({@link #getTrigger()}, {@link #getPreTokenCount()},
 * {@link #getMessagesSummarized()}, {@link #getDiscoveredToolNames()}), which would otherwise have to be gathered
 * again from entries that may no longer be in the record.
 *
 * <p>
 * The trigger is kept as the {@code CompactionTrigger} constant's name. This package sits below the compaction
 * package, and the name is also exactly what the boundary message carries.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class SummarySpan {

    private final SeqRange range;
    private final String summaryText;
    private final String boundaryId;
    private final String trigger;
    private final int preTokenCount;
    private final int messagesSummarized;
    private final List<String> discoveredToolNames;

    private SummarySpan(Builder builder) {
        this.range = SeqRange.of(builder.fromSeq, builder.toSeq);
        this.summaryText = Objects.requireNonNull(builder.summaryText, "summaryText cannot be null");
        this.boundaryId = Objects.requireNonNull(builder.boundaryId, "boundaryId cannot be null");
        this.trigger = Objects.requireNonNull(builder.trigger, "trigger cannot be null");
        if (builder.preTokenCount < 0 || builder.messagesSummarized < 0) {
            throw new IllegalArgumentException("preTokenCount and messagesSummarized cannot be negative");
        }
        this.preTokenCount = builder.preTokenCount;
        this.messagesSummarized = builder.messagesSummarized;
        this.discoveredToolNames = List.copyOf(builder.discoveredToolNames);
    }

    /**
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return a builder pre-filled with this span's values (never null)
     */
    public Builder toBuilder() {
        return new Builder().fromSeq(range.getFromSeq()).toSeq(range.getToSeq()).summaryText(summaryText)
                .boundaryId(boundaryId).trigger(trigger).preTokenCount(preTokenCount)
                .messagesSummarized(messagesSummarized).discoveredToolNames(discoveredToolNames);
    }

    /**
     * @return the seqs the summary stands in for (never null)
     */
    public SeqRange getRange() {
        return range;
    }

    /**
     * @return the first seq the summary stands in for
     */
    public long getFromSeq() {
        return range.getFromSeq();
    }

    /**
     * @return the first seq after the summarized range
     */
    public long getToSeq() {
        return range.getToSeq();
    }

    /**
     * @return the summary text (never null)
     */
    public String getSummaryText() {
        return summaryText;
    }

    /**
     * @return the id suffixing the marker pair (never null)
     */
    public String getBoundaryId() {
        return boundaryId;
    }

    /**
     * @return the name of the compaction trigger that produced the summary (never null)
     */
    public String getTrigger() {
        return trigger;
    }

    /**
     * @return the estimated size of what was summarized, as the boundary message reports it
     */
    public int getPreTokenCount() {
        return preTokenCount;
    }

    /**
     * @return how many view messages were folded into the summary, as the boundary message reports it
     */
    public int getMessagesSummarized() {
        return messagesSummarized;
    }

    /**
     * @return the tool names seen in what was summarized, in first-seen order (never null)
     */
    public List<String> getDiscoveredToolNames() {
        return discoveredToolNames;
    }

    /**
     * Returns this span ending at {@code newToSeq} instead — the cut a rewind makes through a span it lies inside.
     * The summary is kept as it is.
     *
     * @param newToSeq
     *            the new end (must be greater than {@link #getFromSeq()})
     * @return the shorter span (never null)
     */
    public SummarySpan endingAt(long newToSeq) {
        return toBuilder().toSeq(newToSeq).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SummarySpan other)) {
            return false;
        }
        return preTokenCount == other.preTokenCount && messagesSummarized == other.messagesSummarized
                && range.equals(other.range) && summaryText.equals(other.summaryText)
                && boundaryId.equals(other.boundaryId) && trigger.equals(other.trigger)
                && discoveredToolNames.equals(other.discoveredToolNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(range, summaryText, boundaryId, trigger, preTokenCount, messagesSummarized,
                discoveredToolNames);
    }

    @Override
    public String toString() {
        return "SummarySpan{range=" + range + ", boundaryId=" + boundaryId + ", trigger=" + trigger
                + ", messagesSummarized=" + messagesSummarized + "}";
    }

    /** Builder for {@link SummarySpan}. */
    public static final class Builder {

        private long fromSeq;
        private long toSeq;
        private String summaryText;
        private String boundaryId;
        private String trigger;
        private int preTokenCount;
        private int messagesSummarized;
        private List<String> discoveredToolNames = List.of();

        private Builder() {
        }

        /**
         * @param fromSeq
         *            the first seq the summary stands in for
         * @return this builder
         */
        public Builder fromSeq(long fromSeq) {
            this.fromSeq = fromSeq;
            return this;
        }

        /**
         * @param toSeq
         *            the first seq after the summarized range
         * @return this builder
         */
        public Builder toSeq(long toSeq) {
            this.toSeq = toSeq;
            return this;
        }

        /**
         * @param summaryText
         *            the summary (must not be null)
         * @return this builder
         */
        public Builder summaryText(String summaryText) {
            this.summaryText = summaryText;
            return this;
        }

        /**
         * @param boundaryId
         *            the id suffixing the marker pair (must not be null)
         * @return this builder
         */
        public Builder boundaryId(String boundaryId) {
            this.boundaryId = boundaryId;
            return this;
        }

        /**
         * @param trigger
         *            the compaction trigger's name (must not be null)
         * @return this builder
         */
        public Builder trigger(String trigger) {
            this.trigger = trigger;
            return this;
        }

        /**
         * @param preTokenCount
         *            the estimated size of what was summarized
         * @return this builder
         */
        public Builder preTokenCount(int preTokenCount) {
            this.preTokenCount = preTokenCount;
            return this;
        }

        /**
         * @param messagesSummarized
         *            how many view messages were folded into the summary
         * @return this builder
         */
        public Builder messagesSummarized(int messagesSummarized) {
            this.messagesSummarized = messagesSummarized;
            return this;
        }

        /**
         * @param discoveredToolNames
         *            the tool names seen in what was summarized (must not be null nor contain null)
         * @return this builder
         */
        public Builder discoveredToolNames(List<String> discoveredToolNames) {
            this.discoveredToolNames = Objects.requireNonNull(discoveredToolNames,
                    "discoveredToolNames cannot be null");
            return this;
        }

        /**
         * @return the span (never null)
         * @throws IllegalArgumentException
         *             if the range is empty or a count is negative
         */
        public SummarySpan build() {
            return new SummarySpan(this);
        }
    }
}
