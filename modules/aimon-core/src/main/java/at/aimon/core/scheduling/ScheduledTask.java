/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentDefinitionVersion;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

/**
 * Represents a scheduled task that executes a routine on a cron schedule.
 *
 * <p>
 * The task is identified by two distinct concepts:
 * <ul>
 * <li>{@link #getOwner() owner} &mdash; the {@link Principal} that owns the task. Used for ownership tracking,
 * authorization, quota accounting, and listing. Survives across sessions.</li>
 * <li>{@link #getBoundRuntimeId() boundRuntimeId} &mdash; the {@link AgentRuntimeId} used at execution time
 * to resolve tools from the {@code AgentRuntimeRegistry}. This is a runtime binding, not an identity.</li>
 * </ul>
 *
 * <p>
 * A third value, {@link #getAgentDefinitionVersion() agentDefinitionVersion}, is neither of those &mdash; it is a
 * record of what the bound agent's definition looked like when the task was created, so that a fire long afterwards
 * can say whether it still looks that way. The definition is <b>not</b> pinned to it; see that accessor.
 */
public final class ScheduledTask {

    private final ScheduledTaskId id;
    private final String name;
    private final String description;
    private final String cronExpression;
    private final String timezone;
    private final List<RoutineStep> routine;
    private final Principal owner;
    private final AgentRuntimeId boundRuntimeId;
    private final AgentDefinitionVersion agentDefinitionVersion;
    private final boolean enabled;
    private final Instant createdAt;
    private final Instant lastExecutedAt;

    private ScheduledTask(Builder builder) {
        id = Objects.requireNonNull(builder.id, "Task ID cannot be null");
        name = Objects.requireNonNull(builder.name, "Task name cannot be null");
        description = builder.description;
        cronExpression = Objects.requireNonNull(builder.cronExpression, "Cron expression cannot be null");
        timezone = builder.timezone;
        routine = builder.routine != null ? List.copyOf(builder.routine) : List.of();
        owner = Objects.requireNonNull(builder.owner, "Owner cannot be null");
        boundRuntimeId = Objects.requireNonNull(builder.boundRuntimeId, "Bound runtime ID cannot be null");
        agentDefinitionVersion = builder.agentDefinitionVersion;
        enabled = builder.enabled;
        createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        lastExecutedAt = builder.lastExecutedAt;

        if (routine.isEmpty()) {
            throw new IllegalArgumentException("Routine must contain at least one step");
        }
    }

    /** Builder를 생성한다. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new task with the last executed time updated.
     *
     * @param lastExecutedAt
     *            the new last executed time
     * @return a new ScheduledTask with updated lastExecutedAt
     */
    public ScheduledTask withLastExecutedAt(Instant lastExecutedAt) {
        return copy().lastExecutedAt(lastExecutedAt).build();
    }

    /**
     * Creates a new task with enabled status updated.
     *
     * @param enabled
     *            the new enabled status
     * @return a new ScheduledTask with updated enabled status
     */
    public ScheduledTask withEnabled(boolean enabled) {
        return copy().enabled(enabled).build();
    }

    /**
     * Returns a builder pre-loaded with every field of this task.
     *
     * <p>
     * The {@code with*} methods above go through here rather than each listing the fields themselves. Listing them
     * was how a field could be added to the class and silently dropped on the next {@code withLastExecutedAt} &mdash;
     * which runs after every fire, so the loss would look like the value was never recorded.
     */
    private Builder copy() {
        return new Builder().id(id).name(name).description(description).cronExpression(cronExpression)
                .timezone(timezone).routine(routine).owner(owner).boundRuntimeId(boundRuntimeId)
                .agentDefinitionVersion(agentDefinitionVersion).enabled(enabled).createdAt(createdAt)
                .lastExecutedAt(lastExecutedAt);
    }

    public ScheduledTaskId getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public String getTimezone() {
        return timezone;
    }

    public List<RoutineStep> getRoutine() {
        return routine;
    }

    /**
     * Returns the {@link Principal} that owns this task. Used for authorization and quota.
     */
    public Principal getOwner() {
        return owner;
    }

