package at.aimon.core.skill.policy.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;

/** Unit tests for {@link InMemoryPendingTurnRegistry}. */
class InMemoryPendingTurnRegistryTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    private InMemoryPendingTurnRegistry registry;
    private AgentRuntimeId ctxA;
    private AgentRuntimeId ctxB;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPendingTurnRegistry();
        ctxA = AgentRuntimeId.of("agent:test-1");
        ctxB = AgentRuntimeId.of("agent:test-2");
    }

    @Test
    void registerThenGetReturnsSnapshot() {
        final PendingTurn turn = turn(ctxA, T0);
        registry.register(turn);

        assertThat(registry.get(turn.getId())).contains(turn);
    }

    @Test
    void getReturnsEmptyForUnknownId() {
        assertThat(registry.get(PendingTurnId.generate())).isEmpty();
    }

    @Test
    void registerRejectsDuplicateId() {
        final PendingTurn turn = turn(ctxA, T0);
        registry.register(turn);

        assertThatThrownBy(() -> registry.register(turn)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listByContextReturnsTurnsForThatContextOnly() {
        final PendingTurn a1 = turn(ctxA, T0);
        final PendingTurn a2 = turn(ctxA, T0.plusSeconds(10));
        final PendingTurn b1 = turn(ctxB, T0.plusSeconds(5));
        registry.register(a1);
        registry.register(a2);
        registry.register(b1);

        assertThat(registry.listByAgentRuntime(ctxA)).containsExactly(a1, a2);  // Sorted by createdAt.
        assertThat(registry.listByAgentRuntime(ctxB)).containsExactly(b1);
    }

    @Test
    void listByContextReturnsImmutableSnapshot() {
        registry.register(turn(ctxA, T0));
        final List<PendingTurn> snapshot = registry.listByAgentRuntime(ctxA);

        assertThatThrownBy(() -> snapshot.add(turn(ctxA, T0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listAllReturnsTurnsAcrossContextsSortedByCreatedAt() {
        // Register out of chronological order to prove the registry sorts.
        final PendingTurn b1 = turn(ctxB, T0.plusSeconds(20));
        final PendingTurn a1 = turn(ctxA, T0);
        final PendingTurn a2 = turn(ctxA, T0.plusSeconds(10));
        registry.register(b1);
        registry.register(a1);
        registry.register(a2);

        assertThat(registry.listAll()).containsExactly(a1, a2, b1);
    }

    @Test
    void listAllReturnsEmptyWhenNoTurnsRegistered() {
        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void listAllReturnsImmutableSnapshot() {
        registry.register(turn(ctxA, T0));
        final List<PendingTurn> snapshot = registry.listAll();

        assertThatThrownBy(() -> snapshot.add(turn(ctxA, T0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void removeReturnsAndDropsTheTurn() {
        final PendingTurn turn = turn(ctxA, T0);
        registry.register(turn);

        assertThat(registry.remove(turn.getId())).contains(turn);
        assertThat(registry.get(turn.getId())).isEmpty();
        // Removing again is a no-op.
        assertThat(registry.remove(turn.getId())).isEmpty();
    }

    @Test
    void removeExpiredDropsOnlyExpiredTurns() {
        final PendingTurn shortLived = turn(ctxA, T0, Duration.ofMinutes(1));
        final PendingTurn longLived = turn(ctxA, T0, Duration.ofHours(1));
        registry.register(shortLived);
        registry.register(longLived);

        final List<PendingTurn> expired = registry.removeExpired(T0.plus(Duration.ofMinutes(2)));

        assertThat(expired).containsExactly(shortLived);
        assertThat(registry.get(shortLived.getId())).isEmpty();
        assertThat(registry.get(longLived.getId())).contains(longLived);
    }

    @Test
    void removeExpiredAtBoundaryKeepsTurnAlive() {
        // PendingTurn.isExpired uses strict "before", so a turn whose expiresAt equals now is still alive.
        final PendingTurn turn = turn(ctxA, T0, Duration.ofMinutes(5));
        registry.register(turn);

        assertThat(registry.removeExpired(turn.getExpiresAt())).isEmpty();
        assertThat(registry.get(turn.getId())).contains(turn);
    }

    @Test
    void allMethodsRejectNullArgs() {
        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.get(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.listByAgentRuntime(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.remove(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.removeExpired(null)).isInstanceOf(NullPointerException.class);
    }

    private static PendingTurn turn(AgentRuntimeId ctx, Instant createdAt) {
        return turn(ctx, createdAt, Duration.ofMinutes(30));
    }

    private static PendingTurn turn(AgentRuntimeId ctx, Instant createdAt, Duration ttl) {
        return PendingTurn
                .builder().id(PendingTurnId.generate()).agentRuntimeId(ctx).pendingSkills(List.of(PendingSkillRequest
                        .builder().toolUseId("tu_" + createdAt.toEpochMilli()).skillName("commit").build()))
                .createdAt(createdAt).ttl(ttl).build();
    }
}
