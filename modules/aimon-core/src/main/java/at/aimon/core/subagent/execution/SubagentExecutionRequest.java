package at.aimon.core.subagent.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Request for subagent execution.
 *
 * <p>
 * Contains the request information for subagent execution (what to execute):
 *
 * <ul>
 * <li>Task ID for tracking
 * <li>Goal for the subagent
 * <li>User information
 * <li>Previous session snapshot for context
 * </ul>
 *
 * <p>
 * Note: Execution context (how to execute - subagent, tools) is provided separately via SubagentExecutionContext.
 *
 * <p>
 * Immutable value object.
 */
public final class SubagentExecutionRequest {
    /**
     * Returns a new builder for SubagentExecutionRequest.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String taskId;
    private final String goal;
    private final Principal principal;
    private final SessionId invokingSessionId;
    private final SessionSnapshot previousSnapshot;
    private final Map<String, Object> executionAttributes;
    private final LlmCallMetadata llmCallMetadata;
    private final ExecutionBudget budget;

    private SubagentExecutionRequest(Builder builder) {
        this.taskId = Objects.requireNonNull(builder.taskId, "Task ID cannot be null");
        this.goal = Objects.requireNonNull(builder.goal, "Goal cannot be null");
        this.principal = builder.principal;
        this.invokingSessionId = builder.invokingSessionId;
        this.previousSnapshot = builder.previousSnapshot;
        this.executionAttributes = builder.executionAttributes != null
                ? Map.copyOf(builder.executionAttributes)
                : Map.of();
        this.llmCallMetadata = builder.llmCallMetadata != null ? builder.llmCallMetadata : LlmCallMetadata.empty();
        this.budget = builder.budget;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getGoal() {
        return goal;
    }

    /**
     * Gets the principal (caller identity) if available.
     *
     * @return An Optional containing the principal, or empty if not set
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Gets the session whose turn spawned this run, forwarded from the environment.
     *
     * <p>
     * Empty means no session asked for this run (scheduled tasks, background work), and such a run therefore
     * inherits no decision the user made anywhere.
     *
     * <p>
     * On a resumed run the invoker is whoever <em>resumed</em> it, not whoever originally spawned it: resuming
     * reuses the snapshot's session id, and the decisions that apply are the resumer's. That is deliberate.
     *
     * @return An Optional containing the invoking session id, or empty if not set
     */
    public Optional<SessionId> getInvokingSessionId() {
        return Optional.ofNullable(invokingSessionId);
    }

    /**
     * Gets the previous session snapshot if available.
     *
     * @return An Optional containing the session snapshot, or empty if not set
     */
    public Optional<SessionSnapshot> getPreviousSnapshot() {
        return Optional.ofNullable(previousSnapshot);
    }

    /**
     * Gets the execution attributes.
     *
     * @return The execution attributes (never null, may be empty)
     */
    public Map<String, Object> getExecutionAttributes() {
        return executionAttributes;
    }

    /**
     * Gets the caller-supplied LLM call metadata for usage attribution.
     *
     * <p>
     * <b>Merge semantics (asymmetric with Orca):</b> the executor <i>always</i> overrides {@code component} and
     * {@code feature} with subagent-derived values ({@code subagent.getName()} and {@code "subagent"}). Other fields
     * ({@code traceId}, {@code principal}, {@code tags}) are inherited from this metadata. This means a caller who
     * sets {@code component}/{@code feature} on the request will have those fields silently ignored — subagent
     * execution must always be attributed to the subagent that actually ran, regardless of caller intent. If a caller
     * needs to add orthogonal tags, use {@code tags} instead.
     *
     * @return the metadata (never null, may be {@link LlmCallMetadata#empty()})
     */
    public LlmCallMetadata getLlmCallMetadata() {
        return llmCallMetadata;
    }

    /**
     * Gets the execution budget that bounds the subagent's ReAct loop (iterations, tokens, wall-clock).
     *
     * <p>
     * When empty, the subagent executor applies {@link ExecutionBudget#unlimited()} and only the subagent's
     * {@code maxIterations} bounds the loop.
     *
     * @return an {@link Optional} holding the budget, or empty if none was supplied
     */
    public Optional<ExecutionBudget> getBudget() {
        return Optional.ofNullable(budget);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SubagentExecutionRequest that = (SubagentExecutionRequest) o;
        return Objects.equals(taskId, that.taskId) && Objects.equals(goal, that.goal)
                && Objects.equals(principal, that.principal)
                && Objects.equals(invokingSessionId, that.invokingSessionId)
                && Objects.equals(previousSnapshot, that.previousSnapshot)
                && Objects.equals(executionAttributes, that.executionAttributes)
                && Objects.equals(llmCallMetadata, that.llmCallMetadata) && Objects.equals(budget, that.budget);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, goal, principal, invokingSessionId, previousSnapshot, executionAttributes,
                llmCallMetadata, budget);
    }

    @Override
    public String toString() {
        return "SubagentExecutionRequest{" + "taskId='" + taskId + '\'' + ", goal='" + goal + '\'' + ", principal="
                + principal + ", invokingSessionId=" + invokingSessionId + ", previousSnapshot=" + previousSnapshot
                + ", executionAttributes=" + executionAttributes + '}';
    }

    /** Builder for SubagentExecutionRequest. */
    public static final class Builder {
        private String taskId;
        private String goal;
        private Principal principal;
        private SessionId invokingSessionId;
        private SessionSnapshot previousSnapshot;
        private Map<String, Object> executionAttributes;
        private LlmCallMetadata llmCallMetadata;
        private ExecutionBudget budget;

        /** taskId를 설정한다. */
        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        /** goal을 설정한다. */
        public Builder goal(String goal) {
            this.goal = goal;
            return this;
        }

        /** 이 실행을 spawn한 대화의 id를 설정한다 (nullable). */
        public Builder invokingSessionId(SessionId invokingSessionId) {
            this.invokingSessionId = invokingSessionId;
            return this;
        }

        /** principal을 설정한다. */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /** previousSnapshot을 설정한다. */
        public Builder previousSnapshot(SessionSnapshot previousSnapshot) {
            this.previousSnapshot = previousSnapshot;
            return this;
        }

        /**
         * Sets the execution attributes.
         *
         * <p>
         * <b>Note:</b> The map is stored using {@code Map.copyOf()}, which creates a shallow copy. Map values should be
         * effectively immutable types (e.g., {@code String}, {@code Integer}).
         *
         * @param executionAttributes
         *            The execution attributes (can be null)
         * @return This builder
         */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /**
         * Sets the LLM call metadata for usage attribution.
         *
         * @param llmCallMetadata
         *            the metadata (can be null, defaults to {@link LlmCallMetadata#empty()})
         * @return this builder
         */
        public Builder llmCallMetadata(LlmCallMetadata llmCallMetadata) {
            this.llmCallMetadata = llmCallMetadata;
            return this;
        }

        /**
         * Sets the execution budget that bounds the subagent's ReAct loop.
         *
         * @param budget
         *            the budget (nullable; {@link ExecutionBudget#unlimited()} is applied when absent)
         * @return this builder
         */
        public Builder budget(ExecutionBudget budget) {
            this.budget = budget;
            return this;
        }

        /** SubagentExecutionRequest를 생성한다. */
        public SubagentExecutionRequest build() {
            return new SubagentExecutionRequest(this);
        }
    }
}
