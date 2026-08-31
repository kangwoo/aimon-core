package at.aimon.core.skill.policy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.skill.policy.SkillInvocationDecision;

/** Unit tests for {@link InMemoryAgentApprovalStore}. */
class InMemoryAgentApprovalStoreTest {

    private InMemoryAgentApprovalStore store;
    private AgentRuntimeId ctxA;
    private AgentRuntimeId ctxB;

    @BeforeEach
    void setUp() {
        store = new InMemoryAgentApprovalStore();
        ctxA = AgentRuntimeId.of("agent:test-1");
        ctxB = AgentRuntimeId.of("agent:test-2");
    }

    @Test
    void getReturnsEmptyWhenNothingStored() {
        assertThat(store.get(ctxA, "commit")).isEmpty();
    }

    @Test
    void putThenGetRoundTripsDecision() {
        store.put(ctxA, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(ctxA, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void putOverwritesExistingDecision() {
        store.put(ctxA, "commit", SkillInvocationDecision.ALLOW);
        store.put(ctxA, "commit", SkillInvocationDecision.DENY);

        assertThat(store.get(ctxA, "commit")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    void entriesAreScopedPerContext() {
        store.put(ctxA, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(ctxB, "commit")).isEmpty();
    }

    @Test
    void entriesAreScopedPerSkillName() {
        store.put(ctxA, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(ctxA, "deploy")).isEmpty();
    }

    @Test
    void invalidateDropsAllEntriesForContext() {
        store.put(ctxA, "commit", SkillInvocationDecision.ALLOW);
        store.put(ctxA, "deploy", SkillInvocationDecision.DENY);
        store.put(ctxB, "commit", SkillInvocationDecision.ALLOW);

        store.invalidate(ctxA);

        assertThat(store.get(ctxA, "commit")).isEmpty();
        assertThat(store.get(ctxA, "deploy")).isEmpty();
        // Other contexts are untouched.
        assertThat(store.get(ctxB, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void invalidateOnUnknownContextIsNoOp() {
        store.invalidate(ctxA);  // Must not throw.

        assertThat(store.get(ctxA, "commit")).isEmpty();
    }

    @Test
    void putRejectsAskDecision() {
        assertThatThrownBy(() -> store.put(ctxA, "commit", SkillInvocationDecision.ASK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ASK");
    }

    @Test
    void getRejectsNullArguments() {
        assertThatThrownBy(() -> store.get(null, "commit")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.get(ctxA, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void putRejectsNullArguments() {
        assertThatThrownBy(() -> store.put(null, "commit", SkillInvocationDecision.ALLOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.put(ctxA, null, SkillInvocationDecision.ALLOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.put(ctxA, "commit", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void invalidateRejectsNullRuntimeId() {
        assertThatThrownBy(() -> store.invalidate(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNonPositiveBound() {
        assertThatThrownBy(() -> new InMemoryAgentApprovalStore(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 1");
        assertThatThrownBy(() -> new InMemoryAgentApprovalStore(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lruBoundEvictsEldestContextOnceCapacityExceeded() {
        // Capacity 2: store 3 contexts and verify the oldest disappears. Eviction is a soft "forget" — the underlying
        // policy will simply re-prompt the user, so this is acceptable.
        final InMemoryAgentApprovalStore tiny = new InMemoryAgentApprovalStore(2);
        final AgentRuntimeId first = AgentRuntimeId.of("agent:test-3");
        final AgentRuntimeId second = AgentRuntimeId.of("agent:test-4");
        final AgentRuntimeId third = AgentRuntimeId.of("agent:test-5");

        tiny.put(first, "commit", SkillInvocationDecision.ALLOW);
        tiny.put(second, "commit", SkillInvocationDecision.ALLOW);
        tiny.put(third, "commit", SkillInvocationDecision.ALLOW);

        assertThat(tiny.get(first, "commit")).isEmpty();  // Evicted.
        assertThat(tiny.get(second, "commit")).contains(SkillInvocationDecision.ALLOW);
        assertThat(tiny.get(third, "commit")).contains(SkillInvocationDecision.ALLOW);
    }
}
