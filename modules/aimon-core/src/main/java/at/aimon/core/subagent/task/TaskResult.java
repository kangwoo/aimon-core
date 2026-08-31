package at.aimon.core.subagent.task;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentResultFormatter;

/**
 * The durable projection of what a background subagent task produced — the <b>result</b> half of the background-task
 * surface, stored by {@link TaskResultStore} under the same {@code taskId} keyspace as {@link BackgroundTaskStore}
 * (lifecycle) and {@link TaskOutputStore} (incremental log).
 *
 * <p>
 * <b>Why a projection and not {@link SubagentExecutionResult} itself.</b> A live result carries a whole
 * {@link at.aimon.core.agent.session.transcript.SessionSnapshot} — the subagent's full ReAct transcript, including
 * every
 * tool result and any binary content block it saw. Persisting that verbatim would repeat, in a new place, the record
 * bloat the session store was reshaped to avoid, and it would <em>double-store</em> the transcript: the background path
 * already saves the same snapshot per {@code taskId} through {@link SessionSnapshotStore}. So this type keeps exactly
 * what a caller asking "what did the task produce?" needs and drops the rest.
 *
 * <p>
 * <b>What is dropped.</b> The transcript (available from {@link SessionSnapshotStore} under the same {@code taskId},
 * subject to that store's ownership scoping) and the accrued {@link SubagentExecutionResult#getCost() cost}. The
 * start/end instants are left out as well, because {@link BackgroundTask} already records them for the same
 * {@code taskId}. A result that came back from a store is therefore not interchangeable with the in-heap one — it is
 * the answer, not the run. This is the same wire-safe projection
 * {@link at.aimon.core.agent.session.store.StoredAgentExecutionResult} performs on the session-scoped side.
 *
 * <p>
 * <b>Size policy.</b> {@link #from(SubagentExecutionResult)} bounds the answer/error text to
 * {@link #DEFAULT_MAX_SUMMARY_CHARS} characters, keeping the tail (agent conclusions land at the end) and marking the
 * elision, with {@link #isSummaryTruncated()} recording that it happened. This is a <em>storage</em> cap and is
 * distinct
 * from — and deliberately larger than — {@link SubagentResultFormatter#DEFAULT_MAX_CHARS}, the cap applied again when
 * the text is inlined into a parent agent's context. Anything cut here is still recoverable from the task's
 * {@link TaskOutputStore} log.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class TaskResult {

    /**
     * Default storage cap for the answer/error text, in characters (~128k).
     *
     * <p>
     * Four times {@link SubagentResultFormatter#DEFAULT_MAX_CHARS} so that persisting a result never loses text the
     * inline path would have shown, while still bounding what a single task can write into a shared backend.
     */
    public static final int DEFAULT_MAX_SUMMARY_CHARS = 128_000;

    private final boolean success;
    private final String finalAnswer;
    private final String errorMessage;
    private final CompletionReason completionReason;
    private final int iterationCount;
    private final long durationMillis;
    private final int totalTokens;
    private final boolean summaryTruncated;

    private TaskResult(Builder builder) {
        this.success = builder.success;
        this.finalAnswer = builder.finalAnswer;
        this.errorMessage = builder.errorMessage;
        this.completionReason = Objects.requireNonNull(builder.completionReason, "completionReason cannot be null");
        this.iterationCount = Math.max(0, builder.iterationCount);
        this.durationMillis = Math.max(0L, builder.durationMillis);
        this.totalTokens = Math.max(0, builder.totalTokens);
        this.summaryTruncated = builder.summaryTruncated;
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Projects a live execution result onto its durable form, bounding the answer/error text to
     * {@link #DEFAULT_MAX_SUMMARY_CHARS}.
     *
     * @param source
     *            the live result to project (must not be null)
     * @return the durable projection (never null)
     * @throws NullPointerException
     *             if source is null
     */
    public static TaskResult from(SubagentExecutionResult source) {
        return from(source, DEFAULT_MAX_SUMMARY_CHARS);
    }

    /**
     * Projects a live execution result onto its durable form with an explicit storage cap.
     *
     * @param source
     *            the live result to project (must not be null)
     * @param maxSummaryChars
     *            the storage cap for the answer/error text in characters; a non-positive value stores them unbounded
     * @return the durable projection (never null)
     * @throws NullPointerException
     *             if source is null
     */
    public static TaskResult from(SubagentExecutionResult source, int maxSummaryChars) {
        Objects.requireNonNull(source, "source cannot be null");
        final String answer = source.getFinalAnswer();
        final String error = source.getErrorMessage();
        final String boundedAnswer = SubagentResultFormatter.truncateTailKeep(answer, maxSummaryChars, null);
        final String boundedError = SubagentResultFormatter.truncateTailKeep(error, maxSummaryChars, null);
        final boolean truncated = wasTruncated(answer, maxSummaryChars) || wasTruncated(error, maxSummaryChars);
        return builder().success(source.isSuccess()).finalAnswer(answer == null ? null : boundedAnswer)
                .errorMessage(error == null ? null : boundedError).completionReason(source.getCompletionReason())
                .iterationCount(source.getIterationCount())
                .durationMillis(source.getMetadata().getDuration().toMillis())
                .totalTokens(source.getMetadata().getTokenUsage().getTotalTokens()).summaryTruncated(truncated).build();
    }

    /**
     * Mirrors {@link SubagentResultFormatter#truncateTailKeep(String, int, String)}'s own truncation condition rather
     * than comparing lengths before and after. A length comparison collides: the elision marker is
     * {@code "…[N chars omitted]…\n"}, exactly 21 characters when {@code N} has two digits, so text overflowing the cap
     * by exactly 21 characters comes back the same length it went in and would read as untruncated.
     */
    private static boolean wasTruncated(String original, int maxChars) {
        return original != null && maxChars > 0 && original.length() > maxChars;
    }

    /**
     * @return whether the task produced a successful result
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return {@code "SUCCESS"} or {@code "FAILURE"}, matching {@link SubagentExecutionResult#getStatus()}
     */
    public String getStatus() {
        return success ? "SUCCESS" : "FAILURE";
    }

    /**
     * @return the (possibly truncated) final answer, empty when the task failed
     */
    public Optional<String> getFinalAnswer() {
        return Optional.ofNullable(finalAnswer);
    }

    /**
     * @return the (possibly truncated) error message, empty when the task succeeded
     */
    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    /**
     * Returns the human-readable summary: the final answer for a success, the error message for a failure. Never null —
     * an absent value yields an empty string, matching {@link SubagentExecutionResult#getSummary()}'s never-null
     * contract.
     *
     * @return the summary text (never null)
     */
    public String getSummary() {
        final String summary = success ? finalAnswer : errorMessage;
        return summary != null ? summary : "";
    }

    /**
     * @return why the subagent's loop ended (never null)
     */
    public CompletionReason getCompletionReason() {
        return completionReason;
    }

    /**
     * @return the number of ReAct iterations the subagent ran
     */
    public int getIterationCount() {
        return iterationCount;
    }

    /**
     * @return how long the subagent ran
     */
    public Duration getDuration() {
        return Duration.ofMillis(durationMillis);
    }

    /**
     * @return the wall-clock duration in milliseconds
     */
    public long getDurationMillis() {
        return durationMillis;
    }

    /**
     * @return the total tokens the subagent consumed
     */
    public int getTotalTokens() {
        return totalTokens;
    }

    /**
     * @return whether the stored answer/error text was cut to fit {@link #DEFAULT_MAX_SUMMARY_CHARS}
     */
    public boolean isSummaryTruncated() {
        return summaryTruncated;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskResult)) {
            return false;
        }
        final TaskResult that = (TaskResult) o;
        return success == that.success && iterationCount == that.iterationCount && durationMillis == that.durationMillis
                && totalTokens == that.totalTokens && summaryTruncated == that.summaryTruncated
                && Objects.equals(finalAnswer, that.finalAnswer) && Objects.equals(errorMessage, that.errorMessage)
                && completionReason == that.completionReason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, finalAnswer, errorMessage, completionReason, iterationCount, durationMillis,
                totalTokens, summaryTruncated);
    }

    @Override
    public String toString() {
        return "TaskResult{status=" + getStatus() + ", reason=" + completionReason + ", iterations=" + iterationCount
                + ", tokens=" + totalTokens + ", durationMs=" + durationMillis + ", summaryChars="
                + getSummary().length() + (summaryTruncated ? " (truncated)" : "") + "}";
    }

    /** Builder for {@link TaskResult}. */
    public static final class Builder {

        private boolean success;
        private String finalAnswer;
        private String errorMessage;
        private CompletionReason completionReason = CompletionReason.COMPLETED;
        private int iterationCount;
        private long durationMillis;
        private int totalTokens;
        private boolean summaryTruncated;

        private Builder() {
        }

        /**
         * @param success
         *            whether the task produced a successful result
         * @return this builder
         */
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /**
         * @param finalAnswer
         *            the final answer (nullable)
         * @return this builder
         */
        public Builder finalAnswer(String finalAnswer) {
            this.finalAnswer = finalAnswer;
            return this;
        }

        /**
         * @param errorMessage
         *            the error message (nullable)
         * @return this builder
         */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /**
         * @param completionReason
         *            why the loop ended (must not be null)
         * @return this builder
         */
        public Builder completionReason(CompletionReason completionReason) {
            this.completionReason = completionReason;
            return this;
        }

        /**
         * @param iterationCount
         *            the number of ReAct iterations (negative clamped to 0)
         * @return this builder
         */
        public Builder iterationCount(int iterationCount) {
            this.iterationCount = iterationCount;
            return this;
        }

        /**
         * @param durationMillis
         *            the wall-clock duration in milliseconds (negative clamped to 0)
         * @return this builder
         */
        public Builder durationMillis(long durationMillis) {
            this.durationMillis = durationMillis;
            return this;
        }

        /**
         * @param totalTokens
         *            the total tokens consumed (negative clamped to 0)
         * @return this builder
         */
        public Builder totalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        /**
         * @param summaryTruncated
         *            whether the stored text was cut to fit the storage cap
         * @return this builder
         */
        public Builder summaryTruncated(boolean summaryTruncated) {
            this.summaryTruncated = summaryTruncated;
            return this;
        }

        /**
         * @return the built result
         */
        public TaskResult build() {
            return new TaskResult(this);
        }
    }
}
