package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.TaskQuery;

/**
 * Integration tests for {@link MongoBackgroundTaskStore} against a real MongoDB replica-set container — the shared
 * metadata backend that lets any instance observe background subagent tasks (design §5.3.2 ①). Mirrors the Redis-backed
 * store's coverage.
 */
@DisplayName("MongoBackgroundTaskStore integration")
@Tag("docker")
class MongoBackgroundTaskStoreIntegrationTest {

    private MongoBackgroundTaskStore store;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        store = new MongoBackgroundTaskStore(MongoTestSupport.sharedDatabase());
    }

    private static BackgroundTask pending(String taskId) {
        // BSON stores instants as Date (millisecond granularity), so truncate here to keep whole-snapshot equality
        // assertions honest across the encode → Date → decode round-trip.
        return BackgroundTask.builder().taskId(taskId).subagentName("explore").description("desc")
                .state(BackgroundTaskState.PENDING).startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS)).build();
    }

    @Test
    @DisplayName("put then find returns the stored snapshot")
    void putThenFind() {
        BackgroundTask task = pending("t1");
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
        BackgroundTask running = pending("t1").toBuilder().state(BackgroundTaskState.RUNNING).build();
        store.put(running);

        assertThat(store.find("t1")).contains(running);
        assertThat(store.list(TaskQuery.all())).extracting(BackgroundTask::getTaskId).containsExactly("t1");
    }

    @Test
    @DisplayName("transition PENDING -> RUNNING keeps endTime empty")
    void transitionToRunning() {
        store.put(pending("t1"));

        Optional<BackgroundTask> updated = store.transition("t1", BackgroundTaskState.RUNNING);

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

        Optional<BackgroundTask> completed = store.transition("t1", BackgroundTaskState.COMPLETED);

        assertThat(completed).isPresent();
        assertThat(completed.orElseThrow().getState()).isEqualTo(BackgroundTaskState.COMPLETED);
        assertThat(completed.orElseThrow().getEndTime()).isPresent();
    }

    @Test
    @DisplayName("transition out of a terminal state is a no-op (idempotent) and returns empty")
    void terminalStateIsIdempotent() {
        store.put(pending("t1"));
        BackgroundTask killed = store.transition("t1", BackgroundTaskState.KILLED).orElseThrow();

        Optional<BackgroundTask> secondAttempt = store.transition("t1", BackgroundTaskState.COMPLETED);

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
        Instant at = Instant.parse("2026-07-07T12:00:00Z");

        Optional<BackgroundTask> updated = store.heartbeat("t1", at);

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
        BackgroundTask completed = store.transition("t1", BackgroundTaskState.COMPLETED).orElseThrow();

        Optional<BackgroundTask> result = store.heartbeat("t1", Instant.parse("2026-07-07T12:00:00Z"));

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
        AgentRuntimeId ctx = AgentRuntimeId.of("agent:a");
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
        Principal alice = Principal.user("alice");
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
    @DisplayName("remove deletes the snapshot and drops it from listings")
    void remove() {
        store.put(pending("t1"));
        store.remove("t1");

        assertThat(store.find("t1")).isEmpty();
        assertThat(store.list(TaskQuery.all())).isEmpty();
    }

    @Test
    @DisplayName("two store instances on separate clients observe the same task (cross-instance visibility)")
    void crossInstanceVisibility() {
        store.put(pending("shared"));

        try (MongoClient other = MongoTestSupport.newClient()) {
            MongoBackgroundTaskStore nodeB = new MongoBackgroundTaskStore(
                    other.getDatabase(MongoTestSupport.DATABASE_NAME));
            assertThat(nodeB.find("shared")).isPresent();
            assertThat(nodeB.list(TaskQuery.all())).extracting(BackgroundTask::getTaskId).containsExactly("shared");
        }
    }
}
