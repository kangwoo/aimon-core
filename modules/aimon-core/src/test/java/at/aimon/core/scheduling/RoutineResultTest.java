package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class RoutineResultTest {

    private final ScheduledTaskId taskId = ScheduledTaskId.of("task-x");
    private final RoutineStep step = RoutineStep.of("Bash", "{}");
    private final Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    private final Instant t1 = t0.plusSeconds(10);
    private final StepResult ok = StepResult.success(0, step, null, 1, t0, t0.plusSeconds(1));
    private final StepResult fail = StepResult.failure(1, step, "x", 1, t0, t0.plusSeconds(2));

    @Test
    void successFactoryReportsSuccessAndAggregatesStepCounts() {
        RoutineResult r = RoutineResult.success(taskId, List.of(ok, ok), t0, t1);

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getTaskId()).isEqualTo(taskId);
        assertThat(r.getStepResults()).hasSize(2);
        assertThat(r.getCompletedStepCount()).isEqualTo(2);
        assertThat(r.getTotalStepCount()).isEqualTo(2);
        assertThat(r.getDuration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(r.getErrorMessage()).isEmpty();
    }

    @Test
    void failureFactoryReportsFailureAndCountsOnlySuccessfulSteps() {
        RoutineResult r = RoutineResult.failure(taskId, List.of(ok, fail), "stop", t0, t1);

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getCompletedStepCount()).isEqualTo(1);
        assertThat(r.getTotalStepCount()).isEqualTo(2);
        assertThat(r.getErrorMessage()).contains("stop");
    }

    @Test
    void durationReturnsZeroWhenTimingMissing() {
        RoutineResult r = RoutineResult.builder().taskId(taskId).build();
        assertThat(r.getDuration()).isEqualTo(Duration.ZERO);
        assertThat(r.getStepResults()).isEmpty();
    }

    @Test
    void builderAddStepResultAppends() {
        RoutineResult r = RoutineResult.builder().taskId(taskId).addStepResult(ok).addStepResult(fail).build();
        assertThat(r.getStepResults()).containsExactly(ok, fail);
    }

    @Test
    void builderRejectsNullTaskId() {
        assertThatNullPointerException().isThrownBy(() -> RoutineResult.builder().build());
    }

    @Test
    void builderTreatsNullStepResultsAsEmpty() {
        RoutineResult r = RoutineResult.builder().taskId(taskId).stepResults(null).build();
        assertThat(r.getStepResults()).isEmpty();
    }

    @Test
    void equalsAndHashCodeUseAllFields() {
        RoutineResult a = RoutineResult.success(taskId, List.of(ok), t0, t1);
        RoutineResult b = RoutineResult.success(taskId, List.of(ok), t0, t1);
        RoutineResult diff = RoutineResult.failure(taskId, List.of(fail), "x", t0, t1);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(diff).isNotEqualTo("string");
    }

    @Test
    void toStringIncludesTaskIdAndProgress() {
        RoutineResult r = RoutineResult.success(taskId, List.of(ok), t0, t1);
        assertThat(r.toString()).contains("task-x").contains("steps=1/1").contains("success=true");
    }
}
