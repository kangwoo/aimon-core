package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RoutineStepTest {

    @Test
    void simpleFactoryAppliesDefaultRetryAndTimeout() {
        RoutineStep step = RoutineStep.of("Bash", "{\"command\":\"ls\"}");

        assertThat(step.getTool()).isEqualTo("Bash");
        assertThat(step.getToolParams()).isEqualTo("{\"command\":\"ls\"}");
        assertThat(step.getMaxRetries()).isEqualTo(RoutineStep.DEFAULT_MAX_RETRIES);
        assertThat(step.getRetryDelay()).isEqualTo(RoutineStep.DEFAULT_RETRY_DELAY);
        assertThat(step.getTimeout()).isEqualTo(RoutineStep.DEFAULT_TIMEOUT);
        assertThat(step.getId()).isNull();
    }

    @Test
    void withRetryFactoryOverridesRetryConfig() {
        RoutineStep step = RoutineStep.withRetry("Bash", "{}", 7, Duration.ofSeconds(30));

        assertThat(step.getMaxRetries()).isEqualTo(7);
        assertThat(step.getRetryDelay()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void builderAcceptsAllFields() {
        RoutineStep step = RoutineStep.builder().id("step-1").tool("Read").toolParams("{\"path\":\"/etc\"}")
                .maxRetries(2).retryDelay(Duration.ofMillis(100)).timeout(Duration.ofSeconds(45)).build();

        assertThat(step.getId()).isEqualTo("step-1");
        assertThat(step.getTool()).isEqualTo("Read");
        assertThat(step.getMaxRetries()).isEqualTo(2);
        assertThat(step.getRetryDelay()).isEqualTo(Duration.ofMillis(100));
        assertThat(step.getTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void timeoutMsBuilderAcceptsMillisAndRejectsNegative() {
        RoutineStep step = RoutineStep.builder().tool("Read").timeoutMs(2_500).build();
        assertThat(step.getTimeout()).isEqualTo(Duration.ofMillis(2_500));

        assertThatIllegalArgumentException().isThrownBy(() -> RoutineStep.builder().tool("Read").timeoutMs(-1).build());
    }

    @Test
    void builderTreatsNullToolParamsAsEmptyString() {
        RoutineStep step = RoutineStep.builder().tool("Bash").toolParams(null).build();
        assertThat(step.getToolParams()).isEmpty();
    }

    @Test
    void builderRejectsNullTool() {
        assertThatNullPointerException().isThrownBy(() -> RoutineStep.builder().tool(null));
    }

    @Test
    void builderRejectsNegativeMaxRetries() {
        assertThatIllegalArgumentException().isThrownBy(() -> RoutineStep.builder().tool("Bash").maxRetries(-1));
    }

    @Test
    void builderRejectsNullRetryDelayAndTimeout() {
        assertThatNullPointerException().isThrownBy(() -> RoutineStep.builder().tool("Bash").retryDelay(null));
        assertThatNullPointerException().isThrownBy(() -> RoutineStep.builder().tool("Bash").timeout(null));
    }

    @Test
    void buildRequiresTool() {
        assertThatNullPointerException().isThrownBy(() -> RoutineStep.builder().build());
    }

    @Test
    void equalsAndHashCodeAreFieldByField() {
        RoutineStep a = RoutineStep.of("Bash", "{}");
        RoutineStep b = RoutineStep.of("Bash", "{}");
        RoutineStep different = RoutineStep.of("Read", "{}");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(different).isNotEqualTo("not a step");
    }

    @Test
    void toStringIncludesToolAndConfig() {
        RoutineStep step = RoutineStep.builder().id("s1").tool("Bash").toolParams("{}").build();
        assertThat(step.toString()).contains("s1").contains("Bash").contains("maxRetries=").contains("timeout=");
    }
}
