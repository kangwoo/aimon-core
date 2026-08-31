package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentDefinitionVersion;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

class ScheduledTaskTest {

    private final ScheduledTaskId id = ScheduledTaskId.of("task-1");
    private final RoutineStep step = RoutineStep.of("Bash", "{}");
    private final Principal owner = Principal.user("user-1");
    private final AgentRuntimeId ctx = AgentRuntimeId.fromName("orca");

    @Test
    void builderProducesTaskWithDefaults() {
        ScheduledTask task = baseBuilder().build();

        assertThat(task.getId()).isEqualTo(id);
        assertThat(task.getName()).isEqualTo("daily");
        assertThat(task.getDescription()).isEmpty();
        assertThat(task.getCronExpression()).isEqualTo("0 0 * * *");
        assertThat(task.getTimezone()).isNull();
        assertThat(task.getRoutine()).containsExactly(step);
        assertThat(task.getOwner()).isEqualTo(owner);
        assertThat(task.getBoundRuntimeId()).isEqualTo(ctx);
        assertThat(task.isEnabled()).isTrue();
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getLastExecutedAt()).isEmpty();
    }

    @Test
    void builderAcceptsAllOptionalFields() {
        Instant created = Instant.parse("2024-01-01T00:00:00Z");
        Instant ran = Instant.parse("2024-01-02T00:00:00Z");

        ScheduledTask task = baseBuilder().description("desc").timezone("Asia/Seoul").enabled(false).createdAt(created)
                .lastExecutedAt(ran).build();

        assertThat(task.getDescription()).contains("desc");
        assertThat(task.getTimezone()).isEqualTo("Asia/Seoul");
        assertThat(task.isEnabled()).isFalse();
        assertThat(task.getCreatedAt()).isEqualTo(created);
        assertThat(task.getLastExecutedAt()).contains(ran);
    }

    @Test
    void addStepAccumulatesRoutineSteps() {
        RoutineStep s2 = RoutineStep.of("Read", "{}");
        ScheduledTask task = ScheduledTask.builder().id(id).name("n").cronExpression("* * * * *").owner(owner)
                .boundRuntimeId(ctx).addStep(step).addStep(s2).build();

        assertThat(task.getRoutine()).containsExactly(step, s2);
    }

    @Test
    void routineNullSetterDefaultsToEmptyAndThenFails() {
        // Setting null re-creates an empty list; build() then complains because routine must be non-empty.
        assertThatIllegalArgumentException().isThrownBy(() -> ScheduledTask.builder().id(id).name("n")
                .cronExpression("* * * * *").owner(owner).boundRuntimeId(ctx).routine(null).build());
    }

    @Test
    void buildRequiresAtLeastOneStep() {
        assertThatIllegalArgumentException().isThrownBy(() -> ScheduledTask.builder().id(id).name("n")
                .cronExpression("* * * * *").owner(owner).boundRuntimeId(ctx).build());
    }

    @Test
    void buildRejectsNullRequiredFields() {
        assertThatNullPointerException().isThrownBy(() -> ScheduledTask.builder().name("n").cronExpression("c")
                .owner(owner).boundRuntimeId(ctx).addStep(step).build());
        assertThatNullPointerException().isThrownBy(() -> ScheduledTask.builder().id(id).cronExpression("c")
                .owner(owner).boundRuntimeId(ctx).addStep(step).build());
    }

    @Test
    void withEnabledReturnsCopyAndPreservesOtherFields() {
        ScheduledTask task = baseBuilder().description("d").build();
        ScheduledTask disabled = task.withEnabled(false);

        assertThat(disabled).isNotSameAs(task);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.getDescription()).contains("d");
        assertThat(disabled.getId()).isEqualTo(task.getId());
        assertThat(task.isEnabled()).isTrue();
    }

    @Test
    void withLastExecutedAtUpdatesTimestamp() {
        ScheduledTask task = baseBuilder().build();
        Instant fired = Instant.parse("2025-06-15T12:00:00Z");

        ScheduledTask updated = task.withLastExecutedAt(fired);

        assertThat(updated.getLastExecutedAt()).contains(fired);
        assertThat(task.getLastExecutedAt()).isEmpty();
    }

    @Test
    void agentDefinitionVersionIsAbsentUnlessRecorded() {
        assertThat(baseBuilder().build().getAgentDefinitionVersion()).isEmpty();
    }

    @Test
    void agentDefinitionVersionSurvivesBothWithers() {
        // withLastExecutedAt runs after every fire, so a field dropped by the copy would look like it was never
        // recorded rather than like a bug.
        AgentDefinitionVersion version = AgentDefinitionVersion.of("0123456789abcdef");
        ScheduledTask task = baseBuilder().agentDefinitionVersion(version).build();

        assertThat(task.getAgentDefinitionVersion()).contains(version);
        assertThat(task.withLastExecutedAt(Instant.parse("2025-06-15T12:00:00Z")).getAgentDefinitionVersion())
                .contains(version);
        assertThat(task.withEnabled(false).getAgentDefinitionVersion()).contains(version);
    }

    @Test
    void withersCarryEveryOtherFieldToo() {
        Instant created = Instant.parse("2024-01-01T00:00:00Z");
        Instant ran = Instant.parse("2024-01-02T00:00:00Z");
        ScheduledTask task = baseBuilder().description("desc").timezone("Asia/Seoul").createdAt(created)
                .lastExecutedAt(ran).build();

        ScheduledTask copy = task.withEnabled(false);

        assertThat(copy.getId()).isEqualTo(id);
        assertThat(copy.getName()).isEqualTo("daily");
        assertThat(copy.getDescription()).contains("desc");
        assertThat(copy.getCronExpression()).isEqualTo("0 0 * * *");
        assertThat(copy.getTimezone()).isEqualTo("Asia/Seoul");
        assertThat(copy.getRoutine()).containsExactly(step);
        assertThat(copy.getOwner()).isEqualTo(owner);
        assertThat(copy.getBoundRuntimeId()).isEqualTo(ctx);
        assertThat(copy.getCreatedAt()).isEqualTo(created);
        assertThat(copy.getLastExecutedAt()).contains(ran);
    }

    @Test
    void equalsAndHashCodeAreIdBased() {
        ScheduledTask a = baseBuilder().build();
        ScheduledTask b = baseBuilder().name("different").build();
        ScheduledTask c = baseBuilder().id(ScheduledTaskId.of("other")).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c).isNotEqualTo("string");
    }

    @Test
    void toStringIncludesIdNameCronAndOwner() {
        ScheduledTask task = baseBuilder().build();
        assertThat(task.toString()).contains("task-1").contains("daily").contains("0 0 * * *").contains("enabled=true");
    }

    private ScheduledTask.Builder baseBuilder() {
        return ScheduledTask.builder().id(id).name("daily").cronExpression("0 0 * * *").owner(owner).boundRuntimeId(ctx)
                .addStep(step);
    }
}
