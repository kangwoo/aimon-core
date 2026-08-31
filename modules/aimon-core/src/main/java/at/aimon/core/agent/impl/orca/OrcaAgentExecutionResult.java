package at.aimon.core.agent.impl.orca;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.cost.CostSummary;

/**
 * Represents the result of an agent execution.
 *
 * <p>
 * Contains the final answer, session snapshot (including system prompt and conversation history), execution
 * metadata, file artifacts, and status information about the execution.
 *
 * <p>
 * The session snapshot preserves the exact context of the session, including the system prompt that was used
 * at the time of execution.
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
 *     AgentExecutionResult result = agent.execute("What is 2 + 2?");
 *
 *     if (result.isSuccess()) {
 *         System.out.println("Answer: " + result.getFinalAnswer());
 *         System.out.println("Iterations: " + result.getIterationCount());
 *         System.out.println("Duration: " + result.getMetadata().getDuration().toMillis() + "ms");
 *         System.out.println("Tokens: " + result.getMetadata().getTokenUsage().getTotalTokens());
 *
 *         // Access session snapshot
 *         SessionSnapshot snapshot = result.getSnapshot();
 *         System.out.println("System Prompt: " + snapshot.getSystemPrompt());
 *         System.out.println("Message Count: " + snapshot.getConversationHistory().size());
 *     } else {
 *         System.err.println("Error: " + result.getErrorMessage());
 *     }
 *
 *     // Access file artifacts (available in both success and failure cases)
 *     for (FileArtifact artifact : result.getArtifacts()) {
 *         System.out.println("Generated file: " + artifact.getFileName());
 *     }
 * }
 * </pre>
 */
public final class OrcaAgentExecutionResult implements AgentExecutionResult {

    /**
     * Creates a successful execution result without artifacts.
     *
     * @param finalAnswer
     *            The final answer (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A new successful AgentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static OrcaAgentExecutionResult success(String finalAnswer, SessionSnapshot snapshot,
            ExecutionMetadata metadata) {
        return success(finalAnswer, snapshot, metadata, List.of());
    }

    /**
     * Creates a successful execution result with artifacts.
     *
     * @param finalAnswer
     *            The final answer (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param artifacts
     *            The file artifacts generated during execution (must not be null)
     * @return A new successful AgentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static OrcaAgentExecutionResult success(String finalAnswer, SessionSnapshot snapshot,
            ExecutionMetadata metadata, List<FileArtifact> artifacts) {
        return success(finalAnswer, snapshot, metadata, artifacts, CompletionReason.COMPLETED);
    }

    /**
     * Creates a successful execution result with an explicit completion reason.
     *
     * <p>
     * Intended for executors that stop on a budget boundary (e.g., {@link CompletionReason#MAX_ITERATIONS}) but still
     * want to surface a partial answer. Callers that simply finished normally should prefer
     * {@link #success(String, SessionSnapshot, ExecutionMetadata, List)}.
     *
     * @param finalAnswer
     *            The final answer (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param artifacts
     *            The file artifacts generated during execution (must not be null)
     * @param completionReason
     *            The structured reason the execution ended (must not be null)
     * @return A new successful AgentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static OrcaAgentExecutionResult success(String finalAnswer, SessionSnapshot snapshot,
            ExecutionMetadata metadata, List<FileArtifact> artifacts, CompletionReason completionReason) {
        return success(finalAnswer, snapshot, metadata, artifacts, completionReason, false);
    }

    /**
     * Creates a successful execution result with an explicit completion reason and streaming flag.
     *
     * <p>
     * Use this overload when the terminal assistant text has already been delivered to the caller incrementally via
     * {@link at.aimon.core.agent.stream.AssistantTextDelta} events: set {@code wasStreamed} to {@code true} so
     * downstream renderers skip re-printing the final answer.
     *
     * @param finalAnswer
     *            The final answer (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param artifacts
     *            The file artifacts generated during execution (must not be null)
     * @param completionReason
     *            The structured reason the execution ended (must not be null)
     * @param wasStreamed
     *            Whether the terminal assistant text was streamed to the caller as delta events
     * @return A new successful AgentExecutionResult
     * @throws NullPointerException
     *             if any reference parameter is null
     */
    public static OrcaAgentExecutionResult success(String finalAnswer, SessionSnapshot snapshot,
            ExecutionMetadata metadata, List<FileArtifact> artifacts, CompletionReason completionReason,
            boolean wasStreamed) {
        Objects.requireNonNull(finalAnswer, "Final answer cannot be null");
        Objects.requireNonNull(snapshot, "Session snapshot cannot be null");
        Objects.requireNonNull(metadata, "Execution metadata cannot be null");
        Objects.requireNonNull(artifacts, "Artifacts cannot be null");
        Objects.requireNonNull(completionReason, "Completion reason cannot be null");
        return new Builder().finalAnswer(finalAnswer).snapshot(snapshot).metadata(metadata).success(true)
                .errorMessage(null).artifacts(artifacts).completionReason(completionReason).wasStreamed(wasStreamed)
                .compactionEvents(List.of()).costSummary(CostSummary.empty()).build();
    }

