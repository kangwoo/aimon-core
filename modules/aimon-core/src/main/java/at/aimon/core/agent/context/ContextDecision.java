package at.aimon.core.agent.context;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionResult;

/**
 * Outcome of {@link ContextEngine#prepare(ContextRequest)}: the view to send and what the engine did to produce it.
 *
 * <p>
 * The action is {@link CompactionDecision.Action} itself &mdash; same names, same meaning. {@code BLOCK} means the
 * caller must end the execution with a {@link at.aimon.core.agent.exception.ContextWindowExceededException}, built from
 * {@link #getEstimatedTokens()} and {@link #getBlockingLimit()}. {@code COMPACT} means a compaction was attempted, and
 * {@link #getCompactionMetadata()} describes it whether or not it succeeded.
 *
 * <p>
 * Immutable value object built via {@link Builder} or {@link #from(CompactionDecision, ContextView)}.
 */
public final class ContextDecision {

    private final ContextView view;
    private final CompactionDecision.Action action;
    private final String reason;
    private final CompactionMetadata compactionMetadata;
    private final int estimatedTokens;
    private final int blockingLimit;
    private final int viewSizeBefore;

    private ContextDecision(Builder builder) {
        this.view = Objects.requireNonNull(builder.view, "view cannot be null");
        this.action = Objects.requireNonNull(builder.action, "action cannot be null");
        this.reason = Objects.requireNonNull(builder.reason, "reason cannot be null");
        this.compactionMetadata = builder.compactionMetadata;
        this.estimatedTokens = builder.estimatedTokens;
        this.blockingLimit = builder.blockingLimit;
        this.viewSizeBefore = builder.viewSizeBefore;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A decision that did nothing to the transcript.
     *
     * @param view
     *            the view to send (must not be null)
     * @return the decision (never null)
     */
    public static ContextDecision none(ContextView view) {
        return builder().view(view).action(CompactionDecision.Action.NONE).build();
    }

    /**
     * Carries a {@link CompactionDecision} over unchanged, attaching the view computed after it.
     *
     * @param decision
     *            the guard's decision (must not be null)
     * @param view
     *            the view computed after the decision took effect (must not be null)
     * @return the decision (never null)
     */
    public static ContextDecision from(CompactionDecision decision, ContextView view) {
        return from(decision, view, -1);
    }

    /**
     * Carries a {@link CompactionDecision} over unchanged, attaching the view computed after it and the size of the
     * view it was taken on.
     *
     * @param decision
     *            the guard's decision (must not be null)
     * @param view
     *            the view computed after the decision took effect (must not be null)
     * @param viewSizeBefore
     *            how many messages the view held before, or a negative value for unknown
     * @return the decision (never null)
     */
    public static ContextDecision from(CompactionDecision decision, ContextView view, int viewSizeBefore) {
        Objects.requireNonNull(decision, "decision cannot be null");
        return builder().view(view).action(decision.getAction()).reason(decision.getReason())
                .compactionMetadata(decision.getCompactionResult().map(CompactionResult::getMetadata).orElse(null))
                .estimatedTokens(decision.getEstimatedTokens()).blockingLimit(decision.getBlockingLimit())
                .viewSizeBefore(viewSizeBefore).build();
    }

    /** The messages to send on this call. */
    public ContextView getView() {
        return view;
    }

    public CompactionDecision.Action getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    /** Describes the compaction attempt when {@link #getAction()} is {@code COMPACT}, successful or not. */
    public Optional<CompactionMetadata> getCompactionMetadata() {
        return Optional.ofNullable(compactionMetadata);
    }

    /** The estimated size the decision was taken on. {@code 0} when not populated. */
    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    /** The blocking limit the decision was taken against. {@code 0} when not populated. */
    public int getBlockingLimit() {
        return blockingLimit;
    }

    /**
     * How many messages the view held before the decision took effect — the "before" of a compaction's boundary event,
     * compared with {@link #getView()} as the "after". Both are view sizes: with an append-only log, compaction does
     * not shrink the log, only the view (context-engine §10). Empty when the engine did not report it.
     */
    public OptionalInt getViewSizeBefore() {
        return viewSizeBefore < 0 ? OptionalInt.empty() : OptionalInt.of(viewSizeBefore);
    }

    @Override
    public String toString() {
        return "ContextDecision{action=" + action + ", reason='" + reason + "', view=" + view
                + (compactionMetadata != null ? ", compaction=" + compactionMetadata : "")
                + (estimatedTokens > 0 ? ", estimatedTokens=" + estimatedTokens : "")
                + (blockingLimit > 0 ? ", blockingLimit=" + blockingLimit : "") + '}';
    }

    /** Builder for {@link ContextDecision}. */
    public static final class Builder {
        private ContextView view;
        private CompactionDecision.Action action;
        private String reason = "";
        private CompactionMetadata compactionMetadata;
        private int estimatedTokens;
        private int blockingLimit;
        private int viewSizeBefore = -1;

        private Builder() {
        }

        public Builder view(ContextView view) {
            this.view = view;
            return this;
        }

        public Builder action(CompactionDecision.Action action) {
            this.action = action;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder compactionMetadata(CompactionMetadata compactionMetadata) {
            this.compactionMetadata = compactionMetadata;
            return this;
        }

        public Builder estimatedTokens(int estimatedTokens) {
            this.estimatedTokens = estimatedTokens;
            return this;
        }

        public Builder blockingLimit(int blockingLimit) {
            this.blockingLimit = blockingLimit;
            return this;
        }

        /**
         * @param viewSizeBefore
         *            how many messages the view held before the decision took effect, or a negative value for unknown
         * @return this builder
         */
        public Builder viewSizeBefore(int viewSizeBefore) {
            this.viewSizeBefore = viewSizeBefore;
            return this;
        }

        public ContextDecision build() {
            return new ContextDecision(this);
        }
    }
}
