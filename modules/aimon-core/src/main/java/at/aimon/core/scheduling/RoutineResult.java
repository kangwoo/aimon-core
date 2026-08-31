/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.interrupt.InterruptReason;

/**
 * Represents the result of executing a complete routine.
 *
 * <p>
 * A run ends one of three ways, and the third is not a flavour of the second: it {@link #isSuccess() succeeded}, it
 * failed on a step, or it was {@link #isCancelled() cancelled} before it could finish. A cancelled run reports
 * {@code isSuccess() == false} like a failure does &mdash; there is no completed routine to hand back &mdash; but it
 * carries an {@link #getInterruptReason() interrupt reason} instead of a fault, and callers that distinguish "this
 * task is broken" from "somebody stopped it" must read that rather than the success flag.
 */
public final class RoutineResult {

    private final ScheduledTaskId taskId;
    private final boolean success;
    private final List<StepResult> stepResults;
    private final String errorMessage;
    private final InterruptReason interruptReason;
    private final Instant startedAt;
    private final Instant completedAt;

    private RoutineResult(Builder builder) {
        taskId = Objects.requireNonNull(builder.taskId, "Task ID cannot be null");
        success = builder.success;
        stepResults = builder.stepResults != null ? List.copyOf(builder.stepResults) : List.of();
        errorMessage = builder.errorMessage;
        interruptReason = builder.interruptReason;
        startedAt = builder.startedAt;
        completedAt = builder.completedAt;
    }

    /**
     * Creates a successful routine result.
     */
    public static RoutineResult success(ScheduledTaskId taskId, List<StepResult> stepResults, Instant startedAt,
            Instant completedAt) {
        return builder().taskId(taskId).success(true).stepResults(stepResults).startedAt(startedAt)
                .completedAt(completedAt).build();
    }

    /**
     * Creates a failed routine result.
     */
    public static RoutineResult failure(ScheduledTaskId taskId, List<StepResult> stepResults, String errorMessage,
            Instant startedAt, Instant completedAt) {
        return builder().taskId(taskId).success(false).stepResults(stepResults).errorMessage(errorMessage)
                .startedAt(startedAt).completedAt(completedAt).build();
    }

    /**
     * Creates a cancelled routine result &mdash; the run was interrupted before it could complete every step.
     *
     * <p>
     * {@code stepResults} holds the steps that had already been attempted; the steps after them were never started.
     *
     * @param taskId
     *            the task whose run was cancelled
     * @param stepResults
     *            results of the steps attempted before the interrupt landed
     * @param interruptReason
     *            why the run was interrupted (must not be null)
     * @param startedAt
     *            when the run started
     * @param completedAt
     *            when the run unwound
     * @return the cancelled result
     */
    public static RoutineResult cancelled(ScheduledTaskId taskId, List<StepResult> stepResults,
            InterruptReason interruptReason, Instant startedAt, Instant completedAt) {
        Objects.requireNonNull(interruptReason, "Interrupt reason cannot be null");
        return builder().taskId(taskId).success(false).stepResults(stepResults).interruptReason(interruptReason)
                .errorMessage("Routine cancelled: " + interruptReason).startedAt(startedAt).completedAt(completedAt)
                .build();
    }

    /** Builder를 생성한다. */
    public static Builder builder() {
        return new Builder();
    }

    public ScheduledTaskId getTaskId() {
        return taskId;
    }

    public boolean isSuccess() {
        return success;
    }

    public List<StepResult> getStepResults() {
        return stepResults;
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    /**
     * Reports whether this run was cancelled rather than completed or failed on its own.
     *
     * @return {@code true} if an interrupt ended the run
     */
    public boolean isCancelled() {
        return interruptReason != null;
    }

    /**
     * @return why the run was interrupted, or empty if it was not
     */
    public Optional<InterruptReason> getInterruptReason() {
        return Optional.ofNullable(interruptReason);
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    /** 실행 소요 시간을 반환한다. */
    public Duration getDuration() {
        if (startedAt != null && completedAt != null) {
            return Duration.between(startedAt, completedAt);
        }
        return Duration.ZERO;
    }

    public int getCompletedStepCount() {
        return (int) stepResults.stream().filter(StepResult::isSuccess).count();
    }

    public int getTotalStepCount() {
        return stepResults.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RoutineResult that = (RoutineResult) o;
        return success == that.success && taskId.equals(that.taskId) && stepResults.equals(that.stepResults)
                && Objects.equals(errorMessage, that.errorMessage)
                && Objects.equals(interruptReason, that.interruptReason) && Objects.equals(startedAt, that.startedAt)
                && Objects.equals(completedAt, that.completedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, success, stepResults, errorMessage, interruptReason, startedAt, completedAt);
    }

    @Override
    public String toString() {
        return "RoutineResult{taskId='" + taskId + "', success=" + success + ", steps=" + getCompletedStepCount() + '/'
                + getTotalStepCount() + (interruptReason != null ? ", cancelled=" + interruptReason : "")
                + ", duration=" + getDuration() + '}';
    }

    /**
     * Builder for {@link RoutineResult}.
     */
    public static final class Builder {

        private ScheduledTaskId taskId;
        private boolean success;
        private List<StepResult> stepResults = new ArrayList<>();
        private String errorMessage;
        private InterruptReason interruptReason;
        private Instant startedAt;
        private Instant completedAt;

        private Builder() {
        }

        /** 태스크 ID를 설정한다. */
        public Builder taskId(ScheduledTaskId taskId) {
            this.taskId = taskId;
            return this;
        }

        /** 성공 여부를 설정한다. */
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /** 단계 결과 목록을 설정한다. */
        public Builder stepResults(List<StepResult> stepResults) {
            this.stepResults = stepResults != null ? new ArrayList<>(stepResults) : new ArrayList<>();
            return this;
        }

        /** 단계 결과를 추가한다. */
        public Builder addStepResult(StepResult stepResult) {
            stepResults.add(stepResult);
            return this;
        }

        /** 에러 메시지를 설정한다. */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /** 중단 사유를 설정한다. 설정하면 결과가 취소된 실행으로 표시된다. */
        public Builder interruptReason(InterruptReason interruptReason) {
            this.interruptReason = interruptReason;
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

        /** RoutineResult를 생성한다. */
        public RoutineResult build() {
            return new RoutineResult(this);
        }
    }
}
