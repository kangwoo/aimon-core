package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.InMemoryBackgroundTaskStore;

/**
 * Regression coverage for {@link DefaultSubagentExecutionManager#resolveNotifiedState} — the terminal state a finished
 * background task reports to its launching agent must prefer the store's authoritative record over the locally-computed
 * state, and must never claim {@code COMPLETED} for a task the {@link at.aimon.core.subagent.task.ZombieTaskReaper}
 * flipped to {@code FAILED} and that was then evicted under terminal-retention overflow.
 */
@DisplayName("DefaultSubagentExecutionManager.resolveNotifiedState — authoritative terminal state")
class DefaultSubagentExecutionManagerNotifiedStateTest {

    @Test
    @DisplayName("non-terminal task present: our transition applies and its state is reported")
    void appliesTransitionWhenNonTerminal() {
        InMemoryBackgroundTaskStore store = new InMemoryBackgroundTaskStore();
        store.put(running("t1"));

        BackgroundTaskState reported = DefaultSubagentExecutionManager.resolveNotifiedState(store, "t1",
                BackgroundTaskState.COMPLETED);

        assertThat(reported).isEqualTo(BackgroundTaskState.COMPLETED);
        assertThat(store.find("t1").map(BackgroundTask::getState)).contains(BackgroundTaskState.COMPLETED);
    }

    @Test
    @DisplayName("already-terminal task (reaper FAILED) present: FAILED is reported, not the local COMPLETED")
    void reportsAuthoritativeTerminalState() {
        InMemoryBackgroundTaskStore store = new InMemoryBackgroundTaskStore();
        store.put(running("t1"));
        store.transition("t1", BackgroundTaskState.FAILED); // ZombieTaskReaper flips a heartbeat-expired task

        BackgroundTaskState reported = DefaultSubagentExecutionManager.resolveNotifiedState(store, "t1",
                BackgroundTaskState.COMPLETED);

        assertThat(reported).isEqualTo(BackgroundTaskState.FAILED);
        assertThat(store.find("t1").map(BackgroundTask::getState)).contains(BackgroundTaskState.FAILED);
    }

    @Test
    @DisplayName("evicted reaped task + local COMPLETED: settles to FAILED, never mis-reports COMPLETED")
    void evictedReapedTaskIsNotReportedCompleted() {
        // Small cap so a reaped-then-evicted task reproduces the exact overflow window from the review finding.
        InMemoryBackgroundTaskStore store = new InMemoryBackgroundTaskStore(2);
        Instant base = Instant.parse("2020-01-01T00:00:00Z");
        // The reaper flipped this task to FAILED with the earliest end time, so it evicts first under overflow.
        store.put(terminal("reaped", BackgroundTaskState.FAILED, base));
        for (int i = 1; i <= 5; i++) {
            store.put(terminal("t" + i, BackgroundTaskState.COMPLETED, base.plusSeconds(i)));
        }
        assertThat(store.find("reaped")).as("reaped entry must have been evicted").isEmpty();

        // The hung future finally completes successfully -> local finalState COMPLETED. It must NOT be reported.
        BackgroundTaskState reported = DefaultSubagentExecutionManager.resolveNotifiedState(store, "reaped",
                BackgroundTaskState.COMPLETED);

        assertThat(reported).isEqualTo(BackgroundTaskState.FAILED);
    }

    @Test
    @DisplayName("absent task + local KILLED/FAILED: the locally-authoritative terminal state is trusted")
    void absentTaskTrustsLocalNonCompletedState() {
        InMemoryBackgroundTaskStore store = new InMemoryBackgroundTaskStore();

        assertThat(DefaultSubagentExecutionManager.resolveNotifiedState(store, "gone", BackgroundTaskState.KILLED))
                .isEqualTo(BackgroundTaskState.KILLED);
        assertThat(DefaultSubagentExecutionManager.resolveNotifiedState(store, "gone", BackgroundTaskState.FAILED))
                .isEqualTo(BackgroundTaskState.FAILED);
    }

    private static BackgroundTask running(String id) {
        return BackgroundTask.builder().taskId(id).subagentName("s").state(BackgroundTaskState.RUNNING)
                .startTime(Instant.now()).build();
    }

    private static BackgroundTask terminal(String id, BackgroundTaskState state, Instant end) {
        return BackgroundTask.builder().taskId(id).subagentName("s").state(state).startTime(end).endTime(end).build();
    }
}
