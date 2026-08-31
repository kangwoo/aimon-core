/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents the execution history of a scheduled task.
 */
public final class ScheduledTaskExecutionHistory {

    private final String id;
    private final ScheduledTaskId taskId;
    private final Status status;
    private final int completedSteps;
    private final int totalSteps;
    private final String errorMessage;
    private final Instant startedAt;
    private final Instant completedAt;

    private ScheduledTaskExecutionHistory(Builder builder) {
        id = Objects.requireNonNull(builder.id, "History ID cannot be null");
        taskId = Objects.requireNonNull(builder.taskId, "Task ID cannot be null");
        status = Objects.requireNonNull(builder.status, "Status cannot be null");
        completedSteps = builder.completedSteps;
        totalSteps = builder.totalSteps;
        errorMessage = builder.errorMessage;
        startedAt = Objects.requireNonNull(builder.startedAt, "Started at cannot be null");
        completedAt = Objects.requireNonNull(builder.completedAt, "Completed at cannot be null");
    }

    /**
     * Creates a history record from a routine result.
     *
     * <p>
     * Cancellation is read before the step counts, because a cancelled run is not a partially failed one. Both stop
     * short of the last step, but only one of them says anything about the task: {@link Status#PARTIAL} invites a look
     * at the step that broke, and there is no such step here. Folding the two together would make a task that someone
     * stopped read, in its own history, exactly like a task that is failing intermittently.
     */
    public static ScheduledTaskExecutionHistory fromRoutineResult(String historyId, RoutineResult result) {
        final Status status;
        if (result.isSuccess()) {
            status = Status.SUCCESS;
        } else if (result.isCancelled()) {
            status = Status.CANCELLED;
        } else if (result.getCompletedStepCount() > 0) {
            status = Status.PARTIAL;
        } else {
            status = Status.FAILURE;
        }

        return builder().id(historyId).taskId(result.getTaskId()).status(status)
                .completedSteps(result.getCompletedStepCount()).totalSteps(result.getTotalStepCount())
                .errorMessage(result.getErrorMessage().orElse(null)).startedAt(result.getStartedAt())
                .completedAt(result.getCompletedAt()).build();
    }

    /** Builder를 생성한다. */
    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public ScheduledTaskId getTaskId() {
        return taskId;
    }

    public Status getStatus() {
        return status;
    }

    public int getCompletedSteps() {
        return completedSteps;
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Duration getDuration() {
        return Duration.between(startedAt, completedAt);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ScheduledTaskExecutionHistory that = (ScheduledTaskExecutionHistory) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ScheduledTaskExecutionHistory{id='" + id + "', taskId='" + taskId + "', status=" + status + ", steps="
                + completedSteps + '/' + totalSteps + ", duration=" + getDuration() + '}';
    }

    /**
     * Execution status.
     *
     * <p>
     * {@code CANCELLED} is not a kind of failure. A run that was interrupted &mdash; by its owner cancelling the task,
     * or by the host shutting down &mdash; carries no fault to attribute to the task, so it is kept apart from
     * {@code FAILURE} and {@code PARTIAL}, which both mean a step did not do what it was asked.
     */
    public enum Status {
        SUCCESS, FAILURE, PARTIAL, CANCELLED
    }

    /**
     * Builder for {@link ScheduledTaskExecutionHistory}.
     */
    public static final class Builder {

        private String id;
        private ScheduledTaskId taskId;
        private Status status;
        private int completedSteps;
        private int totalSteps;
        private String errorMessage;
        private Instant startedAt;
        private Instant completedAt;

        private Builder() {
        }

        /** 이력 ID를 설정한다. */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /** 태스크 ID를 설정한다. */
        public Builder taskId(ScheduledTaskId taskId) {
            this.taskId = taskId;
            return this;
        }

        /** 실행 상태를 설정한다. */
        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        /** 완료된 단계 수를 설정한다. */
        public Builder completedSteps(int completedSteps) {
            this.completedSteps = completedSteps;
            return this;
        }

        /** 전체 단계 수를 설정한다. */
        public Builder totalSteps(int totalSteps) {
            this.totalSteps = totalSteps;
            return this;
        }

        /** 에러 메시지를 설정한다. */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /** 시작 시각을 설정한다. */
        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        /** 완료 시각을 설정한다. */
        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        /** ScheduledTaskExecutionHistory를 생성한다. */
        public ScheduledTaskExecutionHistory build() {
            return new ScheduledTaskExecutionHistory(this);
        }
    }
}
