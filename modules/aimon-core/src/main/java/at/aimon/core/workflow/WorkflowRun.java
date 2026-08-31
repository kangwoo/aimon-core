package at.aimon.core.workflow;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

/**
 * Immutable metadata snapshot of a background workflow run.
 *
 * <p>
 * Run-scoped analog of {@code at.aimon.core.subagent.task.BackgroundTask}: the durable, node-independent record of a
 * detached {@link WorkflowScript} execution — the value a {@code RunStore} persists so that run listing and status
 * queries survive across instances in a scale-out deployment. It deliberately carries <b>no</b> node-local execution
 * handles ({@code CompletableFuture}, {@code Thread}, {@code InterruptCoordinator}) and <b>no</b> typed script result
 * {@code T}; those live only on the node that owns the running run (see {@code RunHandle} / {@code RunningRunRegistry},
 * design §5.1). The typed result is not serialized, so cross-node observers see only run state, not {@code T}.
 *
 * <p>
 * Instances are created via {@link #builder()} (or the {@link #pending} convenience) and evolved via
 * {@link #toBuilder()} — the store derives a new snapshot on every state transition rather than mutating in place.
 */
public final class WorkflowRun {

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience factory for the initial {@link WorkflowRunState#PENDING} snapshot at submission time.
     *
     * @param runId
     *            the run identifier (must not be null)
     * @param scriptName
     *            the human-readable script name (must not be null)
     * @param owner
     *            the owning principal, or null if none was forwarded
     * @param agentRuntimeId
     *            the owning agent runtime, or null if none was recorded
     * @param startTime
     *            the submission instant (must not be null; injected by the caller for testability)
     * @return a new PENDING run snapshot
     */
    public static WorkflowRun pending(RunId runId, String scriptName, Principal owner, AgentRuntimeId agentRuntimeId,
            Instant startTime) {
        return builder().runId(runId).scriptName(scriptName).state(WorkflowRunState.PENDING).owner(owner)
                .agentRuntimeId(agentRuntimeId).startTime(startTime).build();
    }

    private final RunId runId;
    private final String scriptName;
    private final WorkflowRunState state;
    private final Instant startTime;
    private final Instant endTime;
    private final Principal owner;
    private final AgentRuntimeId agentRuntimeId;
    private final Instant lastHeartbeat;

    private WorkflowRun(Builder builder) {
        this.runId = Objects.requireNonNull(builder.runId, "runId cannot be null");
        this.scriptName = Objects.requireNonNull(builder.scriptName, "scriptName cannot be null");
        this.state = Objects.requireNonNull(builder.state, "state cannot be null");
        this.startTime = Objects.requireNonNull(builder.startTime, "startTime cannot be null");
        this.endTime = builder.endTime;
        this.owner = builder.owner;
        this.agentRuntimeId = builder.agentRuntimeId;
        this.lastHeartbeat = builder.lastHeartbeat;
    }

    /**
     * @return the unique run identifier (never null)
     */
    public RunId getRunId() {
        return runId;
    }

    /**
     * @return the human-readable script name (never null)
     */
    public String getScriptName() {
        return scriptName;
    }

    /**
     * @return the current lifecycle state (never null)
     */
    public WorkflowRunState getState() {
        return state;
    }

    /**
     * @return the instant the run was registered / started (never null)
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * @return the instant the run reached a terminal state, or empty while still non-terminal
     */
    public Optional<Instant> getEndTime() {
        return Optional.ofNullable(endTime);
    }

    /**
     * @return the owning principal (caller identity) that submitted this run, or empty if none was forwarded
     */
    public Optional<Principal> getOwner() {
        return Optional.ofNullable(owner);
    }

    /**
     * @return the agent runtime that owns this run, or empty if none was recorded
     */
    public Optional<AgentRuntimeId> getAgentRuntimeId() {
        return Optional.ofNullable(agentRuntimeId);
    }

    /**
     * Returns the instant the owning node last renewed this run's lease (heartbeat). Empty when lease tracking is not
     * enabled or the snapshot predates it — such a run is treated as heartbeated at {@link #getStartTime() start time}
     * for staleness purposes.
     *
     * @return the last heartbeat instant, or empty if never stamped
     */
    public Optional<Instant> getLastHeartbeat() {
        return Optional.ofNullable(lastHeartbeat);
    }

    /**
     * Returns a builder pre-populated with this run's fields, for deriving an evolved snapshot (e.g. a state
     * transition). The store uses this to produce a new immutable instance rather than mutating in place.
     *
     * @return a builder seeded from this instance
     */
    public Builder toBuilder() {
        return new Builder().runId(runId).scriptName(scriptName).state(state).startTime(startTime).endTime(endTime)
                .owner(owner).agentRuntimeId(agentRuntimeId).lastHeartbeat(lastHeartbeat);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final WorkflowRun that = (WorkflowRun) o;
        return runId.equals(that.runId) && scriptName.equals(that.scriptName) && state == that.state
                && startTime.equals(that.startTime) && Objects.equals(endTime, that.endTime)
                && Objects.equals(owner, that.owner) && Objects.equals(agentRuntimeId, that.agentRuntimeId)
                && Objects.equals(lastHeartbeat, that.lastHeartbeat);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, scriptName, state, startTime, endTime, owner, agentRuntimeId, lastHeartbeat);
    }

    @Override
    public String toString() {
        return "WorkflowRun{" + "runId=" + runId + ", scriptName='" + scriptName + '\'' + ", state=" + state
                + ", startTime=" + startTime + ", endTime=" + endTime + '}';
    }

    /** Builder for {@link WorkflowRun}. */
    public static final class Builder {
        private RunId runId;
        private String scriptName;
        private WorkflowRunState state;
        private Instant startTime;
        private Instant endTime;
        private Principal owner;
        private AgentRuntimeId agentRuntimeId;
        private Instant lastHeartbeat;

        private Builder() {
        }

        /** Sets the run identifier. */
        public Builder runId(RunId runId) {
            this.runId = runId;
            return this;
        }

        /** Sets the human-readable script name. */
        public Builder scriptName(String scriptName) {
            this.scriptName = scriptName;
            return this;
        }

        /** Sets the lifecycle state. */
        public Builder state(WorkflowRunState state) {
            this.state = state;
            return this;
        }

        /** Sets the start instant. */
        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        /** Sets the terminal instant (nullable while non-terminal). */
        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        /** Sets the owning principal (nullable). */
        public Builder owner(Principal owner) {
            this.owner = owner;
            return this;
        }

        /** Sets the owning agent runtime id (nullable). */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /** Sets the last lease-heartbeat instant (nullable; renewed by the owning node while non-terminal). */
        public Builder lastHeartbeat(Instant lastHeartbeat) {
            this.lastHeartbeat = lastHeartbeat;
            return this;
        }

        /**
         * @return a new immutable {@link WorkflowRun}
         */
        public WorkflowRun build() {
            return new WorkflowRun(this);
        }
    }
}