    /**
     * Creates a failed execution result without artifacts.
     *
     * @param errorMessage
     *            The error message (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A new failed AgentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static OrcaAgentExecutionResult failure(String errorMessage, SessionSnapshot snapshot,
            ExecutionMetadata metadata) {
        return failure(errorMessage, snapshot, metadata, List.of());
    }

    /**
     * Creates a failed execution result with artifacts.
     *
     * <p>
     * Artifacts may be present in failure results when files were generated before the failure occurred. The caller
     * should handle the case where {@code isSuccess()} returns false but {@code getArtifacts()} is non-empty.
     *
     * @param errorMessage
     *            The error message (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param artifacts
     *            The file artifacts generated before failure (must not be null)
     * @return A new failed AgentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static OrcaAgentExecutionResult failure(String errorMessage, SessionSnapshot snapshot,
            ExecutionMetadata metadata, List<FileArtifact> artifacts) {
        return failure(errorMessage, snapshot, metadata, artifacts, CompletionReason.ERROR);
    }

    /**
     * Creates a failed execution result with an explicit completion reason.
     *
     * <p>
     * Intended for executors that end with a structured reason other than {@link CompletionReason#ERROR} (for example
     * {@link CompletionReason#ABORTED} on cancellation).
     *
     * @param errorMessage
     *            The error message (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param artifacts
     *            The file artifacts generated before failure (must not be null)
     * @param completionReason
     *            The structured reason the execution ended (must not be null)
     * @return A new failed AgentExecutionResult
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static OrcaAgentExecutionResult failure(String errorMessage, SessionSnapshot snapshot,
            ExecutionMetadata metadata, List<FileArtifact> artifacts, CompletionReason completionReason) {
        return failure(errorMessage, snapshot, metadata, artifacts, completionReason, false);
    }

    /**
     * Creates a failed execution result with an explicit completion reason and streaming flag.
     *
     * <p>
     * Use this overload when partial assistant text was already streamed to the caller via
     * {@link at.aimon.core.agent.stream.AssistantTextDelta} events before the failure/interruption.
     *
     * @param errorMessage
     *            The error message (must not be null)
     * @param snapshot
     *            The session snapshot (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @param artifacts
     *            The file artifacts generated before failure (must not be null)
     * @param completionReason
     *            The structured reason the execution ended (must not be null)
     * @param wasStreamed
     *            Whether partial assistant text was streamed to the caller as delta events
     * @return A new failed AgentExecutionResult
     * @throws NullPointerException
     *             if any reference parameter is null
     */
    public static OrcaAgentExecutionResult failure(String errorMessage, SessionSnapshot snapshot,
            ExecutionMetadata metadata, List<FileArtifact> artifacts, CompletionReason completionReason,
            boolean wasStreamed) {
        Objects.requireNonNull(errorMessage, "Error message cannot be null");
        Objects.requireNonNull(snapshot, "Session snapshot cannot be null");
        Objects.requireNonNull(metadata, "Execution metadata cannot be null");
        Objects.requireNonNull(artifacts, "Artifacts cannot be null");
        Objects.requireNonNull(completionReason, "Completion reason cannot be null");
        return new Builder().finalAnswer(null).snapshot(snapshot).metadata(metadata).success(false)
                .errorMessage(errorMessage).artifacts(artifacts).completionReason(completionReason)
                .wasStreamed(wasStreamed).compactionEvents(List.of()).costSummary(CostSummary.empty()).build();
    }

