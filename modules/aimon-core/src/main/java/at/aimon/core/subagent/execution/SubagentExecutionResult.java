package at.aimon.core.subagent.execution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.Money;

/**
 * Represents the result of a subagent execution.
 *
 * <p>
 * Contains the final answer, session snapshot, execution metadata, and status information about the execution.
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SubagentExecutionResult result = executor.execute(context, request);
 *
 *     if (result.isSuccess()) {
 *         System.out.println("Summary: " + result.getFinalAnswer());
 *         System.out.println("Iterations: " + result.getIterationCount());
 *         System.out.println("Duration: " + result.getMetadata().getDuration().toMillis() + "ms");
 *         System.out.println("Tokens: " + result.getMetadata().getTokenUsage().getTotalTokens());
 *     } else {
 *         System.err.println("Error: " + result.getErrorMessage());
 *     }
 * }
 * </pre>
 */
public final class SubagentExecutionResult {
    /**
     * Creates a successful execution result.
     *
     * @param finalAnswer
     *            The final answer (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A new successful SubagentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static SubagentExecutionResult success(String finalAnswer, SessionSnapshot snapshot,
            ExecutionMetadata metadata) {
        return success(finalAnswer, snapshot, metadata, CompletionReason.COMPLETED);
    }

    /**
     * Creates a successful execution result carrying an explicit {@link CompletionReason}.
     *
     * @param finalAnswer
     *            The final answer (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param completionReason
     *            Why the subagent stopped (typically {@link CompletionReason#COMPLETED}; must not be null)
     * @return A new successful SubagentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static SubagentExecutionResult success(String finalAnswer, SessionSnapshot snapshot,
            ExecutionMetadata metadata, CompletionReason completionReason) {
        return success(finalAnswer, snapshot, metadata, completionReason, Money.zeroUsd());
    }

    /**
     * Creates a successful execution result carrying an explicit {@link CompletionReason} and estimated {@link Money}
     * cost of the LLM calls it made.
     *
     * @param finalAnswer
     *            The final answer (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param completionReason
     *            Why the subagent stopped (must not be null)
     * @param cost
     *            The estimated USD cost of this execution (null is treated as {@link Money#zeroUsd()})
     * @return A new successful SubagentExecutionResult
     * @throws NullPointerException
     *             if a required parameter is null
     */
    public static SubagentExecutionResult success(String finalAnswer, SessionSnapshot snapshot,
            ExecutionMetadata metadata, CompletionReason completionReason, Money cost) {
        Objects.requireNonNull(finalAnswer, "Final answer cannot be null");
        Objects.requireNonNull(snapshot, "Session snapshot cannot be null");
        Objects.requireNonNull(metadata, "Execution metadata cannot be null");
        Objects.requireNonNull(completionReason, "Completion reason cannot be null");
        return new SubagentExecutionResult(finalAnswer, snapshot, metadata, true, null, completionReason, cost);
    }

    /**
     * Creates a failed execution result.
     *
     * @param errorMessage
     *            The error message (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A new failed SubagentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static SubagentExecutionResult failure(String errorMessage, SessionSnapshot snapshot,
            ExecutionMetadata metadata) {
        return failure(errorMessage, snapshot, metadata, CompletionReason.ERROR);
    }

    /**
     * Creates a failed execution result carrying an explicit {@link CompletionReason} (e.g. a budget/interrupt stop
     * that is not a plain error).
     *
     * @param errorMessage
     *            The error message (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param completionReason
     *            Why the subagent stopped (must not be null)
     * @return A new failed SubagentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static SubagentExecutionResult failure(String errorMessage, SessionSnapshot snapshot,
            ExecutionMetadata metadata, CompletionReason completionReason) {
        return failure(errorMessage, snapshot, metadata, completionReason, Money.zeroUsd());
    }

    /**
     * Creates a failed execution result carrying an explicit {@link CompletionReason} and estimated {@link Money} cost.
     *
     * @param errorMessage
     *            The error message (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param completionReason
     *            Why the subagent stopped (must not be null)
     * @param cost
     *            The estimated USD cost of this execution (null is treated as {@link Money#zeroUsd()})
     * @return A new failed SubagentExecutionResult
     * @throws NullPointerException
     *             if a required parameter is null
     */
    public static SubagentExecutionResult failure(String errorMessage, SessionSnapshot snapshot,
            ExecutionMetadata metadata, CompletionReason completionReason, Money cost) {
        Objects.requireNonNull(errorMessage, "Error message cannot be null");
        Objects.requireNonNull(snapshot, "Session snapshot cannot be null");
        Objects.requireNonNull(metadata, "Execution metadata cannot be null");
        Objects.requireNonNull(completionReason, "Completion reason cannot be null");
        return new SubagentExecutionResult(null, snapshot, metadata, false, errorMessage, completionReason, cost);
    }

