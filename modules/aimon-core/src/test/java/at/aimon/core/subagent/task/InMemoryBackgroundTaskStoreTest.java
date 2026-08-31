package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;

@DisplayName("InMemoryBackgroundTaskStore — default node-local store for the multi-instance seam")
class InMemoryBackgroundTaskStoreTest {

    private InMemoryBackgroundTaskStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryBackgroundTaskStore();
    }

    private static BackgroundTask pending(String taskId) {
        return BackgroundTask.builder().taskId(taskId).subagentName("explore").description("desc")
                .state(BackgroundTaskState.PENDING).startTime(Instant.now()).build();
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
    @DisplayName("put overwrites an existing snapshot for the same id")
    void putOverwrites() {
        store.put(pending("t1"));
        BackgroundTask running = pending("t1").toBuilder().state(BackgroundTaskState.RUNNING).build();
        store.put(running);

        assertThat(store.find("t1")).contains(running);
    }

    @Test
    @DisplayName("transition PENDING -> RUNNING keeps endTime empty and returns the updated snapshot")
    void transitionToRunning() {
        store.put(pending("t1"));

        Optional<BackgroundTask> updated = store.transition("t1", BackgroundTaskState.RUNNING);

        assertThat(updated).isPresent();
        assertThat(updated.get().getState()).isEqualTo(BackgroundTaskState.RUNNING);
        assertThat(updated.get().getEndTime()).isEmpty();
        assertThat(store.find("t1").orElseThrow().getState()).isEqualTo(BackgroundTaskState.RUNNING);
    }

    @Test
    @DisplayName("transition to a terminal state stamps endTime")
    void transitionToTerminalStampsEndTime() {
        store.put(pending("t1"));
        store.transition("t1", BackgroundTaskState.RUNNING);

        Optional<BackgroundTask> completed = store.transition("t1", BackgroundTaskState.COMPLETED);

        assertThat(completed).isPresent();
        assertThat(completed.get().getState()).isEqualTo(BackgroundTaskState.COMPLETED);
        assertThat(completed.get().getEndTime()).isPresent();
    }

    @Test
    @DisplayName("transition out of a terminal state is a no-op (idempotent) and returns empty")
    void terminalStateIsIdempotent() {
        store.put(pending("t1"));
        store.transition("t1", BackgroundTaskState.RUNNING);
        BackgroundTask killed = store.transition("t1", BackgroundTaskState.KILLED).orElseThrow();

        Optional<BackgroundTask> secondAttempt = store.transition("t1", BackgroundTaskState.COMPLETED);

        assertThat(secondAttempt).isEmpty();
        assertThat(store.find("t1")).contains(killed);
        assertThat(store.find("t1").orElseThrow().getState()).isEqualTo(BackgroundTaskState.KILLED);
    }

    @Test
    @DisplayName("transition of an unknown task returns empty and adds nothing")
    void transitionUnknownReturnsEmpty() {
        assertThat(store.transition("ghost", BackgroundTaskState.RUNNING)).isEmpty();
        assertThat(store.find("ghost")).isEmpty();
    }

    @Test
    @DisplayName("PENDING may go straight to KILLED (stopped before it ran)")
    void pendingDirectlyToKilled() {
        store.put(pending("t1"));

        Optional<BackgroundTask> killed = store.transition("t1", BackgroundTaskState.KILLED);

        assertThat(killed).isPresent();
        assertThat(killed.get().getState()).isEqualTo(BackgroundTaskState.KILLED);
        assertThat(killed.get().getEndTime()).isPresent();
    }

    @Test
    @DisplayName("heartbeat of a non-terminal task stamps lastHeartbeat and returns the updated snapshot")
    void heartbeatStampsNonTerminal() {
        store.put(pending("t1"));
        store.transition("t1", BackgroundTaskState.RUNNING);
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        Optional<BackgroundTask> updated = store.heartbeat("t1", at);

        assertThat(updated).isPresent();
        assertThat(updated.get().getLastHeartbeat()).contains(at);
        assertThat(store.find("t1").orElseThrow().getLastHeartbeat()).contains(at);
    }

    @Test
    @DisplayName("heartbeat of a terminal task is a no-op (guarded) and returns empty")
    void heartbeatTerminalIsNoOp() {
        store.put(pending("t1"));
        BackgroundTask completed = store.transition("t1", BackgroundTaskState.COMPLETED).orElseThrow();

        Optional<BackgroundTask> result = store.heartbeat("t1", Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(result).isEmpty();
        assertThat(store.find("t1")).contains(completed);
    }

    @Test
    @DisplayName("heartbeat of an unknown task returns empty and adds nothing")
    void heartbeatUnknownReturnsEmpty() {
        assertThat(store.heartbeat("ghost", Instant.now())).isEmpty();
        assertThat(store.find("ghost")).isEmpty();
    }

    @Test
    @DisplayName("heartbeat rejects null arguments")
    void heartbeatRejectsNulls() {
        store.put(pending("t1"));
        assertThatNullPointerException().isThrownBy(() -> store.heartbeat(null, Instant.now()));
        assertThatNullPointerException().isThrownBy(() -> store.heartbeat("t1", null));
    }

    @Test
    @DisplayName("list(all) returns every stored task")
    void listAll() {
        store.put(pending("a"));
        store.put(pending("b"));

        List<BackgroundTask> all = store.list(TaskQuery.all());

        assertThat(all).extracting(BackgroundTask::getTaskId).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("list(byState) filters to matching tasks only")
    void listByState() {
        store.put(pending("a"));
        store.put(pending("b"));
        store.transition("b", BackgroundTaskState.RUNNING);

        assertThat(store.list(TaskQuery.byState(BackgroundTaskState.RUNNING))).extracting(BackgroundTask::getTaskId)
                .containsExactly("b");
        assertThat(store.list(TaskQuery.byState(BackgroundTaskState.PENDING))).extracting(BackgroundTask::getTaskId)
                .containsExactly("a");
    }

    @Test
    @DisplayName("list(byAgentRuntime) filters to the owning agent runtime")
    void listByAgentRuntime() {
        AgentRuntimeId ctxA = AgentRuntimeId.of("agent:a");
        AgentRuntimeId ctxB = AgentRuntimeId.of("agent:b");
        store.put(pending("a").toBuilder().agentRuntimeId(ctxA).build());
        store.put(pending("b").toBuilder().agentRuntimeId(ctxB).build());

        assertThat(store.list(TaskQuery.byAgentRuntime(ctxA))).extracting(BackgroundTask::getTaskId)
                .containsExactly("a");
    }

    @Test
    @DisplayName("list on an empty store returns an empty list")
    void listEmpty() {
        assertThat(store.list(TaskQuery.all())).isEmpty();
    }

    @Test
    @DisplayName("remove deletes the snapshot")
    void remove() {
        store.put(pending("t1"));
        store.remove("t1");

        assertThat(store.find("t1")).isEmpty();
        assertThat(store.list(TaskQuery.all())).isEmpty();
    }

    @Test
    @DisplayName("remove of an unknown task is a harmless no-op")
    void removeUnknownIsNoOp() {
        store.remove("ghost");
        assertThat(store.list(TaskQuery.all())).isEmpty();
    }

    @Test
    @DisplayName("null arguments are rejected")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> store.put(null));
        assertThatNullPointerException().isThrownBy(() -> store.find(null));
        assertThatNullPointerException().isThrownBy(() -> store.list(null));
        assertThatNullPointerException().isThrownBy(() -> store.transition(null, BackgroundTaskState.RUNNING));
        assertThatNullPointerException().isThrownBy(() -> store.transition("t1", null));
        assertThatNullPointerException().isThrownBy(() -> store.remove(null));
    }

    @Test
    @DisplayName("owner criterion narrows list results")
    void listByOwner() {
        Principal alice = Principal.user("alice");
        Principal bob = Principal.user("bob");
        store.put(pending("a").toBuilder().owner(alice).build());
        store.put(pending("b").toBuilder().owner(bob).build());

        List<BackgroundTask> aliceTasks = store.list(TaskQuery.builder().owner(alice).build());

        assertThat(aliceTasks).extracting(BackgroundTask::getTaskId).containsExactly("a");
    }

    private static BackgroundTask completed(String taskId, Instant endTime) {
        return BackgroundTask.builder().taskId(taskId).subagentName("explore").description("desc")
                .state(BackgroundTaskState.COMPLETED).startTime(endTime.minusSeconds(1)).endTime(endTime).build();
    }

    @Test
    @DisplayName("evicts the oldest terminal task once the terminal cap is exceeded")
    void evictsOldestTerminalOverCap() {
        InMemoryBackgroundTaskStore capped = new InMemoryBackgroundTaskStore(2);
        Instant base = Instant.now();
        capped.put(completed("old", base));
        capped.put(completed("mid", base.plusSeconds(1)));
        capped.put(completed("new", base.plusSeconds(2)));

        // Cap is 2 → the oldest (by end time) terminal task is evicted.
        assertThat(capped.find("old")).isEmpty();
        assertThat(capped.find("mid")).isPresent();
        assertThat(capped.find("new")).isPresent();
    }

    @Test
    @DisplayName("never evicts in-flight (non-terminal) tasks even beyond the cap")
    void neverEvictsInFlightTasks() {
        InMemoryBackgroundTaskStore capped = new InMemoryBackgroundTaskStore(1);
        capped.put(pending("running-1"));
        capped.put(pending("running-2"));
        capped.put(completed("done-1", Instant.now()));
        capped.put(completed("done-2", Instant.now().plusSeconds(1)));

        // In-flight tasks are retained regardless of the terminal cap.
        assertThat(capped.find("running-1")).isPresent();
        assertThat(capped.find("running-2")).isPresent();
        // Terminal cap of 1 enforced: only the newest terminal task remains.
        assertThat(capped.find("done-1")).isEmpty();
        assertThat(capped.find("done-2")).isPresent();
    }

    @Test
    @DisplayName("rejects a non-positive terminal cap")
    void rejectsNonPositiveCap() {
        assertThatThrownBy(() -> new InMemoryBackgroundTaskStore(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
