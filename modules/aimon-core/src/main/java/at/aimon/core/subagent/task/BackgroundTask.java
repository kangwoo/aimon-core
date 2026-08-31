package at.aimon.core.subagent.task;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

/**
 * Immutable metadata snapshot of a background subagent task.
 *
 * <p>
 * This is the durable, node-independent record of a detached subagent execution — the value that a
 * {@link BackgroundTaskStore} persists so that task listing ({@code Task.list}) and status queries survive across
 * instances in a scale-out deployment. It deliberately carries <b>no</b> node-local execution handles
 * ({@code CompletableFuture}, {@code Thread}, {@code InterruptCoordinator}); those live in {@code RunningTaskHandle} on
 * the node that owns the running task.
 *
 * <p>
 * Instances are created via {@link #builder()} and evolved via {@link #toBuilder()} — the store derives a new snapshot
 * on every state transition rather than mutating in place.
 */
public final class BackgroundTask {

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String taskId;
    private final String subagentName;
    private final String description;
    private final BackgroundTaskState state;
    private final Instant startTime;
    private final Instant endTime;
    private final long outputOffset;
    private final Principal owner;
    private final AgentRuntimeId agentRuntimeId;
    private final Instant lastHeartbeat;

    private BackgroundTask(Builder builder) {
        this.taskId = Objects.requireNonNull(builder.taskId, "taskId cannot be null");
        this.subagentName = Objects.requireNonNull(builder.subagentName, "subagentName cannot be null");
        this.description = builder.description != null ? builder.description : "";
        this.state = Objects.requireNonNull(builder.state, "state cannot be null");
        this.startTime = Objects.requireNonNull(builder.startTime, "startTime cannot be null");
        this.endTime = builder.endTime;
        this.outputOffset = builder.outputOffset;
        this.owner = builder.owner;
        this.agentRuntimeId = builder.agentRuntimeId;
        this.lastHeartbeat = builder.lastHeartbeat;
    }

    /**
     * @return the unique task identifier (never null)
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * @return the name of the subagent that runs (or ran) this task (never null)
     */
    public String getSubagentName() {
        return subagentName;
    }

    /**
     * @return the human-readable task description (never null, may be empty)
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return the current lifecycle state (never null)
     */
    public BackgroundTaskState getState() {
        return state;
    }

    /**
     * @return the instant the task was registered / started (never null)
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * @return the instant the task reached a terminal state, or empty while still non-terminal
     */
    public Optional<Instant> getEndTime() {
        return Optional.ofNullable(endTime);
    }

    /**
     * @return the number of output characters already delivered to the parent (streaming cursor; {@code 0} until output
     *         streaming lands in a later phase)
     */
    public long getOutputOffset() {
        return outputOffset;
    }

    /**
     * @return the owning principal (caller identity) that spawned this task, or empty if none was forwarded
     */
    public Optional<Principal> getOwner() {
        return Optional.ofNullable(owner);
    }

    /**
     * @return the agent runtime that owns this task, or empty if none was recorded
     */
    public Optional<AgentRuntimeId> getAgentRuntimeId() {
        return Optional.ofNullable(agentRuntimeId);
    }

    /**
     * Returns the instant the owning node last renewed this task's lease (heartbeat). The owning node stamps this on
     * spawn and a {@link TaskHeartbeatPublisher} refreshes it periodically while the task is non-terminal; a
     * {@link ZombieTaskReaper} on any surviving node reaps a non-terminal task whose heartbeat has aged past the lease
     * TTL (its owner is presumed lost). Empty when lease tracking is not enabled or the snapshot predates it — such a
     * task is treated as heartbeated at {@link #getStartTime() start time} for staleness purposes.
     *
     * @return the last heartbeat instant, or empty if never stamped
     */
    public Optional<Instant> getLastHeartbeat() {
        return Optional.ofNullable(lastHeartbeat);
    }

    /**
     * Returns a builder pre-populated with this task's fields, for deriving an evolved snapshot (e.g. a state
     * transition). The store uses this to produce a new immutable instance rather than mutating in place.
     *
     * @return a builder seeded from this instance
     */
    public Builder toBuilder() {
        return new Builder().taskId(taskId).subagentName(subagentName).description(description).state(state)
                .startTime(startTime).endTime(endTime).outputOffset(outputOffset).owner(owner)
                .agentRuntimeId(agentRuntimeId).lastHeartbeat(lastHeartbeat);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BackgroundTask that = (BackgroundTask) o;
        return outputOffset == that.outputOffset && taskId.equals(that.taskId) && subagentName.equals(that.subagentName)
                && description.equals(that.description) && state == that.state && startTime.equals(that.startTime)
                && Objects.equals(endTime, that.endTime) && Objects.equals(owner, that.owner)
                && Objects.equals(agentRuntimeId, that.agentRuntimeId)
                && Objects.equals(lastHeartbeat, that.lastHeartbeat);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, subagentName, description, state, startTime, endTime, outputOffset, owner,
                agentRuntimeId, lastHeartbeat);
    }

    @Override
    public String toString() {
        return "BackgroundTask{" + "taskId='" + taskId + '\'' + ", subagentName='" + subagentName + '\'' + ", state="
                + state + ", startTime=" + startTime + ", endTime=" + endTime + '}';
    }

    /** Builder for {@link BackgroundTask}. */
    public static final class Builder {
        private String taskId;
        private String subagentName;
        private String description;
        private BackgroundTaskState state;
        private Instant startTime;
        private Instant endTime;
        private long outputOffset;
        private Principal owner;
        private AgentRuntimeId agentRuntimeId;
        private Instant lastHeartbeat;

        private Builder() {
        }

        /** Sets the task identifier. */
        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        /** Sets the subagent name. */
        public Builder subagentName(String subagentName) {
            this.subagentName = subagentName;
            return this;
        }

        /** Sets the human-readable description. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Sets the lifecycle state. */
        public Builder state(BackgroundTaskState state) {
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

        /** Sets the output streaming cursor. */
        public Builder outputOffset(long outputOffset) {
            this.outputOffset = outputOffset;
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
         * @return a new immutable {@link BackgroundTask}
         */
        public BackgroundTask build() {
            return new BackgroundTask(this);
        }
    }
}
