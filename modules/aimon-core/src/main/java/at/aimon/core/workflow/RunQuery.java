package at.aimon.core.workflow;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

/**
 * Immutable filter over {@link WorkflowRun} records, used by a {@code RunStore}'s {@code list(RunQuery)}.
 *
 * <p>
 * Run-scoped analog of {@code at.aimon.core.subagent.task.TaskQuery}. Each criterion is optional; an absent criterion
 * matches everything. {@link #all()} returns the unfiltered query. Filtering by {@code agentRuntimeId} is the primary
 * scoping tool in a multi-instance deployment — a caller lists only the runs belonging to its own agent execution
 * context.
 */
public final class RunQuery {

    private static final RunQuery ALL = new RunQuery(null, null, null);

    /**
     * @return a query that matches every run
     */
    public static RunQuery all() {
        return ALL;
    }

    /**
     * @param state
     *            the state to match (must not be null)
     * @return a query matching only runs in the given state
     */
    public static RunQuery byState(WorkflowRunState state) {
        return new RunQuery(Objects.requireNonNull(state, "state cannot be null"), null, null);
    }

    /**
     * @param agentRuntimeId
     *            the context to match (must not be null)
     * @return a query matching only runs owned by the given agent runtime
     */
    public static RunQuery byAgentRuntime(AgentRuntimeId agentRuntimeId) {
        return new RunQuery(null, null, Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null"));
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final WorkflowRunState state;
    private final Principal owner;
    private final AgentRuntimeId agentRuntimeId;

    private RunQuery(WorkflowRunState state, Principal owner, AgentRuntimeId agentRuntimeId) {
        this.state = state;
        this.owner = owner;
        this.agentRuntimeId = agentRuntimeId;
    }

    /**
     * @return the state criterion, or empty to match any state
     */
    public Optional<WorkflowRunState> getState() {
        return Optional.ofNullable(state);
    }

    /**
     * @return the owner criterion, or empty to match any owner
     */
    public Optional<Principal> getOwner() {
        return Optional.ofNullable(owner);
    }

    /**
     * @return the context criterion, or empty to match any context
     */
    public Optional<AgentRuntimeId> getAgentRuntimeId() {
        return Optional.ofNullable(agentRuntimeId);
    }

    /**
     * Tests whether a run satisfies every present criterion of this query.
     *
     * @param run
     *            the run to test (must not be null)
     * @return {@code true} if the run matches all present criteria
     */
    public boolean matches(WorkflowRun run) {
        Objects.requireNonNull(run, "run cannot be null");
        if (state != null && run.getState() != state) {
            return false;
        }
        if (owner != null && !owner.equals(run.getOwner().orElse(null))) {
            return false;
        }
        if (agentRuntimeId != null && !agentRuntimeId.equals(run.getAgentRuntimeId().orElse(null))) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "RunQuery{" + "state=" + state + ", owner=" + owner + ", agentRuntimeId=" + agentRuntimeId + '}';
    }

    /** Builder for {@link RunQuery}. */
    public static final class Builder {
        private WorkflowRunState state;
        private Principal owner;
        private AgentRuntimeId agentRuntimeId;

        private Builder() {
        }

        /** Restricts the query to runs in the given state (nullable = any). */
        public Builder state(WorkflowRunState state) {
            this.state = state;
            return this;
        }

        /** Restricts the query to runs owned by the given principal (nullable = any). */
        public Builder owner(Principal owner) {
            this.owner = owner;
            return this;
        }

        /** Restricts the query to runs owned by the given context (nullable = any). */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * @return a new immutable {@link RunQuery}
         */
        public RunQuery build() {
            return new RunQuery(state, owner, agentRuntimeId);
        }
    }
}
