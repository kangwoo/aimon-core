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
 *
 * <p>
 * Beside success and failure a step can end two more specific ways. It can be {@link #isHalted() halted}: its tool
 * reported that the work is waiting on something outside the run ({@code ToolResult#haltingRoutine}), so the routine
 * stops there. And a failed step's {@link #isOutcomeUnknown() outcome can be unknown}: its last attempt timed out, so
 * the tool may have done its work after all — a failure to report, but not a proof that nothing happened.
 */
public final class StepResult {

    private final int stepIndex;
    private final RoutineStep step;
    private final boolean success;
    private final String stdout;
    private final String errorMessage;
    private final int attemptCount;
    private final String haltReason;
    private final boolean outcomeUnknown;
    private final Instant startedAt;
    private final Instant completedAt;

    private StepResult(Builder builder) {
        stepIndex = builder.stepIndex;
        step = Objects.requireNonNull(builder.step, "Step cannot be null");
        success = builder.success;
        stdout = builder.stdout;
        errorMessage = builder.errorMessage;
        attemptCount = builder.attemptCount;
        haltReason = builder.haltReason;
        outcomeUnknown = builder.outcomeUnknown;
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

    /**
     * Creates the result of a step whose tool asked the routine to stop after it.
     *
     * @param stepIndex
     *            the step's 0-based index in the routine
     * @param step
     *            the step
     * @param haltReason
     *            the reason the tool gave (must not be null)
     * @param stdout
     *            the tool's output, kept for the record
     * @param attemptCount
     *            how many attempts the step took
     * @param startedAt
     *            when the step started
     * @param completedAt
     *            when the step ended
     * @return the halted step result
     */
    public static StepResult halted(int stepIndex, RoutineStep step, String haltReason, String stdout, int attemptCount,
            Instant startedAt, Instant completedAt) {
        Objects.requireNonNull(haltReason, "Halt reason cannot be null");
        return builder().stepIndex(stepIndex).step(step).success(false).stdout(stdout).haltReason(haltReason)
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

    /**
     * Reports whether the step's tool asked the routine to stop after it. A halted step is neither a success nor a
     * failure: {@link #isSuccess()} is {@code false}, and no fault is implied.
     *
     * @return {@code true} if the step halted the routine
     */
    public boolean isHalted() {
        return haltReason != null;
    }

    /**
     * Returns why the step's tool asked the routine to stop.
     *
     * @return the halt reason, or empty if the step did not halt the routine
     */
    public Optional<String> getHaltReason() {
        return Optional.ofNullable(haltReason);
    }

    /**
     * Reports whether the step failed without it being known that its tool did nothing: its last attempt timed out,
     * and a timed-out tool may have finished its work after the executor stopped waiting.
     *
     * @return {@code true} if the step's effect is unknown
     */
    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
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
                && outcomeUnknown == that.outcomeUnknown && Objects.equals(haltReason, that.haltReason)
                && step.equals(that.step) && Objects.equals(stdout, that.stdout)
                && Objects.equals(errorMessage, that.errorMessage) && Objects.equals(startedAt, that.startedAt)
                && Objects.equals(completedAt, that.completedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stepIndex, step, success, stdout, errorMessage, attemptCount, haltReason, outcomeUnknown,
                startedAt, completedAt);
    }

    @Override
    public String toString() {
        return "StepResult{stepIndex=" + stepIndex + ", tool='" + step.getTool() + "', success=" + success
                + ", attemptCount=" + attemptCount + (haltReason != null ? ", halted='" + haltReason + "'" : "")
                + (outcomeUnknown ? ", outcomeUnknown=true" : "") + '}';
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
        private String haltReason;
        private boolean outcomeUnknown;
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

        /**
         * 루틴 중단 사유를 설정한다. 설정하면 중단된 단계로 표시된다.
         *
         * @param haltReason
         *            중단 사유 (중단되지 않았으면 null)
         * @return 이 빌더
         */
        public Builder haltReason(String haltReason) {
            this.haltReason = haltReason;
            return this;
        }

        /**
         * 결과를 알 수 없는 실패(마지막 시도가 시간 초과)인지 설정한다.
         *
         * @param outcomeUnknown
         *            결과를 알 수 없으면 true
         * @return 이 빌더
         */
        public Builder outcomeUnknown(boolean outcomeUnknown) {
            this.outcomeUnknown = outcomeUnknown;
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