    private final String finalAnswer;
    private final SessionSnapshot snapshot;
    private final ExecutionMetadata metadata;
    private final boolean success;
    private final String errorMessage;
    private final List<FileArtifact> artifacts;
    private final CompletionReason completionReason;
    private final boolean wasStreamed;
    private final List<CompactionMetadata> compactionEvents;
    private final CostSummary costSummary;

    /**
     * Creates a new AgentExecutionResult from the supplied builder.
     *
     * @param builder
     *            the builder carrying all field values (must not be null)
     */
    private OrcaAgentExecutionResult(Builder builder) {
        this.finalAnswer = builder.finalAnswer;
        this.snapshot = Objects.requireNonNull(builder.snapshot, "Session snapshot cannot be null");
        this.metadata = Objects.requireNonNull(builder.metadata, "Execution metadata cannot be null");
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.artifacts = Collections
                .unmodifiableList(Objects.requireNonNull(builder.artifacts, "Artifacts cannot be null"));
        this.completionReason = Objects.requireNonNull(builder.completionReason, "Completion reason cannot be null");
        this.wasStreamed = builder.wasStreamed;
        this.compactionEvents = List
                .copyOf(Objects.requireNonNull(builder.compactionEvents, "Compaction events cannot be null"));
        this.costSummary = Objects.requireNonNull(builder.costSummary, "Cost summary cannot be null");
    }

    /**
     * Fluent builder for {@link OrcaAgentExecutionResult}. Kept private so the public construction surface remains the
     * static {@code success(...)} / {@code failure(...)} factory methods.
     */
    private static final class Builder {
        private String finalAnswer;
        private SessionSnapshot snapshot;
        private ExecutionMetadata metadata;
        private boolean success;
        private String errorMessage;
        private List<FileArtifact> artifacts;
        private CompletionReason completionReason;
        private boolean wasStreamed;
        private List<CompactionMetadata> compactionEvents;
        private CostSummary costSummary;

        private Builder finalAnswer(String finalAnswer) {
            this.finalAnswer = finalAnswer;
            return this;
        }

        private Builder snapshot(SessionSnapshot snapshot) {
            this.snapshot = snapshot;
            return this;
        }

        private Builder metadata(ExecutionMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        private Builder success(boolean success) {
            this.success = success;
            return this;
        }

        private Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        private Builder artifacts(List<FileArtifact> artifacts) {
            this.artifacts = artifacts;
            return this;
        }

        private Builder completionReason(CompletionReason completionReason) {
            this.completionReason = completionReason;
            return this;
        }

        private Builder wasStreamed(boolean wasStreamed) {
            this.wasStreamed = wasStreamed;
            return this;
        }

        private Builder compactionEvents(List<CompactionMetadata> compactionEvents) {
            this.compactionEvents = compactionEvents;
            return this;
        }

        private Builder costSummary(CostSummary costSummary) {
            this.costSummary = costSummary;
            return this;
        }

        private OrcaAgentExecutionResult build() {
            return new OrcaAgentExecutionResult(this);
        }
    }

    /**
     * Returns a copy of this result with the supplied compaction events attached. Used by
     * {@link OrcaAgentExecutor} to surface AUTO compactions that ran during the ReAct loop without forcing
     * additional positional parameters onto every {@code success(...)} / {@code failure(...)} factory overload.
     *
     * @param compactionEvents
     *            ordered metadata for each {@code COMPACT} decision the guard returned during this execution; oldest
     *            first. Pass an empty list to clear (must not be null).
     * @return a new result instance with the events recorded; the original is unchanged
     */
    public OrcaAgentExecutionResult withCompactionEvents(List<CompactionMetadata> compactionEvents) {
        Objects.requireNonNull(compactionEvents, "Compaction events cannot be null");
        if (this.compactionEvents.equals(compactionEvents)) {
            return this;
        }
        return new Builder().finalAnswer(finalAnswer).snapshot(snapshot).metadata(metadata).success(success)
                .errorMessage(errorMessage).artifacts(artifacts).completionReason(completionReason)
                .wasStreamed(wasStreamed).compactionEvents(compactionEvents).costSummary(costSummary).build();
    }

