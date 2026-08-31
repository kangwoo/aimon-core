package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;

class ScheduledTaskExecutionHistoryTest {

    private final ScheduledTaskId taskId = ScheduledTaskId.of("t-1");
    private final RoutineStep step = RoutineStep.of("Bash", "{}");
    private final Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    private final Instant t1 = t0.plusSeconds(5);

    @Test
    void fromRoutineResultMapsSuccessfulRunToSuccessStatus() {
        StepResult ok = StepResult.success(0, step, null, 1, t0, t1);
        RoutineResult result = RoutineResult.success(taskId, List.of(ok), t0, t1);

        ScheduledTaskExecutionHistory h = ScheduledTaskExecutionHistory.fromRoutineResult("h-1", result);

        assertThat(h.getId()).isEqualTo("h-1");
        assertThat(h.getTaskId()).isEqualTo(taskId);
        assertThat(h.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.SUCCESS);
        assertThat(h.isSuccess()).isTrue();
        assertThat(h.getCompletedSteps()).isEqualTo(1);
        assertThat(h.getTotalSteps()).isEqualTo(1);
        assertThat(h.getDuration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(h.getErrorMessage()).isEmpty();
    }

    @Test
    void fromRoutineResultMapsPartialFailureToPartialStatus() {
        StepResult ok = StepResult.success(0, step, null, 1, t0, t0.plusMillis(500));
        StepResult fail = StepResult.failure(1, step, "boom", 1, t0.plusMillis(500), t1);
        RoutineResult result = RoutineResult.failure(taskId, List.of(ok, fail), "step 2 failed", t0, t1);

        ScheduledTaskExecutionHistory h = ScheduledTaskExecutionHistory.fromRoutineResult("h-2", result);

        assertThat(h.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.PARTIAL);
        assertThat(h.isSuccess()).isFalse();
        assertThat(h.getCompletedSteps()).isEqualTo(1);
        assertThat(h.getTotalSteps()).isEqualTo(2);
        assertThat(h.getErrorMessage()).contains("step 2 failed");
    }

    /**
     * A cancelled run stops short of its last step, and so does a partial failure — but only one of them says anything
     * about the task. Folding them together would make a task somebody stopped read, in its own history, exactly like
     * a task that is failing intermittently.
     */
    @Test
    void fromRoutineResultMapsCancelledRunToCancelledRatherThanPartial() {
        StepResult ok = StepResult.success(0, step, null, 1, t0, t0.plusMillis(500));
        StepResult stopped = StepResult.failure(1, step, "interrupted", 1, t0.plusMillis(500), t1);
        RoutineResult result = RoutineResult.cancelled(taskId, List.of(ok, stopped), InterruptReason.TASK_CANCELLED, t0,
                t1);

        ScheduledTaskExecutionHistory h = ScheduledTaskExecutionHistory.fromRoutineResult("h-4", result);

        // Had cancellation been read after the step counts, this run would have been filed as PARTIAL.
        assertThat(result.getCompletedStepCount()).isEqualTo(1);
        assertThat(h.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.CANCELLED);
        assertThat(h.isSuccess()).isFalse();
        assertThat(h.getErrorMessage()).hasValueSatisfying(msg -> assertThat(msg).contains("TASK_CANCELLED"));
    }

    @Test
    void fromRoutineResultMapsTotalFailureToFailureStatus() {
        StepResult fail = StepResult.failure(0, step, "boom", 1, t0, t1);
        RoutineResult result = RoutineResult.failure(taskId, List.of(fail), "fail", t0, t1);

        ScheduledTaskExecutionHistory h = ScheduledTaskExecutionHistory.fromRoutineResult("h-3", result);

        assertThat(h.getStatus()).isEqualTo(ScheduledTaskExecutionHistory.Status.FAILURE);
        assertThat(h.getCompletedSteps()).isZero();
        assertThat(h.getTotalSteps()).isEqualTo(1);
    }

    @Test
    void builderRejectsNullRequiredFields() {
        assertThatNullPointerException().isThrownBy(() -> ScheduledTaskExecutionHistory.builder().build());
    }

    @Test
    void equalsAndHashCodeAreIdBased() {
        ScheduledTaskExecutionHistory a = base("h-1").build();
        ScheduledTaskExecutionHistory b = base("h-1").totalSteps(99).build();
        ScheduledTaskExecutionHistory c = base("h-2").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c).isNotEqualTo("not a history");
    }

    @Test
    void toStringContainsKeyFields() {
        ScheduledTaskExecutionHistory h = base("h-99").completedSteps(2).totalSteps(3).build();
        assertThat(h.toString()).contains("h-99").contains("t-1").contains("2/3");
    }

    private ScheduledTaskExecutionHistory.Builder base(String id) {
        return ScheduledTaskExecutionHistory.builder().id(id).taskId(taskId)
                .status(ScheduledTaskExecutionHistory.Status.SUCCESS).startedAt(t0).completedAt(t1);
    }
}
