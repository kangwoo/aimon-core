package at.aimon.session.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.TaskQuery;

/**
 * Integration tests for {@link PostgresBackgroundTaskStore} against a real Postgres container — the shared metadata
 * backend that lets any instance observe background subagent tasks (design §5.3.2).
 */
@DisplayName("PostgresBackgroundTaskStore integration")
@Tag("docker")
class PostgresBackgroundTaskStoreIntegrationTest {

    private PostgresBackgroundTaskStore store;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        store = new PostgresBackgroundTaskStore(PostgresTestSupport.dataSource());
    }

    private static BackgroundTask pending(String taskId) {
        return BackgroundTask.builder().taskId(taskId).subagentName("explore").description("desc")
                .state(BackgroundTaskState.PENDING).startTime(Instant.parse("2026-07-07T10:00:00Z")).build();
    }

    @Test
    @DisplayName("put then find returns the stored snapshot")
    void putThenFind() {
        final BackgroundTask task = pending("t1");
        store.put(task);

        assertThat(store.find("t1")).contains(task);
    }

    @Test
    @DisplayName("find of an unknown task is empty")
    void findUnknownIsEmpty() {
        assertThat(store.find("nope")).isEmpty();
    }

    @Test
    @DisplayName("put overwrites the snapshot for the same id")
    void putOverwrites() {
        store.put(pending("t1"));
        final BackgroundTask running = pending("t1").toBuilder().state(BackgroundTaskState.RUNNING).build();
        store.put(running);

        assertThat(store.find("t1")).contains(running);
        assertThat(store.list(TaskQuery.all())).extracting(BackgroundTask::getTaskId).containsExactly("t1");
    }

    @Test
    @DisplayName("owner round-trips through the three flattened columns")
    void ownerRoundTrips() {
        final Principal owner = Principal.service("backup-service", "Backup Service");
        final BackgroundTask task = pending("t1").toBuilder().owner(owner).build();
        store.put(task);

        final Optional<Principal> reloaded = store.find("t1").orElseThrow().getOwner();
        assertThat(reloaded).isPresent();
        assertThat(reloaded.orElseThrow().getType()).isEqualTo(Principal.Type.SERVICE);
        assertThat(reloaded.orElseThrow().getId()).isEqualTo("backup-service");
        assertThat(reloaded.orElseThrow().getDisplayName()).isEqualTo("Backup Service");
        assertThat(store.find("t1")).contains(task);
    }

    @Test
    @DisplayName("transition PENDING -> RUNNING keeps endTime empty")
    void transitionToRunning() {
        store.put(pending("t1"));

        final Optional<BackgroundTask> updated = store.transition("t1", BackgroundTaskState.RUNNING);

        assertThat(updated).isPresent();
        assertThat(updated.orElseThrow().getState()).isEqualTo(BackgroundTaskState.RUNNING);
        assertThat(updated.orElseThrow().getEndTime()).isEmpty();
        assertThat(store.find("t1").orElseThrow().getState()).isEqualTo(BackgroundTaskState.RUNNING);
    }

    @Test
    @DisplayName("transition to a terminal state stamps endTime")
    void transitionToTerminalStampsEndTime() {
        store.put(pending("t1"));
        store.transition("t1", BackgroundTaskState.RUNNING);

        final Optional<BackgroundTask> completed = store.transition("t1", BackgroundTaskState.COMPLETED);

        assertThat(completed).isPresent();
        assertThat(completed.orElseThrow().getState()).isEqualTo(BackgroundTaskState.COMPLETED);
        assertThat(completed.orElseThrow().getEndTime()).isPresent();
        assertThat(store.find("t1").orElseThrow().getEndTime()).isPresent();
    }

    @Test
    @DisplayName("transition out of a terminal state is a no-op (idempotent) and returns empty")
    void terminalStateIsIdempotent() {
        store.put(pending("t1"));
        final BackgroundTask killed = store.transition("t1", BackgroundTaskState.KILLED).orElseThrow();

        final Optional<BackgroundTask> secondAttempt = store.transition("t1", BackgroundTaskState.COMPLETED);

        assertThat(secondAttempt).isEmpty();
        assertThat(store.find("t1")).contains(killed);
    }

    @Test
    @DisplayName("transition of an unknown task returns empty and stores nothing")
    void transitionUnknownReturnsEmpty() {
        assertThat(store.transition("ghost", BackgroundTaskState.RUNNING)).isEmpty();
        assertThat(store.find("ghost")).isEmpty();
    }

    @Test
    @DisplayName("heartbeat of a non-terminal task stamps lastHeartbeat and persists it")
    void heartbeatStampsNonTerminal() {
        store.put(pending("t1"));
        store.transition("t1", BackgroundTaskState.RUNNING);
        final Instant at = Instant.parse("2026-07-07T12:00:00Z");

        final Optional<BackgroundTask> updated = store.heartbeat("t1", at);

        assertThat(updated).isPresent();
        assertThat(updated.orElseThrow().getLastHeartbeat()).contains(at);
        assertThat(store.find("t1").orElseThrow().getLastHeartbeat()).contains(at);
        // Heartbeat evolves only the lease; state is untouched.
        assertThat(store.find("t1").orElseThrow().getState()).isEqualTo(BackgroundTaskState.RUNNING);
    }

    @Test
    @DisplayName("heartbeat of a terminal task is a no-op (guarded) and returns empty")
    void heartbeatTerminalIsNoOp() {
        store.put(pending("t1"));
        final BackgroundTask completed = store.transition("t1", BackgroundTaskState.COMPLETED).orElseThrow();

        final Optional<BackgroundTask> result = store.heartbeat("t1", Instant.parse("2026-07-07T12:00:00Z"));

        assertThat(result).isEmpty();
        assertThat(store.find("t1")).contains(completed);
    }

    @Test
    @DisplayName("heartbeat of an unknown task returns empty and stores nothing")
    void heartbeatUnknownReturnsEmpty() {
        assertThat(store.heartbeat("ghost", Instant.now())).isEmpty();
        assertThat(store.find("ghost")).isEmpty();
    }

    @Test
    @DisplayName("list(byState) / list(byAgentRuntime) filter to matching tasks")
    void listFilters() {
        final AgentRuntimeId ctx = AgentRuntimeId.of("agent:a");
        store.put(pending("a").toBuilder().agentRuntimeId(ctx).build());
        store.put(pending("b"));
        store.transition("b", BackgroundTaskState.RUNNING);

        assertThat(store.list(TaskQuery.all())).extracting(BackgroundTask::getTaskId).containsExactlyInAnyOrder("a",
                "b");
        assertThat(store.list(TaskQuery.byState(BackgroundTaskState.RUNNING))).extracting(BackgroundTask::getTaskId)
                .containsExactly("b");
        assertThat(store.list(TaskQuery.byAgentRuntime(ctx))).extracting(BackgroundTask::getTaskId)
                .containsExactly("a");
    }

    @Test
    @DisplayName("owner criterion narrows list results")
    void listByOwner() {
        final Principal alice = Principal.user("alice");
        store.put(pending("a").toBuilder().owner(alice).build());
        store.put(pending("b").toBuilder().owner(Principal.user("bob")).build());

        assertThat(store.list(TaskQuery.builder().owner(alice).build())).extracting(BackgroundTask::getTaskId)
                .containsExactly("a");
    }

    @Test
    @DisplayName("list on an empty store returns an empty list")
    void listEmpty() {
        assertThat(store.list(TaskQuery.all())).isEmpty();
    }

    @Test
    @DisplayName("remove deletes the snapshot and drops it from list")
    void remove() {
        store.put(pending("t1"));
        store.remove("t1");

        assertThat(store.find("t1")).isEmpty();
        assertThat(store.list(TaskQuery.all())).isEmpty();
    }

    @Test
    @DisplayName("two store instances on the same DataSource observe the same task (cross-instance visibility)")
    void crossInstanceVisibility() {
        store.put(pending("shared"));

        final PostgresBackgroundTaskStore nodeB = new PostgresBackgroundTaskStore(PostgresTestSupport.dataSource());
        assertThat(nodeB.find("shared")).isPresent();
        assertThat(nodeB.list(TaskQuery.all())).extracting(BackgroundTask::getTaskId).containsExactly("shared");
    }
}
