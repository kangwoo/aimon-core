package at.aimon.core.subagent.execution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.quota.QuotaViolation;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.environment.ExecutionEnvironment;
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
        return new SubagentExecutionResult(finalAnswer, snapshot, metadata, true, null, completionReason, cost, null,
                null, null, 0, true, null, false);
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
        return new SubagentExecutionResult(null, snapshot, metadata, false, errorMessage, completionReason, cost, null,
                null, null, 0, true, null, false);
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
    private final QuotaViolation quotaViolation;
    private final ExecutionId executionId;
    private final ExecutionEnvironment executionEnvironment;
    private final int toolCallCount;
    private final boolean exitConfirmed;
    private final Map<String, Object> structuredResult;
    private final boolean structuredResultRequired;

    // One argument per field of an immutable value; bundling them would only move the list.
    @SuppressWarnings("checkstyle:ParameterNumber")
    private SubagentExecutionResult(String finalAnswer, SessionSnapshot snapshot, ExecutionMetadata metadata,
            boolean success, String errorMessage, CompletionReason completionReason, Money cost,
            QuotaViolation quotaViolation, ExecutionId executionId, ExecutionEnvironment executionEnvironment,
            int toolCallCount, boolean exitConfirmed, Map<String, Object> structuredResult,
            boolean structuredResultRequired) {
        this.finalAnswer = finalAnswer;
        this.snapshot = Objects.requireNonNull(snapshot, "Session snapshot cannot be null");
        this.metadata = Objects.requireNonNull(metadata, "Execution metadata cannot be null");
        this.success = success;
        this.errorMessage = errorMessage;
        this.completionReason = Objects.requireNonNull(completionReason, "Completion reason cannot be null");
        this.cost = cost != null ? cost : Money.zeroUsd();
        this.quotaViolation = quotaViolation;
        this.executionId = executionId;
        this.executionEnvironment = executionEnvironment;
        this.toolCallCount = toolCallCount;
        this.exitConfirmed = exitConfirmed;
        this.structuredResult = structuredResult;
        this.structuredResultRequired = structuredResultRequired;
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
     * <p>
     * {@link CompletionReason#TRUNCATED} is a final answer the provider cut off at {@code max_tokens}: the partial
     * text, ending in {@link at.aimon.core.agent.budget.TruncatedResponses#TRUNCATION_MARKER}, with
     * {@link #isSuccess()} still {@code true} and the text in {@link #getSummary()}, the shape a turn has. So
     * {@link #getStatus()} reads {@code SUCCESS} for it; the completion reason is what says the answer is incomplete.
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

    /**
     * Gets the usage quota that stopped the fork, when one did.
     *
     * @return the violation when {@link #getCompletionReason()} is {@link CompletionReason#QUOTA_EXCEEDED}, otherwise
     *         empty
     */
    public Optional<QuotaViolation> getQuotaViolation() {
        return Optional.ofNullable(quotaViolation);
    }

    /**
     * Returns a copy of this result naming the quota that stopped the fork.
     *
     * @param quotaViolation
     *            the violation (must not be null)
     * @return a new result carrying the violation; the original is unchanged
     */
    public SubagentExecutionResult withQuotaViolation(QuotaViolation quotaViolation) {
        Objects.requireNonNull(quotaViolation, "Quota violation cannot be null");
        return new SubagentExecutionResult(finalAnswer, snapshot, metadata, success, errorMessage, completionReason,
                cost, quotaViolation, executionId, executionEnvironment, toolCallCount, exitConfirmed, structuredResult,
                structuredResultRequired);
    }

    /**
     * Gets the identity of the fork execution that produced this result — the key its artifacts, execution environment
     * and tool state live under.
     *
     * @return the fork's execution id, or empty when the result never ran the ReAct loop (a code behavior, or a
     *         dispatch failure before the fork started)
     */
    public Optional<ExecutionId> getExecutionId() {
        return Optional.ofNullable(executionId);
    }

    /**
     * Returns a copy of this result naming the fork execution that produced it.
     *
     * @param executionId
     *            the fork's execution id (must not be null)
     * @return a new result carrying the execution id; the original is unchanged
     */
    public SubagentExecutionResult withExecutionId(ExecutionId executionId) {
        Objects.requireNonNull(executionId, "Execution id cannot be null");
        return new SubagentExecutionResult(finalAnswer, snapshot, metadata, success, errorMessage, completionReason,
                cost, quotaViolation, executionId, executionEnvironment, toolCallCount, exitConfirmed, structuredResult,
                structuredResultRequired);
    }

    /**
     * Gets the execution environment the fork ran in (EE-52): the one its tools and hooks used, and the one the
     * {@code subagentStop} hook is handed.
     *
     * <p>
     * A live handle, not a value: it is left out of {@link #equals(Object)}, {@link #hashCode()} and
     * {@link #toString()}, and is not persisted with a task's result.
     *
     * @return the fork's environment, or empty when the result never resolved one (a code behavior, or a dispatch
     *         failure before the fork started)
     */
    public Optional<ExecutionEnvironment> getExecutionEnvironment() {
        return Optional.ofNullable(executionEnvironment);
    }

    /**
     * Returns a copy of this result naming the execution environment the fork ran in.
     *
     * @param executionEnvironment
     *            the fork's resolved environment (must not be null)
     * @return a new result carrying the environment; the original is unchanged
     */
    public SubagentExecutionResult withExecutionEnvironment(ExecutionEnvironment executionEnvironment) {
        Objects.requireNonNull(executionEnvironment, "Execution environment cannot be null");
        return new SubagentExecutionResult(finalAnswer, snapshot, metadata, success, errorMessage, completionReason,
                cost, quotaViolation, executionId, executionEnvironment, toolCallCount, exitConfirmed, structuredResult,
                structuredResultRequired);
    }

    /**
     * Gets how many tool calls the fork handed to its tool pipeline — one per call of a parallel batch. That is the
     * count its tool-call budget is charged with: a call the pipeline then declines (a permission denial, a PreTool
     * block) counts, because the fork spent the call on it. Calls a cut response or an exhausted budget refused before
     * the pipeline are not counted: they were answered, not run.
     *
     * @return the number of tool calls run (0 for a path that ran none or does not count them)
     */
    public int getToolCallCount() {
        return toolCallCount;
    }

    /**
     * Returns a copy of this result recording how many tool calls the fork ran.
     *
     * @param toolCallCount
     *            the count (must not be negative)
     * @return a new result; the original is unchanged
     */
    public SubagentExecutionResult withToolCallCount(int toolCallCount) {
        if (toolCallCount < 0) {
            throw new IllegalArgumentException("toolCallCount must not be negative, got: " + toolCallCount);
        }
        return new SubagentExecutionResult(finalAnswer, snapshot, metadata, success, errorMessage, completionReason,
                cost, quotaViolation, executionId, executionEnvironment, toolCallCount, exitConfirmed, structuredResult,
                structuredResultRequired);
    }

    /**
     * Gets whether the run is known to have exited when this result was handed back.
     *
     * <p>
     * Always {@code true} except for one case: a foreground fork under a deadline that was asked to stop — the deadline
     * passed, or its spawner cancelled it — and was still running when its grace period ran out. Its result is handed
     * back anyway, with this {@code false}, so the spawner is not blocked forever by a tool that ignores interrupts;
     * the
     * run itself goes on until it returns, listed meanwhile by
     * {@link at.aimon.core.subagent.SubagentExecutionManager#lingeringRuns()}. Such a result is never a completion: do
     * not treat its work as done, and do not launch the same work again while it is listed.
     *
     * @return whether the run had exited
     */
    public boolean isExitConfirmed() {
        return exitConfirmed;
    }

    /**
     * Returns a copy of this result saying the run had not exited when it was handed back.
     *
     * @return a new result with {@link #isExitConfirmed()} {@code false}; the original is unchanged
     */
    public SubagentExecutionResult withExitUnconfirmed() {
        return new SubagentExecutionResult(finalAnswer, snapshot, metadata, success, errorMessage, completionReason,
                cost, quotaViolation, executionId, executionEnvironment, toolCallCount, false, structuredResult,
                structuredResultRequired);
    }

    /**
     * Gets the structured result the fork reported: the last payload its {@value StructuredResultReport#TOOL_NAME}
     * tool accepted, which fit the policy's schema and size cap when it was reported. It is carried whatever the
     * completion reason — a fork stopped by a budget may still have reported — and is never truncated.
     *
     * @return the payload (unmodifiable, key order kept), or empty when the fork reported none or was not asked to
     */
    public Optional<Map<String, Object>> getStructuredResult() {
        return Optional.ofNullable(structuredResult);
    }

    /**
     * Gets whether the fork's execution policy required a structured result. Together with
     * {@link #getStructuredResult()} it tells a missing report — required, none accepted — from one never asked for.
     *
     * @return whether a structured result was required
     */
    public boolean isStructuredResultRequired() {
        return structuredResultRequired;
    }

    /**
     * Returns a copy of this result carrying the structured result the fork reported.
     *
     * @param structuredResult
     *            the accepted payload (must not be null; copied deeply)
     * @return a new result; the original is unchanged
     */
    public SubagentExecutionResult withStructuredResult(Map<String, Object> structuredResult) {
        Objects.requireNonNull(structuredResult, "Structured result cannot be null");
        return new SubagentExecutionResult(finalAnswer, snapshot, metadata, success, errorMessage, completionReason,
                cost, quotaViolation, executionId, executionEnvironment, toolCallCount, exitConfirmed,
                StructuredResultReport.copyOf(structuredResult), structuredResultRequired);
    }

    /**
     * Returns a copy of this result recording whether a structured result was required.
     *
     * @param required
     *            whether the fork's policy required one
     * @return a new result; the original is unchanged
     */
    public SubagentExecutionResult withStructuredResultRequired(boolean required) {
        return new SubagentExecutionResult(finalAnswer, snapshot, metadata, success, errorMessage, completionReason,
                cost, quotaViolation, executionId, executionEnvironment, toolCallCount, exitConfirmed, structuredResult,
                required);
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
                && cost.equals(that.cost) && Objects.equals(quotaViolation, that.quotaViolation)
                && Objects.equals(executionId, that.executionId) && toolCallCount == that.toolCallCount
                && exitConfirmed == that.exitConfirmed && Objects.equals(structuredResult, that.structuredResult)
                && structuredResultRequired == that.structuredResultRequired;
    }

    @Override
    public int hashCode() {
        return Objects.hash(finalAnswer, snapshot, metadata, success, errorMessage, completionReason, cost,
                quotaViolation, executionId, toolCallCount, exitConfirmed, structuredResult, structuredResultRequired);
    }

    @Override
    public String toString() {
        if (success) {
            return "SubagentExecutionResult{" + "success=true" + ", reason=" + completionReason + ", cost=" + cost
                    + ", executionId=" + executionId + ", metadata=" + metadata + ", structuredResult="
                    + (structuredResult != null) + ", answer='"
                    + (finalAnswer.length() > 100 ? finalAnswer.substring(0, 100) + "..." : finalAnswer) + '\'' + '}';
        } else {
            return "SubagentExecutionResult{" + "success=false" + ", reason=" + completionReason + ", cost=" + cost
                    + ", executionId=" + executionId + ", metadata=" + metadata + ", exitConfirmed=" + exitConfirmed
                    + ", error='" + errorMessage + '\'' + '}';
        }
    }
}
