package at.aimon.core.skill.policy.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;

/** Unit tests for {@link InMemorySessionApprovalStore}. */
@DisplayName("InMemorySessionApprovalStore")
class InMemorySessionApprovalStoreTest {

    private InMemorySessionApprovalStore store;
    private SessionId convA;
    private SessionId convB;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionApprovalStore();
        convA = SessionId.of("conv-a");
        convB = SessionId.of("conv-b");
    }

    @Test
    void getReturnsEmptyWhenNothingStored() {
        assertThat(store.get(convA, "commit")).isEmpty();
    }

    @Test
    void putThenGetRoundTripsDecision() {
        store.put(convA, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(convA, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void putOverwritesExistingDecision() {
        store.put(convA, "commit", SkillInvocationDecision.ALLOW);
        store.put(convA, "commit", SkillInvocationDecision.DENY);

        assertThat(store.get(convA, "commit")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    @DisplayName("an answer given in one conversation is invisible to another — the whole point of this store")
    void entriesAreScopedPerConversation() {
        store.put(convA, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(convB, "commit")).isEmpty();
    }

    @Test
    @DisplayName("a DENY in one conversation does not pre-answer the same skill in another")
    void denyDoesNotLeakAcrossConversations() {
        store.put(convA, "deploy", SkillInvocationDecision.DENY);

        // Empty, not DENY: session B must reach the policy and ask the user for itself. Leaking the DENY here
        // would be the mirror image of the agent-scoped store's leak, and just as surprising.
        assertThat(store.get(convB, "deploy")).isEmpty();
    }

    @Test
    @DisplayName("conversation ids with the same value are the same key")
    void keyingIsByValueNotIdentity() {
        store.put(SessionId.of("conv-shared"), "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(SessionId.of("conv-shared"), "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void entriesAreScopedPerSkillName() {
        store.put(convA, "commit", SkillInvocationDecision.ALLOW);

        assertThat(store.get(convA, "deploy")).isEmpty();
    }

    @Test
    void invalidateDropsAllEntriesForConversation() {
        store.put(convA, "commit", SkillInvocationDecision.ALLOW);
        store.put(convA, "deploy", SkillInvocationDecision.DENY);
        store.put(convB, "commit", SkillInvocationDecision.ALLOW);

        store.invalidate(convA);

        assertThat(store.get(convA, "commit")).isEmpty();
        assertThat(store.get(convA, "deploy")).isEmpty();
        // Other sessions are untouched.
        assertThat(store.get(convB, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void invalidateOnUnknownConversationIsNoOp() {
        store.invalidate(convA);  // Must not throw.

        assertThat(store.get(convA, "commit")).isEmpty();
    }

    @Test
    void putRejectsAskDecision() {
        assertThatThrownBy(() -> store.put(convA, "commit", SkillInvocationDecision.ASK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ASK");
    }

    @Test
    void getRejectsNullArguments() {
        assertThatThrownBy(() -> store.get(null, "commit")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.get(convA, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void putRejectsNullArguments() {
        assertThatThrownBy(() -> store.put(null, "commit", SkillInvocationDecision.ALLOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.put(convA, null, SkillInvocationDecision.ALLOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.put(convA, "commit", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void invalidateRejectsNullConversationId() {
        assertThatThrownBy(() -> store.invalidate(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNonPositiveBound() {
        assertThatThrownBy(() -> new InMemorySessionApprovalStore(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 1");
        assertThatThrownBy(() -> new InMemorySessionApprovalStore(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lruBoundEvictsEldestConversationOnceCapacityExceeded() {
        // Capacity 2: store 3 sessions and verify the oldest disappears. Eviction is a soft "forget" — the
        // underlying policy will simply re-prompt the user, so this is acceptable.
        final InMemorySessionApprovalStore tiny = new InMemorySessionApprovalStore(2);
        final SessionId first = SessionId.of("conv-1");
        final SessionId second = SessionId.of("conv-2");
        final SessionId third = SessionId.of("conv-3");

        tiny.put(first, "commit", SkillInvocationDecision.ALLOW);
        tiny.put(second, "commit", SkillInvocationDecision.ALLOW);
        tiny.put(third, "commit", SkillInvocationDecision.ALLOW);

        assertThat(tiny.get(first, "commit")).isEmpty();  // Evicted.
        assertThat(tiny.get(second, "commit")).contains(SkillInvocationDecision.ALLOW);
        assertThat(tiny.get(third, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    @DisplayName("the default bound is larger than the agent-scoped store's — sessions outnumber runtimes")
    void defaultBoundExceedsAgentScopedStoreBound() {
        assertThat(InMemorySessionApprovalStore.DEFAULT_MAX_TRACKED_SESSIONS)
                .isGreaterThan(InMemoryAgentApprovalStore.DEFAULT_MAX_TRACKED_CONTEXTS);
    }
}