    /**
     * Creates a successful result with no recorded transcript: an empty {@link SessionSnapshot} and zero-cost
     * {@link ExecutionMetadata} ({@code iterationCount=0}, no tokens) timestamped from {@code startTime} to now. Used
     * by
     * execution paths that produce no ReAct transcript (e.g. code-behavior subagents). Callers that ran a real
     * transcript should use {@link #success(String, SessionSnapshot, ExecutionMetadata)} instead.
     *
     * @param finalAnswer
     *            The final answer (must not be null)
     * @param startTime
     *            The execution start instant (must not be null)
     * @return A successful result with an empty snapshot and zero metadata
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static SubagentExecutionResult emptySuccess(String finalAnswer, Instant startTime) {
        return success(finalAnswer, emptySnapshot(), zeroMetadata(startTime));
    }

    /**
     * Creates a failed result with no recorded transcript: an empty {@link SessionSnapshot} and zero-cost
     * {@link ExecutionMetadata} ({@code iterationCount=0}, no tokens) timestamped from {@code startTime} to now. Used
     * by
     * paths that fail before producing a transcript (manager-level dispatch failures, code-behavior failures). Callers
     * that ran a real transcript should use {@link #failure(String, SessionSnapshot, ExecutionMetadata)}.
     *
     * @param errorMessage
     *            The error message (must not be null)
     * @param startTime
     *            The execution start instant (must not be null)
     * @return A failed result with an empty snapshot and zero metadata
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static SubagentExecutionResult emptyFailure(String errorMessage, Instant startTime) {
        return failure(errorMessage, emptySnapshot(), zeroMetadata(startTime));
    }

    private static SessionSnapshot emptySnapshot() {
        return SessionSnapshot.of(SessionId.generate());
    }

    private static ExecutionMetadata zeroMetadata(Instant startTime) {
        Objects.requireNonNull(startTime, "startTime cannot be null");
        return ExecutionMetadata.builder().iterationCount(0).tokenUsage(TokenUsage.empty())
                .timestamps(startTime, Instant.now()).build();
    }

    private final String finalAnswer;
    private final SessionSnapshot snapshot;
    private final ExecutionMetadata metadata;
    private final boolean success;
    private final String errorMessage;
    private final CompletionReason completionReason;
    private final Money cost;

    private SubagentExecutionResult(String finalAnswer, SessionSnapshot snapshot, ExecutionMetadata metadata,
            boolean success, String errorMessage, CompletionReason completionReason, Money cost) {
        this.finalAnswer = finalAnswer;
        this.snapshot = Objects.requireNonNull(snapshot, "Session snapshot cannot be null");
        this.metadata = Objects.requireNonNull(metadata, "Execution metadata cannot be null");
        this.success = success;
        this.errorMessage = errorMessage;
        this.completionReason = Objects.requireNonNull(completionReason, "Completion reason cannot be null");
        this.cost = cost != null ? cost : Money.zeroUsd();
    }

    /**
     * Gets the final answer.
     *
     * @return The final answer (null if execution failed)
     */
    public String getFinalAnswer() {
        return finalAnswer;
    }

