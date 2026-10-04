package at.aimon.core.tools.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;

/**
 * What every {@link BackgroundBashStore} must do, whatever it is backed by. A store implementation extends this and
 * supplies itself through {@link #createStore()}.
 */
public abstract class BackgroundBashStoreContractTest {

    private static final Instant STARTED = Instant.parse("2026-10-03T10:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-10-03T10:05:00Z");

    /**
     * @return a new, empty store
     */
    protected abstract BackgroundBashStore createStore();

    private static BackgroundBashRecord running(String taskId) {
        return BackgroundBashRecord.builder().taskId(taskId).ownerRuntimeId(AgentRuntimeId.fromName("ops", "acme"))
                .nodeId("node-a").startedAt(STARTED).expiresAt(STARTED.plusSeconds(3600)).build();
    }

    @Test
    @DisplayName("a stored record is found again with every field it was stored with")
    void putThenFind() {
        final BackgroundBashStore store = createStore();

        assertThat(store.putIfAbsent(running("bash_00000001"))).isTrue();

        assertThat(store.find("bash_00000001")).hasValueSatisfying(record -> {
            assertThat(record.getTaskId()).isEqualTo("bash_00000001");
            assertThat(record.getOwnerRuntimeId()).contains(AgentRuntimeId.fromName("ops", "acme"));
            assertThat(record.getNodeId()).isEqualTo("node-a");
            assertThat(record.getStartedAt()).isEqualTo(STARTED);
            assertThat(record.getExpiresAt()).contains(STARTED.plusSeconds(3600));
            assertThat(record.getStatus()).isEqualTo(BashTaskStatus.RUNNING);
            assertThat(record.getExitCode()).isEmpty();
            assertThat(record.getFinishedAt()).isEmpty();
        });
        assertThat(store.find("bash_ffffffff")).isEmpty();
    }

    @Test
    @DisplayName("a record without an owner or an expiry is stored as such")
    void optionalFieldsStayEmpty() {
        final BackgroundBashStore store = createStore();
        store.putIfAbsent(
                BackgroundBashRecord.builder().taskId("bash_00000002").nodeId("node-a").startedAt(STARTED).build());

        assertThat(store.find("bash_00000002")).hasValueSatisfying(record -> {
            assertThat(record.getOwnerRuntimeId()).isEmpty();
            assertThat(record.getOwnerSessionId()).isEmpty();
            assertThat(record.getOwnerExecutionId()).isEmpty();
            assertThat(record.owner()).isEqualTo(BackgroundBashOwner.none());
            assertThat(record.getExpiresAt()).isEmpty();
        });
    }

    @Test
    @DisplayName("the three ownership fields come back as written, and survive settle")
    void ownershipFieldsRoundTrip() {
        final BackgroundBashStore store = createStore();
        final AgentRuntimeId runtime = AgentRuntimeId.fromName("ops", "acme");
        final BackgroundBashOwner session = BackgroundBashOwner.of(runtime, SessionId.of("session-a"), null);
        final BackgroundBashOwner execution = BackgroundBashOwner.of(runtime, null, ExecutionId.of("routine:t:1"));
        store.putIfAbsent(running("bash_0000000a").toBuilder().owner(session).build());
        store.putIfAbsent(running("bash_0000000b").toBuilder().owner(execution).build());

        // A lookup compares all three, so a store that drops one hides the task from its own owner.
        assertThat(store.find("bash_0000000a")).hasValueSatisfying(record -> {
            assertThat(record.getOwnerSessionId()).contains(SessionId.of("session-a"));
            assertThat(record.getOwnerExecutionId()).isEmpty();
            assertThat(record.owner()).isEqualTo(session);
        });
        assertThat(store.find("bash_0000000b")).hasValueSatisfying(record -> {
            assertThat(record.getOwnerSessionId()).isEmpty();
            assertThat(record.getOwnerExecutionId()).contains(ExecutionId.of("routine:t:1"));
            assertThat(record.owner()).isEqualTo(execution);
        });
        assertThat(store.settle("bash_0000000a", BashTaskStatus.COMPLETED, 0, ENDED).orElseThrow().owner())
                .isEqualTo(session);
        assertThat(store.settle("bash_0000000b", BashTaskStatus.KILLED, null, ENDED).orElseThrow().owner())
                .isEqualTo(execution);
        assertThat(store.find("bash_0000000a").orElseThrow().owner()).isEqualTo(session);
    }

    @Test
    @DisplayName("putIfAbsent refuses a taken id and leaves the first record in place")
    void putIfAbsentRefusesATakenId() {
        final BackgroundBashStore store = createStore();
        store.putIfAbsent(running("bash_00000003"));

        final boolean stored = store.putIfAbsent(running("bash_00000003").toBuilder().nodeId("node-b").build());

        assertThat(stored).isFalse();
        assertThat(store.find("bash_00000003").orElseThrow().getNodeId()).isEqualTo("node-a");
    }

    @Test
    @DisplayName("settle moves a running record to its end state")
    void settleEndsARunningRecord() {
        final BackgroundBashStore store = createStore();
        store.putIfAbsent(running("bash_00000004"));

        assertThat(store.settle("bash_00000004", BashTaskStatus.FAILED, 2, ENDED)).hasValueSatisfying(record -> {
            assertThat(record.getStatus()).isEqualTo(BashTaskStatus.FAILED);
            assertThat(record.getExitCode()).contains(2);
            assertThat(record.getFinishedAt()).contains(ENDED);
        });
        assertThat(store.find("bash_00000004").orElseThrow().getStatus()).isEqualTo(BashTaskStatus.FAILED);
    }

    @Test
    @DisplayName("settle is one-way and idempotent: a record that has ended keeps its first end")
    void settleIsOneWay() {
        final BackgroundBashStore store = createStore();
        store.putIfAbsent(running("bash_00000005"));
        store.settle("bash_00000005", BashTaskStatus.KILLED, null, ENDED);

        final BackgroundBashRecord again = store
                .settle("bash_00000005", BashTaskStatus.COMPLETED, 0, ENDED.plusSeconds(60)).orElseThrow();

        assertThat(again.getStatus()).isEqualTo(BashTaskStatus.KILLED);
        assertThat(again.getExitCode()).isEmpty();
        assertThat(again.getFinishedAt()).contains(ENDED);
    }

    @Test
    @DisplayName("settle of an unknown id answers empty, and RUNNING is not an end state")
    void settleEdges() {
        final BackgroundBashStore store = createStore();
        store.putIfAbsent(running("bash_00000006"));

        assertThat(store.settle("bash_ffffffff", BashTaskStatus.COMPLETED, 0, ENDED)).isEmpty();
        assertThatThrownBy(() -> store.settle("bash_00000006", BashTaskStatus.RUNNING, null, ENDED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("remove drops the record and tolerates an unknown id")
    void removeDropsTheRecord() {
        final BackgroundBashStore store = createStore();
        store.putIfAbsent(running("bash_00000007"));

        store.remove("bash_00000007");
        store.remove("bash_ffffffff");

        assertThat(store.find("bash_00000007")).isEmpty();
        assertThat(store.putIfAbsent(running("bash_00000007"))).as("the id is free again").isTrue();
    }
}
