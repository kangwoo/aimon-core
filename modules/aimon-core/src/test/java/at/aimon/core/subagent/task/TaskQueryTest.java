package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

@DisplayName("TaskQuery — immutable filter over BackgroundTask records")
class TaskQueryTest {

    private static BackgroundTask task(BackgroundTaskState state, Principal owner, AgentRuntimeId ctx) {
        return BackgroundTask.builder().taskId("t1").subagentName("explore").state(state).startTime(Instant.now())
                .owner(owner).agentRuntimeId(ctx).build();
    }

    @Test
    @DisplayName("all() matches every task")
    void allMatchesEverything() {
        TaskQuery query = TaskQuery.all();

        assertThat(query.matches(task(BackgroundTaskState.PENDING, null, null))).isTrue();
        assertThat(query.matches(task(BackgroundTaskState.KILLED, Principal.user("x"), null))).isTrue();
        assertThat(query.getState()).isEmpty();
        assertThat(query.getOwner()).isEmpty();
        assertThat(query.getAgentRuntimeId()).isEmpty();
    }

    @Test
    @DisplayName("byState matches only the given state")
    void byStateMatchesState() {
        TaskQuery query = TaskQuery.byState(BackgroundTaskState.RUNNING);

        assertThat(query.matches(task(BackgroundTaskState.RUNNING, null, null))).isTrue();
        assertThat(query.matches(task(BackgroundTaskState.PENDING, null, null))).isFalse();
        assertThat(query.getState()).contains(BackgroundTaskState.RUNNING);
    }

    @Test
    @DisplayName("byAgentRuntime matches only the owning context")
    void byContextMatchesContext() {
        AgentRuntimeId ctxA = AgentRuntimeId.of("agent:a");
        AgentRuntimeId ctxB = AgentRuntimeId.of("agent:b");
        TaskQuery query = TaskQuery.byAgentRuntime(ctxA);

        assertThat(query.matches(task(BackgroundTaskState.RUNNING, null, ctxA))).isTrue();
        assertThat(query.matches(task(BackgroundTaskState.RUNNING, null, ctxB))).isFalse();
        assertThat(query.matches(task(BackgroundTaskState.RUNNING, null, null))).isFalse();
    }

    @Test
    @DisplayName("owner criterion matches only the given principal")
    void byOwnerMatchesOwner() {
        Principal alice = Principal.user("alice");
        TaskQuery query = TaskQuery.builder().owner(alice).build();

        assertThat(query.matches(task(BackgroundTaskState.RUNNING, alice, null))).isTrue();
        assertThat(query.matches(task(BackgroundTaskState.RUNNING, Principal.user("bob"), null))).isFalse();
        assertThat(query.matches(task(BackgroundTaskState.RUNNING, null, null))).isFalse();
    }

    @Test
    @DisplayName("builder combines criteria — all must match (AND semantics)")
    void builderCombinesCriteria() {
        AgentRuntimeId ctx = AgentRuntimeId.of("agent:a");
        Principal alice = Principal.user("alice");
        TaskQuery query = TaskQuery.builder().state(BackgroundTaskState.RUNNING).owner(alice).agentRuntimeId(ctx)
                .build();

        assertThat(query.matches(task(BackgroundTaskState.RUNNING, alice, ctx))).isTrue();
        // Any single mismatch fails the whole query.
        assertThat(query.matches(task(BackgroundTaskState.PENDING, alice, ctx))).isFalse();
        assertThat(query.matches(task(BackgroundTaskState.RUNNING, Principal.user("bob"), ctx))).isFalse();
        assertThat(query.matches(task(BackgroundTaskState.RUNNING, alice, AgentRuntimeId.of("agent:z")))).isFalse();
    }

    @Test
    @DisplayName("factory and matches reject null arguments")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> TaskQuery.byState(null));
        assertThatNullPointerException().isThrownBy(() -> TaskQuery.byAgentRuntime(null));
        assertThatNullPointerException().isThrownBy(() -> TaskQuery.all().matches(null));
    }
}
