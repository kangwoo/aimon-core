package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

@DisplayName("BackgroundTask — immutable metadata snapshot")
class BackgroundTaskTest {

    private static BackgroundTask.Builder base() {
        return BackgroundTask.builder().taskId("t1").subagentName("explore").state(BackgroundTaskState.PENDING)
                .startTime(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("builder populates fields; description defaults to empty; endTime empty by default")
    void builderPopulatesFields() {
        BackgroundTask task = base().build();

        assertThat(task.getTaskId()).isEqualTo("t1");
        assertThat(task.getSubagentName()).isEqualTo("explore");
        assertThat(task.getDescription()).isEmpty();
        assertThat(task.getState()).isEqualTo(BackgroundTaskState.PENDING);
        assertThat(task.getEndTime()).isEmpty();
        assertThat(task.getOutputOffset()).isZero();
        assertThat(task.getOwner()).isEmpty();
        assertThat(task.getAgentRuntimeId()).isEmpty();
    }

    @Test
    @DisplayName("all optional fields round-trip through the builder")
    void optionalFieldsRoundTrip() {
        Principal owner = Principal.user("alice");
        AgentRuntimeId ctx = AgentRuntimeId.of("agent:a");
        Instant end = Instant.parse("2026-01-01T00:05:00Z");

        BackgroundTask task = base().description("do it").endTime(end).outputOffset(42L).owner(owner)
                .agentRuntimeId(ctx).build();

        assertThat(task.getDescription()).isEqualTo("do it");
        assertThat(task.getEndTime()).contains(end);
        assertThat(task.getOutputOffset()).isEqualTo(42L);
        assertThat(task.getOwner()).contains(owner);
        assertThat(task.getAgentRuntimeId()).contains(ctx);
    }

    @Test
    @DisplayName("toBuilder derives an evolved snapshot preserving other fields")
    void toBuilderPreservesFields() {
        Instant end = Instant.parse("2026-01-01T00:05:00Z");
        BackgroundTask original = base().description("do it").owner(Principal.user("alice")).build();

        BackgroundTask evolved = original.toBuilder().state(BackgroundTaskState.COMPLETED).endTime(end).build();

        assertThat(evolved.getState()).isEqualTo(BackgroundTaskState.COMPLETED);
        assertThat(evolved.getEndTime()).contains(end);
        // Unchanged fields carried over.
        assertThat(evolved.getTaskId()).isEqualTo("t1");
        assertThat(evolved.getSubagentName()).isEqualTo("explore");
        assertThat(evolved.getDescription()).isEqualTo("do it");
        assertThat(evolved.getOwner()).contains(Principal.user("alice"));
        // Original is untouched (immutability).
        assertThat(original.getState()).isEqualTo(BackgroundTaskState.PENDING);
        assertThat(original.getEndTime()).isEmpty();
    }

    @Test
    @DisplayName("equals / hashCode reflect all fields")
    void equalsAndHashCode() {
        BackgroundTask a = base().build();
        BackgroundTask b = base().build();
        BackgroundTask different = base().state(BackgroundTaskState.RUNNING).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }

    @Test
    @DisplayName("required fields are enforced at build")
    void requiredFieldsEnforced() {
        assertThatNullPointerException().isThrownBy(() -> BackgroundTask.builder().subagentName("x")
                .state(BackgroundTaskState.PENDING).startTime(Instant.now()).build());
        assertThatNullPointerException().isThrownBy(() -> BackgroundTask.builder().taskId("t")
                .state(BackgroundTaskState.PENDING).startTime(Instant.now()).build());
        assertThatNullPointerException().isThrownBy(
                () -> BackgroundTask.builder().taskId("t").subagentName("x").startTime(Instant.now()).build());
        assertThatNullPointerException().isThrownBy(() -> BackgroundTask.builder().taskId("t").subagentName("x")
                .state(BackgroundTaskState.PENDING).build());
    }
}
