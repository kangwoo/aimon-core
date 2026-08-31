package at.aimon.core.agent.compact;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a {@link CompactionGuard#maybeCompact} call.
 *
 * <p>
 * The decision describes whether the guard performed a compaction, asked the caller to warn/abort, or did nothing.
 * Immutable value object.
 *
 * <p>
 * Carries {@link #getEstimatedTokens()} and {@link #getBlockingLimit()} so callers building a
 * {@link at.aimon.core.agent.exception.ContextWindowExceededException} can populate its structured fields without
 * re-computing thresholds. {@code 0} is reserved for the default {@link #none()} instance which carries no token data.
 */
public final class CompactionDecision {

    /** What the caller should do given the decision. */
    public enum Action {
        /** No action required; the conversation is comfortably below all thresholds. */
        NONE,
        /** Above the warning threshold but not yet compactable; caller should log a warning. */
        WARN,
        /** A compaction attempt was performed; inspect {@link #getCompactionResult()} for outcome. */
        COMPACT,
        /** Above the blocking limit and compaction is unavailable; caller must abort the iteration. */
        BLOCK
    }

    private static final CompactionDecision NONE_NO_REASON = new CompactionDecision(Action.NONE, "", null, 0, 0);

    private final Action action;
    private final String reason;
    private final CompactionResult compactionResult;
    private final int estimatedTokens;
    private final int blockingLimit;

    private CompactionDecision(Action action, String reason, CompactionResult compactionResult, int estimatedTokens,
            int blockingLimit) {
        this.action = Objects.requireNonNull(action, "Action cannot be null");
        this.reason = Objects.requireNonNull(reason, "Reason cannot be null");
        this.compactionResult = compactionResult;
        this.estimatedTokens = estimatedTokens;
        this.blockingLimit = blockingLimit;
    }

    public static CompactionDecision none() {
        return NONE_NO_REASON;
    }

    public static CompactionDecision none(String reason) {
        return new CompactionDecision(Action.NONE, reason, null, 0, 0);
    }

    public static CompactionDecision warn(String reason) {
        return new CompactionDecision(Action.WARN, reason, null, 0, 0);
    }

    public static CompactionDecision warn(String reason, int estimatedTokens, int blockingLimit) {
        return new CompactionDecision(Action.WARN, reason, null, estimatedTokens, blockingLimit);
    }

    public static CompactionDecision block(String reason) {
        return new CompactionDecision(Action.BLOCK, reason, null, 0, 0);
    }

    public static CompactionDecision block(String reason, int estimatedTokens, int blockingLimit) {
        return new CompactionDecision(Action.BLOCK, reason, null, estimatedTokens, blockingLimit);
    }

    public static CompactionDecision compact(CompactionResult result, String reason) {
        Objects.requireNonNull(result, "CompactionResult cannot be null for COMPACT action");
        return new CompactionDecision(Action.COMPACT, reason, result, 0, 0);
    }

    public static CompactionDecision compact(CompactionResult result, String reason, int estimatedTokens,
            int blockingLimit) {
        Objects.requireNonNull(result, "CompactionResult cannot be null for COMPACT action");
        return new CompactionDecision(Action.COMPACT, reason, result, estimatedTokens, blockingLimit);
    }

    public Action getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public Optional<CompactionResult> getCompactionResult() {
        return Optional.ofNullable(compactionResult);
    }

    /** The estimated token count at the time of the decision. {@code 0} when not populated. */
    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    /** The blocking limit used for the decision. {@code 0} when not populated. */
    public int getBlockingLimit() {
        return blockingLimit;
    }

    @Override
    public String toString() {
        return "CompactionDecision{action=" + action + ", reason='" + reason + "'"
                + (compactionResult != null ? ", result=" + compactionResult : "")
                + (estimatedTokens > 0 ? ", estimatedTokens=" + estimatedTokens : "")
                + (blockingLimit > 0 ? ", blockingLimit=" + blockingLimit : "") + '}';
    }
}
