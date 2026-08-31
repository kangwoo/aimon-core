package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class StepResultTest {

    private final RoutineStep step = RoutineStep.of("Bash", "{}");
    private final Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    private final Instant t1 = t0.plusSeconds(3);

    @Test
    void successFactoryProducesSuccessfulResultWithStdout() {
        StepResult r = StepResult.success(0, step, "ok", 1, t0, t1);

        assertThat(r.getStepIndex()).isZero();
        assertThat(r.getStep()).isSameAs(step);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getStdout()).contains("ok");
        assertThat(r.getErrorMessage()).isEmpty();
        assertThat(r.getAttemptCount()).isEqualTo(1);
        assertThat(r.getStartedAt()).isEqualTo(t0);
        assertThat(r.getCompletedAt()).isEqualTo(t1);
        assertThat(r.getDuration()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void failureFactoryProducesFailedResultWithErrorMessage() {
        StepResult r = StepResult.failure(2, step, "boom", 3, t0, t1);

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorMessage()).contains("boom");
        assertThat(r.getStdout()).isEmpty();
        assertThat(r.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void getDurationReturnsZeroWhenTimingMissing() {
        StepResult r = StepResult.builder().stepIndex(0).step(step).build();
        assertThat(r.getDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    void builderRejectsNullStep() {
        assertThatNullPointerException().isThrownBy(() -> StepResult.builder().build());
    }

    @Test
    void equalsAndHashCodeUseAllFields() {
        StepResult a = StepResult.success(0, step, "ok", 1, t0, t1);
        StepResult b = StepResult.success(0, step, "ok", 1, t0, t1);
        StepResult differentIndex = StepResult.success(1, step, "ok", 1, t0, t1);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(differentIndex).isNotEqualTo("string");
    }

    @Test
    void toStringIncludesIndexAndTool() {
        StepResult r = StepResult.success(5, step, null, 1, t0, t1);
        assertThat(r.toString()).contains("stepIndex=5").contains("Bash").contains("success=true");
    }
}
