package at.aimon.core.workflow;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

/**
 * The result of one subagent execution within an workflow run — the value {@code agent()} returns.
 *
 * <p>
 * Immutable. Uses a static factory (not a builder), following the {@code SubagentExecutionResult} /
 * {@code ToolResult} precedent for result wrappers. The payload is always free text ({@link #text()}); when the task
 * requested structured output via {@link AgentTask#getResultSchema()} and the subagent's final answer parsed and
 * validated, {@link #structured()} also carries the parsed object.
 */
public final class AgentStepResult {

    private final AgentTask task;
    private final SubagentExecutionResult raw;
    private final Map<String, Object> structured;

    private AgentStepResult(AgentTask task, SubagentExecutionResult raw, Map<String, Object> structured) {
        this.task = Objects.requireNonNull(task, "task cannot be null");
        this.raw = Objects.requireNonNull(raw, "raw cannot be null");
        this.structured = structured;
    }

    /**
     * Wraps a subagent execution result with its originating task (no structured output).
     *
     * @param task
     *            the task that produced this result (must not be null)
     * @param raw
     *            the underlying subagent execution result (must not be null)
     * @return a new step result
     */
    public static AgentStepResult of(AgentTask task, SubagentExecutionResult raw) {
        return new AgentStepResult(task, raw, null);
    }

    /**
     * Wraps a subagent execution result together with its parsed/validated structured output.
     *
     * @param task
     *            the task that produced this result (must not be null)
     * @param raw
     *            the underlying subagent execution result (must not be null)
     * @param structured
     *            the parsed, schema-validated structured output, or {@code null} when none (no schema, or
     *            parse/validate
     *            failed)
     * @return a new step result
     */
    public static AgentStepResult of(AgentTask task, SubagentExecutionResult raw, Map<String, Object> structured) {
        return new AgentStepResult(task, raw, structured);
    }

    /**
     * @return {@code true} if the subagent execution succeeded
     */
    public boolean isSuccess() {
        return raw.isSuccess();
    }

    /**
     * @return why the subagent stopped — distinguishes a normal {@code COMPLETED} finish from budget stops
     *         ({@code MAX_ITERATIONS}, {@code TOKEN_BUDGET_EXCEEDED}, ...), interruption and errors (never null)
     */
    public CompletionReason completionReason() {
        return raw.getCompletionReason();
    }

    /**
     * @return {@code true} iff the subagent finished normally ({@link CompletionReason#COMPLETED}) — the signal a
     *         judge / loop-until-dry driver uses to tell "done" from "ran out of room" or "failed"
     */
    public boolean isComplete() {
        return raw.getCompletionReason() == CompletionReason.COMPLETED;
    }

    /**
     * @return the subagent's final answer on success, or its error message on failure (never null)
     */
    public String text() {
        return raw.getSummary();
    }

    /**
     * @return the parsed, schema-validated structured output when the task requested it via
     *         {@link AgentTask#getResultSchema()} and the final answer parsed and validated; empty otherwise (no
     *         schema, non-success execution, or a parse/validation failure — {@link #text()} still holds the raw text)
     */
    public Optional<Map<String, Object>> structured() {
        return Optional.ofNullable(structured);
    }

    /**
     * @return the display/diagnostic label of the originating task (never null)
     */
    public String getLabel() {
        return task.getLabel();
    }

    /**
     * @return the task that produced this result (never null)
     */
    public AgentTask getTask() {
        return task;
    }

    /**
     * @return the underlying subagent execution result, for access to metadata (token usage, session snapshot,
     *         ...) not surfaced directly here (never null)
     */
    public SubagentExecutionResult raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "AgentStepResult{label=" + getLabel() + ", success=" + isSuccess() + '}';
    }
}
