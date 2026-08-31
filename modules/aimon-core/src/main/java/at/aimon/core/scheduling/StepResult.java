/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents the result of executing a single routine step.
 */
public final class StepResult {

    private final int stepIndex;
    private final RoutineStep step;
    private final boolean success;
    private final String stdout;
    private final String errorMessage;
    private final int attemptCount;
    private final Instant startedAt;
    private final Instant completedAt;

    private StepResult(Builder builder) {
        stepIndex = builder.stepIndex;
        step = Objects.requireNonNull(builder.step, "Step cannot be null");
        success = builder.success;
        stdout = builder.stdout;
        errorMessage = builder.errorMessage;
        attemptCount = builder.attemptCount;
        startedAt = builder.startedAt;
        completedAt = builder.completedAt;
    }

    /**
     * Creates a successful step result.
     */
    public static StepResult success(int stepIndex, RoutineStep step, String stdout, int attemptCount,
            Instant startedAt, Instant completedAt) {
        return builder().stepIndex(stepIndex).step(step).success(true).stdout(stdout).attemptCount(attemptCount)
                .startedAt(startedAt).completedAt(completedAt).build();
    }

    /**
     * Creates a failed step result.
     */
    public static StepResult failure(int stepIndex, RoutineStep step, String errorMessage, int attemptCount,
            Instant startedAt, Instant completedAt) {
        return builder().stepIndex(stepIndex).step(step).success(false).errorMessage(errorMessage)
                .attemptCount(attemptCount).startedAt(startedAt).completedAt(completedAt).build();
    }

    /** Builder를 생성한다. */
    public static Builder builder() {
        return new Builder();
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public RoutineStep getStep() {
        return step;
    }

    public boolean isSuccess() {
        return success;
    }

    public Optional<String> getStdout() {
        return Optional.ofNullable(stdout);
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public int getAttemptCount() {
        return attemptCount;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final StepResult that = (StepResult) o;
        return stepIndex == that.stepIndex && success == that.success && attemptCount == that.attemptCount
                && step.equals(that.step) && Objects.equals(stdout, that.stdout)
                && Objects.equals(errorMessage, that.errorMessage) && Objects.equals(startedAt, that.startedAt)
                && Objects.equals(completedAt, that.completedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stepIndex, step, success, stdout, errorMessage, attemptCount, startedAt, completedAt);
    }

    @Override
    public String toString() {
        return "StepResult{stepIndex=" + stepIndex + ", tool='" + step.getTool() + "', success=" + success
                + ", attemptCount=" + attemptCount + '}';
    }

    /**
     * Builder for {@link StepResult}.
     */
    public static final class Builder {

        private int stepIndex;
        private RoutineStep step;
        private boolean success;
        private String stdout;
        private String errorMessage;
        private int attemptCount = 1;
        private Instant startedAt;
        private Instant completedAt;

        private Builder() {
        }

        /** 단계 인덱스를 설정한다. */
        public Builder stepIndex(int stepIndex) {
            this.stepIndex = stepIndex;
            return this;
        }

        /** 루틴 단계를 설정한다. */
        public Builder step(RoutineStep step) {
            this.step = step;
            return this;
        }

        /** 성공 여부를 설정한다. */
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /** 표준 출력을 설정한다. */
        public Builder stdout(String stdout) {
            this.stdout = stdout;
            return this;
        }

        /** 에러 메시지를 설정한다. */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /** 시도 횟수를 설정한다. */
        public Builder attemptCount(int attemptCount) {
            this.attemptCount = attemptCount;
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

        /** StepResult를 생성한다. */
        public StepResult build() {
            return new StepResult(this);
        }
    }
}