    /**
     * Gets the session snapshot.
     *
     * <p>
     * The snapshot contains both the system prompt used for this execution and the complete conversation history.
     *
     * @return The session snapshot (never null)
     */
    public SessionSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Gets the conversation history.
     *
     * <p>
     * Convenience method that delegates to {@code getSnapshot().getConversationHistory()}.
     *
     * @return An immutable list of messages (never null)
     */
    public List<Message> getConversationHistory() {
        return snapshot.getConversationHistory();
    }

    /**
     * Gets the number of iterations executed.
     *
     * <p>
     * Convenience method that delegates to {@code getMetadata().getIterationCount()}.
     *
     * @return The iteration count
     */
    public int getIterationCount() {
        return metadata.getIterationCount();
    }

    /**
     * Gets the execution metadata.
     *
     * @return The execution metadata (never null)
     */
    public ExecutionMetadata getMetadata() {
        return metadata;
    }

    /**
     * Checks if the execution was successful.
     *
     * @return true if successful, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Gets the error message.
     *
     * @return The error message (null if execution was successful)
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Gets the summary.
     *
     * <p>
     * Returns the final answer if successful, or the error message if failed. Compatibility method for existing code.
     *
     * @return The summary (never null)
     */
    public String getSummary() {
        return success ? finalAnswer : errorMessage;
    }

    /**
     * Gets the status.
     *
     * <p>
     * Compatibility method for existing code.
     *
     * @return "SUCCESS" if successful, "FAILURE" otherwise
     */
    public String getStatus() {
        return success ? "SUCCESS" : "FAILURE";
    }

    /**
     * Gets why the subagent stopped.
     *
     * <p>
     * Unlike {@link #isSuccess()} (which collapses every non-completion into {@code false}), this distinguishes a
     * normal
     * {@link CompletionReason#COMPLETED} finish from budget stops ({@code MAX_ITERATIONS},
     * {@code TOKEN_BUDGET_EXCEEDED},
     * ...), interruption and plain errors — the signal an workflow judge / loop-until-dry driver needs to tell
     * "needs another pass" from "genuinely failed".
     *
     * @return the completion reason (never null)
     */
    public CompletionReason getCompletionReason() {
        return completionReason;
    }

    /**
     * Gets the estimated USD cost of the LLM calls this execution made.
     *
     * <p>
     * Priced by the subagent executor's {@code CostEstimator} from the accumulated token usage and resolved model;
     * {@link Money#zeroUsd()} when no pricing was wired (the default) or the path made no priced LLM calls.
     *
     * @return the estimated cost (never null)
     */
    public Money getCost() {
        return cost;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SubagentExecutionResult that = (SubagentExecutionResult) o;
        return success == that.success && Objects.equals(finalAnswer, that.finalAnswer)
                && snapshot.equals(that.snapshot) && metadata.equals(that.metadata)
                && Objects.equals(errorMessage, that.errorMessage) && completionReason == that.completionReason
                && cost.equals(that.cost);
    }

    @Override
    public int hashCode() {
        return Objects.hash(finalAnswer, snapshot, metadata, success, errorMessage, completionReason, cost);
    }

    @Override
    public String toString() {
        if (success) {
            return "SubagentExecutionResult{" + "success=true" + ", reason=" + completionReason + ", cost=" + cost
                    + ", metadata=" + metadata + ", answer='"
                    + (finalAnswer.length() > 100 ? finalAnswer.substring(0, 100) + "..." : finalAnswer) + '\'' + '}';
        } else {
            return "SubagentExecutionResult{" + "success=false" + ", reason=" + completionReason + ", cost=" + cost
                    + ", metadata=" + metadata + ", error='" + errorMessage + '\'' + '}';
        }
    }
}