    /**
     * Returns a copy of this result with the supplied cost summary attached. Used by {@link OrcaAgentExecutor} at the
     * single {@code execute()} choke point to surface the per-model estimated cost accumulated during the ReAct loop,
     * without threading an extra positional parameter through every {@code success(...)} / {@code failure(...)} factory
     * overload.
     *
     * @param costSummary
     *            the accumulated per-model cost for this execution (must not be null; pass {@link CostSummary#empty()}
     *            to clear)
     * @return a new result instance with the cost summary recorded; the original is unchanged
     */
    public OrcaAgentExecutionResult withCostSummary(CostSummary costSummary) {
        Objects.requireNonNull(costSummary, "Cost summary cannot be null");
        if (this.costSummary.equals(costSummary)) {
            return this;
        }
        return new Builder().finalAnswer(finalAnswer).snapshot(snapshot).metadata(metadata).success(success)
                .errorMessage(errorMessage).artifacts(artifacts).completionReason(completionReason)
                .wasStreamed(wasStreamed).compactionEvents(compactionEvents).costSummary(costSummary).build();
    }

    /**
     * Gets the final answer.
     *
     * @return The final answer (null if execution failed)
     */
    @Override
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
    @Override
    public boolean isSuccess() {
        return success;
    }

    /**
     * Gets the error message.
     *
     * @return The error message (null if execution was successful)
     */
    @Override
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Gets the file artifacts generated during execution.
     *
     * <p>
     * Both successful and failed executions may contain artifacts.
     *
     * @return An unmodifiable list of file artifacts (never null, may be empty)
     */
    @Override
    public List<FileArtifact> getArtifacts() {
        return artifacts;
    }

    /**
     * Gets the structured reason the execution ended.
     *
     * @return the completion reason (never null)
     */
    @Override
    public CompletionReason getCompletionReason() {
        return completionReason;
    }

    @Override
    public boolean wasStreamed() {
        return wasStreamed;
    }

    /**
     * Returns metadata for each compaction the {@link at.aimon.core.agent.compact.CompactionGuard} performed during
     * this execution, in the order they happened. Empty when no compaction ran. Includes both successful and failed
     * compaction attempts; consumers can inspect each entry's success state via the metadata.
     *
     * @return an unmodifiable list of compaction metadata (never null, may be empty)
     */
    public List<CompactionMetadata> getCompactionEvents() {
        return compactionEvents;
    }

    /**
     * Returns the per-model estimated cost accumulated during this execution.
     *
     * <p>
     * Empty ({@link CostSummary#isEmpty()}) with zero total cost when no cost estimator was wired into the executor, so
     * the getter is always safe to call regardless of whether cost tracking is enabled.
     *
     * @return the cost summary (never null)
     */
    public CostSummary getCostSummary() {
        return costSummary;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final OrcaAgentExecutionResult that = (OrcaAgentExecutionResult) o;
        return success == that.success && wasStreamed == that.wasStreamed
                && Objects.equals(finalAnswer, that.finalAnswer) && snapshot.equals(that.snapshot)
                && metadata.equals(that.metadata) && Objects.equals(errorMessage, that.errorMessage)
                && artifacts.equals(that.artifacts) && completionReason == that.completionReason
                && compactionEvents.equals(that.compactionEvents) && costSummary.equals(that.costSummary);
    }

    @Override
    public int hashCode() {
        return Objects.hash(finalAnswer, snapshot, metadata, success, errorMessage, artifacts, completionReason,
                wasStreamed, compactionEvents, costSummary);
    }

    @Override
    public String toString() {
        final String artifactInfo = artifacts.isEmpty() ? "" : ", artifacts=" + artifacts.size();
        final String reasonInfo = ", reason=" + completionReason;
        if (success) {
            return "AgentExecutionResult{" + "success=true" + ", metadata=" + metadata + ", answer='"
                    + (finalAnswer.length() > 100 ? finalAnswer.substring(0, 100) + "..." : finalAnswer) + '\''
                    + reasonInfo + artifactInfo + '}';
        } else {
            return "AgentExecutionResult{" + "success=false" + ", metadata=" + metadata + ", error='" + errorMessage
                    + '\'' + reasonInfo + artifactInfo + '}';
        }
    }
}
