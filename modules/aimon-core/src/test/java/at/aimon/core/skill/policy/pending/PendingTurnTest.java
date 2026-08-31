package at.aimon.core.skill.policy.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;
import at.aimon.core.agent.session.SessionId;

/** Unit tests for {@link PendingTurn}. */
class PendingTurnTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    @Test
    void buildRequiresAllMandatoryFields() {
        assertThatThrownBy(() -> PendingTurn.builder().build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildSucceedsWithMinimalRequiredFields() {
        final PendingTurn turn = newTurn().build();

        assertThat(turn.getId()).isNotNull();
        assertThat(turn.getAgentRuntimeId()).isNotNull();
        assertThat(turn.getPendingSkills()).hasSize(1);
        assertThat(turn.getCreatedAt()).isEqualTo(T0);
        assertThat(turn.getExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
    }

    @Test
    void pendingSkillsListIsImmutableSnapshot() {
        final List<PendingSkillRequest> mutable = new ArrayList<>(List.of(req("commit")));
        final PendingTurn turn = newTurn().pendingSkills(mutable).build();

        mutable.add(req("deploy"));  // Mutating the source after build must not affect the snapshot.

        assertThat(turn.getPendingSkills()).hasSize(1);
        assertThatThrownBy(() -> turn.getPendingSkills().add(req("nope")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyPendingSkillsRejected() {
        // A pending turn with zero ASK skills makes no sense — guard against accidental construction.
        assertThatThrownBy(() -> newTurn().pendingSkills(List.of()).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
    }

    @Test
    void expiresAtBeforeCreatedAtRejected() {
        assertThatThrownBy(() -> PendingTurn.builder().id(PendingTurnId.generate())
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).pendingSkills(List.of(req("commit"))).createdAt(T0)
                .expiresAt(T0.minusSeconds(1)).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ttlConvenienceDerivesExpiresAt() {
        final PendingTurn turn = PendingTurn.builder().id(PendingTurnId.generate())
                .agentRuntimeId(AgentRuntimeId.of("agent:test-2")).pendingSkills(List.of(req("commit"))).createdAt(T0)
                .ttl(Duration.ofMinutes(5)).build();

        assertThat(turn.getExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    }

    @Test
    void ttlBeforeCreatedAtRejected() {
        assertThatThrownBy(() -> PendingTurn.builder().id(PendingTurnId.generate())
                .agentRuntimeId(AgentRuntimeId.of("agent:test-3")).pendingSkills(List.of(req("commit")))
                .ttl(Duration.ofMinutes(5)).build()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isExpiredAfterExpiresAt() {
        final PendingTurn turn = newTurn().build();

        assertThat(turn.isExpired(turn.getExpiresAt())).isFalse();  // Strictly before, so equal-at-boundary is alive.
        assertThat(turn.isExpired(turn.getExpiresAt().plusSeconds(1))).isTrue();
        assertThat(turn.isExpired(turn.getExpiresAt().minusSeconds(1))).isFalse();
    }

    @Test
    void sessionIdIsCarriedWhenGivenAndOptionalWhenNot() {
        // Pins both directions of the contract the approval commands are written against: the id round-trips, and
        // omitting it yields an empty optional rather than a null. The registrar in the agent loop always supplies
        // one — the empty case exists for entries an embedder registers itself. See PendingTurn#getSessionId().
        final SessionId session = SessionId.of("sess-1");

        assertThat(newTurn().sessionId(session).build().getSessionId()).contains(session);
        assertThat(newTurn().build().getSessionId()).isEmpty();
    }

    @Test
    void equalityBasedOnAllFields() {
        final PendingTurnId id = PendingTurnId.of("same");
        final AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx");
        final PendingTurn a = PendingTurn.builder().id(id).agentRuntimeId(ctx).pendingSkills(List.of(req("commit")))
                .createdAt(T0).ttl(Duration.ofMinutes(1)).build();
        final PendingTurn b = PendingTurn.builder().id(id).agentRuntimeId(ctx).pendingSkills(List.of(req("commit")))
                .createdAt(T0).ttl(Duration.ofMinutes(1)).build();
        final PendingTurn different = PendingTurn.builder().id(id).agentRuntimeId(ctx)
                .pendingSkills(List.of(req("deploy"))).createdAt(T0).ttl(Duration.ofMinutes(1)).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }

    private static PendingTurn.Builder newTurn() {
        return PendingTurn.builder().id(PendingTurnId.generate()).agentRuntimeId(AgentRuntimeId.of("agent:test-4"))
                .pendingSkills(List.of(req("commit"))).createdAt(T0).ttl(Duration.ofMinutes(30));
    }

    private static PendingSkillRequest req(String skill) {
        return PendingSkillRequest.builder().toolUseId("tu_" + skill).skillName(skill).build();
    }
}
