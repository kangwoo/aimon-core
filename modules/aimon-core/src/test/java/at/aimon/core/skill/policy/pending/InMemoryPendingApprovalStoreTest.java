package at.aimon.core.skill.policy.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.skill.policy.SkillInvocationDecision;

/** Unit tests for {@link InMemoryPendingApprovalStore}. */
class InMemoryPendingApprovalStoreTest {

    private InMemoryPendingApprovalStore store;
    private PendingTurnId turnA;
    private PendingTurnId turnB;

    @BeforeEach
    void setUp() {
        store = new InMemoryPendingApprovalStore();
        turnA = PendingTurnId.generate();
        turnB = PendingTurnId.generate();
    }

    @Test
    void getReturnsEmptyWhenNothingRecorded() {
        assertThat(store.get(turnA, "commit")).isEmpty();
    }

    @Test
    void recordThenGetRoundTrips() {
        store.record(turnA, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(turnA, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void recordOverwritesPriorEntry() {
        store.record(turnA, "commit", SkillInvocationDecision.ALLOW);
        store.record(turnA, "commit", SkillInvocationDecision.DENY);

        assertThat(store.get(turnA, "commit")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    void entriesScopedPerTurn() {
        store.record(turnA, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(turnB, "commit")).isEmpty();
    }

    @Test
    void entriesScopedPerSkillName() {
        store.record(turnA, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(turnA, "deploy")).isEmpty();
    }

    @Test
    void getAllReturnsImmutableSnapshotOfTurnEntries() {
        store.record(turnA, "commit", SkillInvocationDecision.ALLOW);
        store.record(turnA, "deploy", SkillInvocationDecision.DENY);
        store.record(turnB, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.getAll(turnA)).containsOnly(entry("commit", SkillInvocationDecision.ALLOW),
                entry("deploy", SkillInvocationDecision.DENY));
        assertThat(store.getAll(turnB)).containsOnly(entry("commit", SkillInvocationDecision.ALLOW));

        // Snapshot must be immutable.
        assertThatThrownBy(() -> store.getAll(turnA).put("nope", SkillInvocationDecision.ALLOW))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getAllReturnsEmptyMapForUnknownTurn() {
        assertThat(store.getAll(turnA)).isEmpty();
    }

    @Test
    void clearDropsAllEntriesForTurn() {
        store.record(turnA, "commit", SkillInvocationDecision.ALLOW);
        store.record(turnA, "deploy", SkillInvocationDecision.ALLOW);
        store.record(turnB, "commit", SkillInvocationDecision.ALLOW);

        store.clear(turnA);

        assertThat(store.get(turnA, "commit")).isEmpty();
        assertThat(store.get(turnA, "deploy")).isEmpty();
        assertThat(store.get(turnB, "commit")).contains(SkillInvocationDecision.ALLOW);  // Other turns untouched.
    }

    @Test
    void clearOnUnknownTurnIsNoOp() {
        store.clear(turnA);
        assertThat(store.get(turnA, "commit")).isEmpty();
    }

    @Test
    void recordRejectsAskDecision() {
        assertThatThrownBy(() -> store.record(turnA, "commit", SkillInvocationDecision.ASK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ASK");
    }

    @Test
    void allMethodsRejectNullArgs() {
        assertThatThrownBy(() -> store.record(null, "commit", SkillInvocationDecision.ALLOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.record(turnA, null, SkillInvocationDecision.ALLOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.record(turnA, "commit", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.get(null, "commit")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.get(turnA, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.getAll(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.clear(null)).isInstanceOf(NullPointerException.class);
    }

    private static java.util.Map.Entry<String, SkillInvocationDecision> entry(String key,
            SkillInvocationDecision value) {
        return java.util.Map.entry(key, value);
    }
}
