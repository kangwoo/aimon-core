package at.aimon.core.agent.compact;

import java.util.Objects;

/**
 * What a {@link CompactionObserver} is told when one compaction attempt begins: what triggered it, which strategy is
 * about to run, whether the blocking limit forced it, and how large the view or buffer was before it.
 *
 * <p>
 * Built only once a compaction is actually attempted — after the decision ladder chose to compact and something is
 * there to summarize or elide — so an observer never hears of a pass that ended without trying.
 *
 * <p>
 * Immutable value object built via {@link Builder}.
 */
public final class CompactionStart {

    /** The strategy that rewrites the transcript buffer in place (a version-1 log). */
    public static final String IN_PLACE = "in-place";

    /** The strategy that records the summary as the view state's span, leaving the log append-only. */
    public static final String VIEW = "view";

    /** The rolling context engine's strategy: widen the one summary span, or elide tool results from the view. */
    public static final String ROLLING = "rolling";

    private final CompactionTrigger trigger;
    private final String strategy;
    private final boolean blockingForced;
    private final int messagesBefore;

    private CompactionStart(Builder builder) {
        this.trigger = Objects.requireNonNull(builder.trigger, "trigger cannot be null");
        this.strategy = Objects.requireNonNull(builder.strategy, "strategy cannot be null");
        if (builder.messagesBefore < 0) {
            throw new IllegalArgumentException("messagesBefore must be >= 0, got: " + builder.messagesBefore);
        }
        this.blockingForced = builder.blockingForced;
        this.messagesBefore = builder.messagesBefore;
    }

    /**
     * Returns a new builder.
     *
     * @return a builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * What caused the attempt.
     *
     * @return the trigger (never null)
     */
    public CompactionTrigger getTrigger() {
        return trigger;
    }

    /**
     * Which strategy runs: {@link #IN_PLACE}, {@link #VIEW} or {@link #ROLLING}.
     *
     * @return the strategy (never null)
     */
    public String getStrategy() {
        return strategy;
    }

    /**
     * Whether the view had reached the blocking limit, so the compaction was forced past the circuit breaker.
     *
     * @return {@code true} for a blocking-limit compaction
     */
    public boolean isBlockingForced() {
        return blockingForced;
    }

    /**
     * How many messages the view (view and rolling strategies) or the buffer (in place) held before the attempt.
     *
     * @return the message count ({@code >= 0})
     */
    public int getMessagesBefore() {
        return messagesBefore;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CompactionStart that = (CompactionStart) o;
        return blockingForced == that.blockingForced && messagesBefore == that.messagesBefore && trigger == that.trigger
                && strategy.equals(that.strategy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trigger, strategy, blockingForced, messagesBefore);
    }

    @Override
    public String toString() {
        return "CompactionStart{trigger=" + trigger + ", strategy=" + strategy + ", blockingForced=" + blockingForced
                + ", messagesBefore=" + messagesBefore + '}';
    }

    /** Builder for {@link CompactionStart}. */
    public static final class Builder {
        private CompactionTrigger trigger;
        private String strategy;
        private boolean blockingForced;
        private int messagesBefore;

        private Builder() {
        }

        /**
         * @param trigger
         *            what caused the attempt (must not be null)
         * @return this builder
         */
        public Builder trigger(CompactionTrigger trigger) {
            this.trigger = trigger;
            return this;
        }

        /**
         * @param strategy
         *            the strategy, one of {@link #IN_PLACE}, {@link #VIEW}, {@link #ROLLING} (must not be null)
         * @return this builder
         */
        public Builder strategy(String strategy) {
            this.strategy = strategy;
            return this;
        }

        /**
         * @param blockingForced
         *            whether the blocking limit forced the attempt
         * @return this builder
         */
        public Builder blockingForced(boolean blockingForced) {
            this.blockingForced = blockingForced;
            return this;
        }

        /**
         * @param messagesBefore
         *            the message count before the attempt (must be {@code >= 0})
         * @return this builder
         */
        public Builder messagesBefore(int messagesBefore) {
            this.messagesBefore = messagesBefore;
            return this;
        }

        /**
         * @return the start (never null)
         */
        public CompactionStart build() {
            return new CompactionStart(this);
        }
    }
}
