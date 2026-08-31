package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.RunQuery;
import at.aimon.core.workflow.WorkflowRun;
import at.aimon.core.workflow.WorkflowRunState;

@DisplayName("InMemoryRunStore — node-local RunStore with atomic idempotency + terminal eviction")
class InMemoryRunStoreTest {

    private static final Instant T0 = Instant.parse("2026-07-22T00:00:00Z");

    private final InMemoryRunStore store = new InMemoryRunStore();

    private static WorkflowRun pending(String name) {
        return WorkflowRun.pending(RunId.from(name), name, null, null, T0);
    }

    @Test
    @DisplayName("put then find/list round-trips the snapshot")
    void putFindList() {
        final WorkflowRun run = pending("audit");
        store.put(run);

        assertThat(store.find(RunId.from("audit"))).contains(run);
        assertThat(store.find(RunId.from("missing"))).isEmpty();
        assertThat(store.list(RunQuery.all())).containsExactly(run);
    }

    @Test
    @DisplayName("putIfAbsentOrTerminal: inserts when absent, refuses a non-terminal duplicate, allows resubmit once terminal")
    void putIfAbsentOrTerminalIdempotency() {
        final RunId id = RunId.from("audit");

        // absent → inserted
        assertThat(store.putIfAbsentOrTerminal(pending("audit"))).isTrue();

        // non-terminal exists → refused, original kept (no double dispatch)
        final WorkflowRun secondAttempt = WorkflowRun.pending(id, "audit-v2", null, null, T0);
        assertThat(store.putIfAbsentOrTerminal(secondAttempt)).isFalse();
        assertThat(store.find(id).map(WorkflowRun::getScriptName)).contains("audit");

        // drive it terminal, then a resubmit is allowed (replaces)
        store.transition(id, WorkflowRunState.COMPLETED);
        final WorkflowRun resubmit = WorkflowRun.pending(id, "audit-rerun", null, null, T0);
        assertThat(store.putIfAbsentOrTerminal(resubmit)).isTrue();
        assertThat(store.find(id).map(WorkflowRun::getScriptName)).contains("audit-rerun");
        assertThat(store.find(id).map(WorkflowRun::getState)).contains(WorkflowRunState.PENDING);
    }

    @Test
    @DisplayName("transition applies to a non-terminal run and stamps endTime when the target is terminal")
    void transitionStampsEndTime() {
        store.put(pending("audit"));
        final RunId id = RunId.from("audit");

        assertThat(store.transition(id, WorkflowRunState.RUNNING)).get().extracting(WorkflowRun::getState)
                .isEqualTo(WorkflowRunState.RUNNING);
        assertThat(store.find(id).flatMap(WorkflowRun::getEndTime)).isEmpty();

        final WorkflowRun done = store.transition(id, WorkflowRunState.COMPLETED).orElseThrow();
        assertThat(done.getState()).isEqualTo(WorkflowRunState.COMPLETED);
        assertThat(done.getEndTime()).isPresent();
    }

    @Test
    @DisplayName("transition is a guard-and-return no-op for unknown or already-terminal runs")
    void transitionGuard() {
        // unknown
        assertThat(store.transition(RunId.from("ghost"), WorkflowRunState.RUNNING)).isEmpty();

        // already terminal → no-op
        store.put(pending("audit"));
        final RunId id = RunId.from("audit");
        store.transition(id, WorkflowRunState.COMPLETED);
        assertThat(store.transition(id, WorkflowRunState.RUNNING)).isEmpty();
        assertThat(store.find(id).map(WorkflowRun::getState)).contains(WorkflowRunState.COMPLETED);
    }

    @Test
    @DisplayName("heartbeat renews a non-terminal run and is a no-op once terminal")
    void heartbeatGuard() {
        store.put(pending("audit"));
        final RunId id = RunId.from("audit");
        final Instant hb = T0.plusSeconds(5);

        assertThat(store.heartbeat(id, hb)).get().extracting(WorkflowRun::getLastHeartbeat)
                .isEqualTo(java.util.Optional.of(hb));

        store.transition(id, WorkflowRunState.KILLED);
        assertThat(store.heartbeat(id, T0.plusSeconds(10))).isEmpty();
    }

    @Test
    @DisplayName("list filters by query; remove deletes")
    void listFilterAndRemove() {
        store.put(pending("a"));
        store.put(pending("b"));
        store.transition(RunId.from("b"), WorkflowRunState.RUNNING);

        assertThat(store.list(RunQuery.byState(WorkflowRunState.RUNNING))).extracting(r -> r.getRunId().scriptName())
                .containsExactly("b");

        store.remove(RunId.from("a"));
        assertThat(store.find(RunId.from("a"))).isEmpty();
    }

    @Test
    @DisplayName("terminal runs are evicted oldest-first once over the cap; in-flight runs are never evicted")
    void terminalEviction() {
        final InMemoryRunStore small = new InMemoryRunStore(2);
        // one in-flight run that must survive regardless of the terminal cap
        small.put(WorkflowRun.pending(RunId.from("live"), "live", null, null, T0).toBuilder()
                .state(WorkflowRunState.RUNNING).build());
        // three terminal runs with DISTINCT explicit end times (not Instant.now(), so eviction order is deterministic);
        // cap is 2 → once the third lands, the oldest (t0) is evicted.
        for (int i = 0; i < 3; i++) {
            small.put(WorkflowRun.pending(RunId.from("t" + i), "t" + i, null, null, T0).toBuilder()
                    .state(WorkflowRunState.COMPLETED).endTime(T0.plusSeconds(i)).build());
        }

        assertThat(small.find(RunId.from("live"))).isPresent();            // in-flight survives
        assertThat(small.find(RunId.from("t0"))).isEmpty();               // oldest terminal evicted
        assertThat(small.find(RunId.from("t1"))).isPresent();
        assertThat(small.find(RunId.from("t2"))).isPresent();
    }

    @Test
    @DisplayName("null arguments are rejected")
    void nullArgs() {
        assertThatThrownBy(() -> store.put(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.putIfAbsentOrTerminal(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.find(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.list(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.transition(null, WorkflowRunState.RUNNING))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.transition(RunId.from("a"), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.heartbeat(null, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.heartbeat(RunId.from("a"), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.remove(null)).isInstanceOf(NullPointerException.class);
    }
}
