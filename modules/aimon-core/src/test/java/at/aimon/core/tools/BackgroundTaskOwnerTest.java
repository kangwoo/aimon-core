package at.aimon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.tools.bash.BackgroundBashRecord;

/** {@link BackgroundTaskOwner}: which call owns a background command (EE-58). */
class BackgroundTaskOwnerTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("ops", "acme");
    private static final SessionId SESSION = SessionId.of("session-a");
    private static final ExecutionId EXECUTION = ExecutionId.of("exec-1");

    @Test
    @DisplayName("a session's turn is owned by its session")
    void turnIsOwnedByItsSession() {
        final BackgroundTaskOwner owner = BackgroundTaskOwner.of(ToolContext.builder()
                .put(ToolContextKeys.AGENT_RUNTIME_ID, RUNTIME).put(ToolContextKeys.SESSION_ID, SESSION).build());

        assertThat(owner.getRuntimeId()).contains(RUNTIME);
        assertThat(owner.getSessionId()).contains(SESSION);
        assertThat(owner.getExecutionId()).isEmpty();
    }

    @Test
    @DisplayName("a fork is owned by the session it was spawned for, so it shares an owner with that session's turn")
    void forkIsOwnedByTheInvokingSession() {
        final BackgroundTaskOwner turn = BackgroundTaskOwner.of(ToolContext.builder()
                .put(ToolContextKeys.AGENT_RUNTIME_ID, RUNTIME).put(ToolContextKeys.SESSION_ID, SESSION).build());
        final BackgroundTaskOwner fork = BackgroundTaskOwner.of(ToolContext.builder()
                .put(ToolContextKeys.AGENT_RUNTIME_ID, RUNTIME).put(ToolContextKeys.EXECUTION_ID, EXECUTION)
                .put(ToolContextKeys.INVOKING_SESSION_ID, SESSION).build());

        assertThat(fork).isEqualTo(turn).hasSameHashCodeAs(turn);
        assertThat(fork.getExecutionId()).as("the fork's own id is not part of the owner").isEmpty();
    }

    @Test
    @DisplayName("an execution acting for no session is owned by itself")
    void sessionlessExecutionOwnsItself() {
        final BackgroundTaskOwner routine = BackgroundTaskOwner.of(ToolContext.builder()
                .put(ToolContextKeys.AGENT_RUNTIME_ID, RUNTIME).put(ToolContextKeys.EXECUTION_ID, EXECUTION).build());
        final BackgroundTaskOwner nextFire = BackgroundTaskOwner
                .of(ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, RUNTIME)
                        .put(ToolContextKeys.EXECUTION_ID, ExecutionId.of("exec-2")).build());

        assertThat(routine.getSessionId()).isEmpty();
        assertThat(routine.getExecutionId()).contains(EXECUTION);
        assertThat(routine).isNotEqualTo(nextFire);
    }

    @Test
    @DisplayName("a context with none of the ids is owned by the runtime alone, and an empty one by nobody")
    void unscopedContexts() {
        final BackgroundTaskOwner runtimeOnly = BackgroundTaskOwner
                .of(ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, RUNTIME).build());

        assertThat(runtimeOnly).isEqualTo(BackgroundTaskOwner.of(RUNTIME, null, null));
        assertThat(BackgroundTaskOwner.of(ToolContext.empty())).isEqualTo(BackgroundTaskOwner.none());
        assertThat(runtimeOnly).isNotEqualTo(BackgroundTaskOwner.of(RUNTIME, SESSION, null));
    }

    @Test
    @DisplayName("a context carrying both a session and an execution id resolves to the session, without throwing")
    void sessionWinsOverExecution() {
        final BackgroundTaskOwner both = BackgroundTaskOwner
                .of(ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, RUNTIME)
                        .put(ToolContextKeys.SESSION_ID, SESSION).put(ToolContextKeys.EXECUTION_ID, EXECUTION).build());

        assertThat(both).isEqualTo(BackgroundTaskOwner.of(RUNTIME, SESSION, null));
        assertThat(BackgroundTaskOwner.of(RUNTIME, SESSION, EXECUTION)).isEqualTo(both);
    }

    @Test
    @DisplayName("a record that carries both ids reads as owned by the session")
    void recordWithBothIdsDoesNotThrow() {
        final BackgroundBashRecord record = BackgroundBashRecord.builder().taskId("bash_00000001")
                .ownerRuntimeId(RUNTIME).ownerSessionId(SESSION).ownerExecutionId(EXECUTION).nodeId("node-a")
                .startedAt(Instant.parse("2026-10-04T00:00:00Z")).build();

        assertThat(record.owner()).isEqualTo(BackgroundTaskOwner.of(RUNTIME, SESSION, null));
    }

    @Test
    @DisplayName("a null context is rejected")
    void nullContext() {
        assertThatThrownBy(() -> BackgroundTaskOwner.of((ToolContext) null)).isInstanceOf(NullPointerException.class);
    }
}
