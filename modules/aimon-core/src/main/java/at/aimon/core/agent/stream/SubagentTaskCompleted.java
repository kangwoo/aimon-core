package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that a <em>background</em> subagent task launched via the {@code Task} tool has reached a terminal state
 * (completed, failed, or killed).
 *
 * <p>
 * <b>Use when:</b> a fire-and-forget background subagent settles off the parent's turn. Unlike {@link ToolResultReady}
 * (which fires synchronously while the launching tool call is still in flight), this event is raised from the
 * background worker thread when the task's future completes — potentially long after the launching turn ended. The
 * {@link #getAgentRuntimeId()} carried here is the <b>parent</b> agent runtime that launched the task, so
 * a UI/observer can attribute the completion to the agent that spawned it.
 *
 * <p>
 * This is the observability half of the completion-notification design; the guaranteed, model-facing half is the
 * {@code <task-notification>} message pushed into the parent's message queue (see
 * {@code DefaultSubagentExecutionManager}). Because the parent's per-turn event listeners are only attached while a
 * turn
 * is active, this event is <b>best-effort for live display</b>: when the parent is idle at completion time no listener
 * is attached and the event is dropped — the queued notification still guarantees the model learns of the completion on
 * its next turn.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getTaskId()} — the background task id (matches the id returned by the {@code Task} tool and accepted by
 * {@code AgentOutput})
 * <li>{@link #getSubagentName()} — the name of the subagent that ran
 * <li>{@link #getOutcome()} — terminal outcome: {@link Outcome#COMPLETED}, {@link Outcome#FAILED}, or
 * {@link Outcome#KILLED}
 * <li>{@link #getDetail()} — optional short, already-truncated human detail (a result summary for a completion, or an
 * error message for a failure/kill)
 * </ul>
 *
 * <p>
 * The base {@link #getIteration() iteration} is {@code 0} because a background completion is not bound to any specific
 * iteration of the parent's ReAct loop (mirroring {@link ExecutionCompleted}).
 *
 * <p>
 * Immutable value object.
 */
public final class SubagentTaskCompleted extends AgentExecutionEvent {

    /** Terminal outcome of a background subagent task. */
    public enum Outcome {
        /** The task ran to completion and returned a successful result. */
        COMPLETED,
        /** The task threw, was rejected, or returned an error result. */
        FAILED,
        /** The task was stopped (cancelled) before it finished. */
        KILLED
    }

    private final String taskId;
    private final String subagentName;
    private final Outcome outcome;
    private final Optional<String> detail;

    private SubagentTaskCompleted(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.taskId = Objects.requireNonNull(builder.taskId, "taskId cannot be null");
        if (this.taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId cannot be empty");
        }
        this.subagentName = Objects.requireNonNull(builder.subagentName, "subagentName cannot be null");
        if (this.subagentName.isEmpty()) {
            throw new IllegalArgumentException("subagentName cannot be empty");
        }
        this.outcome = Objects.requireNonNull(builder.outcome, "outcome cannot be null");
        this.detail = Objects.requireNonNull(builder.detail, "detail Optional cannot be null");
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the background task id, matching the id returned by the {@code Task} tool and accepted by
     * {@code AgentOutput}.
     *
     * @return the task id (never null, never empty)
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Returns the name of the subagent that ran the task.
     *
     * @return the subagent name (never null, never empty)
     */
    public String getSubagentName() {
        return subagentName;
    }

    /**
     * Returns the terminal outcome of the task.
     *
     * @return the outcome (never null)
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * Returns whether the task completed successfully.
     *
     * @return {@code true} iff {@link #getOutcome()} is {@link Outcome#COMPLETED}
     */
    public boolean isSuccess() {
        return outcome == Outcome.COMPLETED;
    }

    /**
     * Returns a short, already-truncated human detail for the completion.
     *
     * @return an {@link Optional} holding the result summary (completion) or error message (failure/kill); empty when
     *         the producer supplied none (never null)
     */
    public Optional<String> getDetail() {
        return detail;
    }

    @Override
    protected String eventName() {
        return "SubagentTaskCompleted";
    }

    @Override
    protected String detailString() {
        return "taskId='" + taskId + "', subagentName='" + subagentName + "', outcome=" + outcome + ", detail="
                + detail;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SubagentTaskCompleted that = (SubagentTaskCompleted) o;
        return getIteration() == that.getIteration() && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId()) && taskId.equals(that.taskId)
                && subagentName.equals(that.subagentName) && outcome == that.outcome && detail.equals(that.detail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), taskId, subagentName, outcome, detail);
    }

    /** Builder for {@link SubagentTaskCompleted}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private String taskId;
        private String subagentName;
        private Outcome outcome;
        private Optional<String> detail = Optional.empty();

        private Builder() {
        }

        /**
         * Sets the event timestamp.
         *
         * @param timestamp
         *            the wall-clock instant (must not be null)
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the agent runtime identifier of the <b>parent</b> that launched the task.
         *
         * @param agentRuntimeId
         *            the parent agent runtime identifier (must not be null)
         * @return this builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * Sets the iteration number. Background completions are not iteration-bound, so this defaults to {@code 0}.
         *
         * @param iteration
         *            the iteration number (must be {@code >= 0})
         * @return this builder
         */
        public Builder iteration(int iteration) {
            this.iteration = iteration;
            return this;
        }

        /**
         * Sets the background task id.
         *
         * @param taskId
         *            the task id (must not be null or empty)
         * @return this builder
         */
        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        /**
         * Sets the subagent name.
         *
         * @param subagentName
         *            the subagent name (must not be null or empty)
         * @return this builder
         */
        public Builder subagentName(String subagentName) {
            this.subagentName = subagentName;
            return this;
        }

        /**
         * Sets the terminal outcome.
         *
         * @param outcome
         *            the outcome (must not be null)
         * @return this builder
         */
        public Builder outcome(Outcome outcome) {
            this.outcome = outcome;
            return this;
        }

        /**
         * Sets the short human detail. Passing {@code null} or an empty string clears it.
         *
         * @param detail
         *            the detail text, or {@code null}/empty to clear
         * @return this builder
         */
        public Builder detail(String detail) {
            if (detail == null || detail.isEmpty()) {
                this.detail = Optional.empty();
            } else {
                this.detail = Optional.of(detail);
            }
            return this;
        }

        /**
         * Builds the {@link SubagentTaskCompleted} event.
         *
         * @return a new {@link SubagentTaskCompleted}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, {@code taskId}, {@code subagentName}, or
         *             {@code outcome} is null
         * @throws IllegalArgumentException
         *             if {@code taskId} or {@code subagentName} is empty, or {@code iteration} is negative
         */
        public SubagentTaskCompleted build() {
            return new SubagentTaskCompleted(this);
        }
    }
}