    /**
     * Returns the agent-runtime binding used at task execution time to resolve tools. This is a runtime handle,
     * not an identity &mdash; it may not refer to a live context when the cron fires.
     */
    public AgentRuntimeId getBoundRuntimeId() {
        return boundRuntimeId;
    }

    /**
     * Returns the agent definition's version as it stood when this task was created, if it was recorded.
     *
     * <p>
     * <b>This does not pin anything.</b> The task still runs against whatever definition the bound runtime carries
     * when the cron fires; recording the version buys the ability to notice that the two differ and say so in the log,
     * which is the whole of its purpose. Pinning was considered and rejected &mdash; a task silently running a prompt
     * its owner edited weeks ago is worse than one running the current prompt with a warning attached.
     *
     * <p>
     * Empty on tasks created before the version was recorded, and on any caller that does not supply it. An absent
     * version disables the comparison; it never fails a run.
     *
     * @return The recorded definition version, or empty if none was recorded
     */
    public Optional<AgentDefinitionVersion> getAgentDefinitionVersion() {
        return Optional.ofNullable(agentDefinitionVersion);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Optional<Instant> getLastExecutedAt() {
        return Optional.ofNullable(lastExecutedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ScheduledTask that = (ScheduledTask) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ScheduledTask{id='" + id + "', name='" + name + "', cron='" + cronExpression + "', enabled=" + enabled
                + ", owner=" + owner + ", boundRuntimeId=" + boundRuntimeId + '}';
    }

    /**
     * Builder for {@link ScheduledTask}.
     */
    public static final class Builder {

        private ScheduledTaskId id;
        private String name;
        private String description;
        private String cronExpression;
        private String timezone;
        private List<RoutineStep> routine = new ArrayList<>();
        private Principal owner;
        private AgentRuntimeId boundRuntimeId;
        private AgentDefinitionVersion agentDefinitionVersion;
        private boolean enabled = true;
        private Instant createdAt;
        private Instant lastExecutedAt;

        private Builder() {
        }

        /** 태스크 ID를 설정한다. */
        public Builder id(ScheduledTaskId id) {
            this.id = id;
            return this;
        }

        /** 태스크 이름을 설정한다. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** 태스크 설명을 설정한다. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** cron 표현식을 설정한다. */
        public Builder cronExpression(String cronExpression) {
            this.cronExpression = cronExpression;
            return this;
        }

        /** 타임존을 설정한다. */
        public Builder timezone(String timezone) {
            this.timezone = timezone;
            return this;
        }

        /** 루틴 단계 목록을 설정한다. */
        public Builder routine(List<RoutineStep> routine) {
            this.routine = routine != null ? new ArrayList<>(routine) : new ArrayList<>();
            return this;
        }

        /** 루틴 단계를 추가한다. */
        public Builder addStep(RoutineStep step) {
            routine.add(step);
            return this;
        }

        /**
         * Sets the {@link Principal} that owns this task. Required.
         */
        public Builder owner(Principal owner) {
            this.owner = owner;
            return this;
        }

        /**
         * Sets the agent-runtime binding used at task execution time. Required.
         */
        public Builder boundRuntimeId(AgentRuntimeId boundRuntimeId) {
            this.boundRuntimeId = boundRuntimeId;
            return this;
        }

        /**
         * Records the bound agent's definition version at the time of scheduling. Optional &mdash; leaving it unset
         * means a fire cannot report whether the definition drifted, not that the task will refuse to run.
         */
        public Builder agentDefinitionVersion(AgentDefinitionVersion agentDefinitionVersion) {
            this.agentDefinitionVersion = agentDefinitionVersion;
            return this;
        }

        /** 활성화 여부를 설정한다. */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** 생성 시각을 설정한다. */
        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /** 마지막 실행 시각을 설정한다. */
        public Builder lastExecutedAt(Instant lastExecutedAt) {
            this.lastExecutedAt = lastExecutedAt;
            return this;
        }

        /** ScheduledTask를 생성한다. */
        public ScheduledTask build() {
            return new ScheduledTask(this);
        }
    }
}
