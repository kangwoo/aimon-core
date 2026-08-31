package at.aimon.core.subagent.task;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

/**
 * Immutable filter over {@link BackgroundTask} records, used by {@link BackgroundTaskStore#list(TaskQuery)}.
 *
 * <p>
 * Each criterion is optional; an absent criterion matches everything. {@link #all()} returns the unfiltered query.
 * Filtering by {@code agentRuntimeId} is the primary scoping tool in a multi-instance deployment — a caller lists only
 * the
 * tasks belonging to its own agent runtime.
 */
public final class TaskQuery {

    private static final TaskQuery ALL = new TaskQuery(null, null, null);

    /**
     * @return a query that matches every task
     */
    public static TaskQuery all() {
        return ALL;
    }

    /**
     * @param state
     *            the state to match (must not be null)
     * @return a query matching only tasks in the given state
     */
    public static TaskQuery byState(BackgroundTaskState state) {
        return new TaskQuery(Objects.requireNonNull(state, "state cannot be null"), null, null);
    }

    /**
     * @param agentRuntimeId
     *            the context to match (must not be null)
     * @return a query matching only tasks owned by the given agent runtime
     */
    public static TaskQuery byAgentRuntime(AgentRuntimeId agentRuntimeId) {
        return new TaskQuery(null, null, Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null"));
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final BackgroundTaskState state;
    private final Principal owner;
    private final AgentRuntimeId agentRuntimeId;

    private TaskQuery(BackgroundTaskState state, Principal owner, AgentRuntimeId agentRuntimeId) {
        this.state = state;
        this.owner = owner;
        this.agentRuntimeId = agentRuntimeId;
    }

    /**
     * @return the state criterion, or empty to match any state
     */
    public Optional<BackgroundTaskState> getState() {
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
     * Tests whether a task satisfies every present criterion of this query.
     *
     * @param task
     *            the task to test (must not be null)
     * @return {@code true} if the task matches all present criteria
     */
    public boolean matches(BackgroundTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        if (state != null && task.getState() != state) {
            return false;
        }
        if (owner != null && !owner.equals(task.getOwner().orElse(null))) {
            return false;
        }
        if (agentRuntimeId != null && !agentRuntimeId.equals(task.getAgentRuntimeId().orElse(null))) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "TaskQuery{" + "state=" + state + ", owner=" + owner + ", agentRuntimeId=" + agentRuntimeId + '}';
    }

    /** Builder for {@link TaskQuery}. */
    public static final class Builder {
        private BackgroundTaskState state;
        private Principal owner;
        private AgentRuntimeId agentRuntimeId;

        private Builder() {
        }

        /** Restricts the query to tasks in the given state (nullable = any). */
        public Builder state(BackgroundTaskState state) {
            this.state = state;
            return this;
        }

        /** Restricts the query to tasks owned by the given principal (nullable = any). */
        public Builder owner(Principal owner) {
            this.owner = owner;
            return this;
        }

        /** Restricts the query to tasks owned by the given context (nullable = any). */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * @return a new immutable {@link TaskQuery}
         */
        public TaskQuery build() {
            return new TaskQuery(state, owner, agentRuntimeId);
        }
    }
}
