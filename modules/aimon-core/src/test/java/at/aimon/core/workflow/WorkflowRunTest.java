package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

@DisplayName("WorkflowRun — immutable run metadata snapshot")
class WorkflowRunTest {

    private static final RunId RUN_ID = RunId.from("audit", "2026-07-22");
    private static final Instant T0 = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    @DisplayName("pending() builds a PENDING snapshot with the given fields and no endTime")
    void pendingFactory() {
        final Principal owner = Principal.user("alice");
        final AgentRuntimeId ctx = AgentRuntimeId.fromName("ops-agent");

        final WorkflowRun run = WorkflowRun.pending(RUN_ID, "Audit", owner, ctx, T0);

        assertThat(run.getRunId()).isEqualTo(RUN_ID);
        assertThat(run.getScriptName()).isEqualTo("Audit");
        assertThat(run.getState()).isEqualTo(WorkflowRunState.PENDING);
        assertThat(run.getStartTime()).isEqualTo(T0);
        assertThat(run.getEndTime()).isEmpty();
        assertThat(run.getOwner()).contains(owner);
        assertThat(run.getAgentRuntimeId()).contains(ctx);
        assertThat(run.getLastHeartbeat()).isEmpty();
    }

    @Test
    @DisplayName("pending() tolerates null owner/agentRuntimeId (both surface as empty Optionals)")
    void pendingWithNulls() {
        final WorkflowRun run = WorkflowRun.pending(RUN_ID, "Audit", null, null, T0);

        assertThat(run.getOwner()).isEmpty();
        assertThat(run.getAgentRuntimeId()).isEmpty();
    }

    @Test
    @DisplayName("toBuilder derives a transitioned snapshot without mutating the original")
    void toBuilderTransition() {
        final WorkflowRun pending = WorkflowRun.pending(RUN_ID, "Audit", null, null, T0);
        final Instant t1 = T0.plusSeconds(30);

        final WorkflowRun completed = pending.toBuilder().state(WorkflowRunState.COMPLETED).endTime(t1).build();

        assertThat(completed.getState()).isEqualTo(WorkflowRunState.COMPLETED);
        assertThat(completed.getEndTime()).contains(t1);
        assertThat(completed.getRunId()).isEqualTo(RUN_ID);
        // original is unchanged
        assertThat(pending.getState()).isEqualTo(WorkflowRunState.PENDING);
        assertThat(pending.getEndTime()).isEmpty();
    }

    @Test
    @DisplayName("equals/hashCode cover all fields")
    void valueEquality() {
        final WorkflowRun a = WorkflowRun.pending(RUN_ID, "Audit", null, null, T0);
        final WorkflowRun b = WorkflowRun.pending(RUN_ID, "Audit", null, null, T0);
        final WorkflowRun c = a.toBuilder().state(WorkflowRunState.RUNNING).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("required fields (runId, scriptName, state, startTime) are non-null")
    void requiredFields() {
        assertThatThrownBy(
                () -> WorkflowRun.builder().scriptName("s").state(WorkflowRunState.PENDING).startTime(T0).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> WorkflowRun.builder().runId(RUN_ID).state(WorkflowRunState.PENDING).startTime(T0).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WorkflowRun.builder().runId(RUN_ID).scriptName("s").startTime(T0).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> WorkflowRun.builder().runId(RUN_ID).scriptName("s").state(WorkflowRunState.PENDING).build())
                .isInstanceOf(NullPointerException.class);
    }
}
