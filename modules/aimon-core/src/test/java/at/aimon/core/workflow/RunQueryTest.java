package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

@DisplayName("RunQuery — filter over WorkflowRun")
class RunQueryTest {

    private static final Instant T0 = Instant.parse("2026-07-22T00:00:00Z");
    private static final AgentRuntimeId CTX_A = AgentRuntimeId.fromName("agent-a");
    private static final AgentRuntimeId CTX_B = AgentRuntimeId.fromName("agent-b");
    private static final Principal ALICE = Principal.user("alice");

    private static WorkflowRun run(WorkflowRunState state, Principal owner, AgentRuntimeId ctx) {
        return WorkflowRun.pending(RunId.from("s"), "s", owner, ctx, T0).toBuilder().state(state).build();
    }

    @Test
    @DisplayName("all() matches every run")
    void allMatchesEverything() {
        assertThat(RunQuery.all().matches(run(WorkflowRunState.RUNNING, ALICE, CTX_A))).isTrue();
        assertThat(RunQuery.all().matches(run(WorkflowRunState.COMPLETED, null, null))).isTrue();
    }

    @Test
    @DisplayName("byState matches only the given state")
    void byState() {
        final RunQuery q = RunQuery.byState(WorkflowRunState.RUNNING);

        assertThat(q.matches(run(WorkflowRunState.RUNNING, null, null))).isTrue();
        assertThat(q.matches(run(WorkflowRunState.PENDING, null, null))).isFalse();
        assertThat(q.getState()).contains(WorkflowRunState.RUNNING);
    }

    @Test
    @DisplayName("byAgentRuntime matches only the given owning context")
    void byAgentRuntime() {
        final RunQuery q = RunQuery.byAgentRuntime(CTX_A);

        assertThat(q.matches(run(WorkflowRunState.RUNNING, null, CTX_A))).isTrue();
        assertThat(q.matches(run(WorkflowRunState.RUNNING, null, CTX_B))).isFalse();
        assertThat(q.matches(run(WorkflowRunState.RUNNING, null, null))).isFalse();
    }

    @Test
    @DisplayName("builder combines criteria (AND); every present criterion must match")
    void builderAndSemantics() {
        final RunQuery q = RunQuery.builder().state(WorkflowRunState.RUNNING).owner(ALICE).agentRuntimeId(CTX_A)
                .build();

        assertThat(q.matches(run(WorkflowRunState.RUNNING, ALICE, CTX_A))).isTrue();
        assertThat(q.matches(run(WorkflowRunState.RUNNING, ALICE, CTX_B))).isFalse();
        assertThat(q.matches(run(WorkflowRunState.PENDING, ALICE, CTX_A))).isFalse();
        assertThat(q.matches(run(WorkflowRunState.RUNNING, Principal.user("bob"), CTX_A))).isFalse();
    }
}
