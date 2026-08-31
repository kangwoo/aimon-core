package at.aimon.core.skill.policy.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;

/** Unit tests for {@link PendingTurnReaper}. */
class PendingTurnReaperTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    private InMemoryPendingTurnRegistry registry;
    private AgentRuntimeId ctx;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPendingTurnRegistry();
        ctx = AgentRuntimeId.of("agent:test-1");
    }

    @Test
    void reapOnceEvictsExpiredTurnsUsingInjectedClock() {
        final PendingTurn shortLived = turn(T0, Duration.ofMinutes(1));
        final PendingTurn longLived = turn(T0, Duration.ofHours(1));
        registry.register(shortLived);
        registry.register(longLived);

        final PendingTurnReaper reaper = PendingTurnReaper.builder().registry(registry).interval(Duration.ofSeconds(10))
                .clock(Clock.fixed(T0.plus(Duration.ofMinutes(2)), ZoneOffset.UTC)).build();

        final List<PendingTurn> removed = reaper.reapOnce();

        assertThat(removed).containsExactly(shortLived);
        assertThat(registry.get(shortLived.getId())).isEmpty();
        assertThat(registry.get(longLived.getId())).contains(longLived);
    }

    @Test
    void reapOnceReturnsEmptyWhenNothingExpired() {
        registry.register(turn(T0, Duration.ofHours(1)));

        final PendingTurnReaper reaper = PendingTurnReaper.builder().registry(registry).interval(Duration.ofSeconds(10))
                .clock(Clock.fixed(T0.plusSeconds(5), ZoneOffset.UTC)).build();

        assertThat(reaper.reapOnce()).isEmpty();
    }

    @Test
    void expirationListenerNotifiedWithRemovedTurns() {
        final PendingTurn expired = turn(T0, Duration.ofMinutes(1));
        registry.register(expired);
        final AtomicReference<List<PendingTurn>> seen = new AtomicReference<>();

        final PendingTurnReaper reaper = PendingTurnReaper.builder().registry(registry).interval(Duration.ofSeconds(10))
                .clock(Clock.fixed(T0.plus(Duration.ofMinutes(2)), ZoneOffset.UTC)).expirationListener(seen::set)
                .build();

        reaper.reapOnce();

        assertThat(seen.get()).containsExactly(expired);
    }

    @Test
    void expirationListenerNotInvokedOnEmptySweep() {
        final AtomicReference<List<PendingTurn>> seen = new AtomicReference<>();
        final PendingTurnReaper reaper = PendingTurnReaper.builder().registry(registry).interval(Duration.ofSeconds(10))
                .clock(Clock.fixed(T0, ZoneOffset.UTC)).expirationListener(seen::set).build();

        reaper.reapOnce();

        assertThat(seen.get()).isNull();
    }

    @Test
    void listenerExceptionDoesNotPropagate() {
        registry.register(turn(T0, Duration.ofMinutes(1)));
        final PendingTurnReaper reaper = PendingTurnReaper.builder().registry(registry).interval(Duration.ofSeconds(10))
                .clock(Clock.fixed(T0.plus(Duration.ofMinutes(2)), ZoneOffset.UTC)).expirationListener(turns -> {
                    throw new RuntimeException("boom");
                }).build();

        // Should not throw.
        assertThat(reaper.reapOnce()).hasSize(1);
    }

    @Test
    void registryExceptionDuringSweepReturnsEmptyAndDoesNotPropagate() {
        final PendingTurnRegistry exploding = new PendingTurnRegistry() {
            @Override
            public void register(PendingTurn turn) {
            }

            @Override
            public Optional<PendingTurn> get(PendingTurnId id) {
                return Optional.empty();
            }

            @Override
            public List<PendingTurn> listByAgentRuntime(AgentRuntimeId agentRuntimeId) {
                return List.of();
            }

            @Override
            public List<PendingTurn> listAll() {
                return List.of();
            }

            @Override
            public Optional<PendingTurn> remove(PendingTurnId id) {
                return Optional.empty();
            }

            @Override
            public List<PendingTurn> removeExpired(Instant now) {
                throw new RuntimeException("storage offline");
            }
        };
        final PendingTurnReaper reaper = PendingTurnReaper.builder().registry(exploding)
                .interval(Duration.ofSeconds(10)).build();

        assertThat(reaper.reapOnce()).isEmpty();
    }

    @Test
    void startSchedulesPeriodicSweepThatEvictsExpiredTurns() throws Exception {
        // Use a very short interval so the test stays fast. The clock returns successive instants so the second sweep
        // sees the turn as expired.
        registry.register(turn(T0, Duration.ofMillis(50)));
        final List<Instant> ticks = List.of(T0.plusMillis(10), T0.plusSeconds(60));
        final Iterator<Instant> tickIter = ticks.iterator();
        final Clock advancingClock = new Clock() {
            @Override
            public Instant instant() {
                synchronized (tickIter) {
                    return tickIter.hasNext() ? tickIter.next() : T0.plusSeconds(60);
                }
            }

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
        final CountDownLatch removed = new CountDownLatch(1);
        final List<PendingTurn> seen = new ArrayList<>();
        final PendingTurnReaper reaper = PendingTurnReaper.builder().registry(registry).interval(Duration.ofMillis(20))
                .clock(advancingClock).expirationListener(turns -> {
                    seen.addAll(turns);
                    removed.countDown();
                }).build();
        try {
            reaper.start();
            assertThat(removed.await(2, TimeUnit.SECONDS)).as("listener must fire within timeout").isTrue();
            assertThat(seen).hasSize(1);
        } finally {
            reaper.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        final PendingTurnReaper reaper = PendingTurnReaper.builder().registry(registry).interval(Duration.ofSeconds(10))
                .build();
        reaper.start();
        reaper.close();
        reaper.close(); // No exception.
    }

    @Test
    void startIsIdempotent() {
        final PendingTurnReaper reaper = PendingTurnReaper.builder().registry(registry).interval(Duration.ofSeconds(10))
                .build();
        try {
            reaper.start();
            reaper.start(); // No exception, no extra task.
        } finally {
            reaper.close();
        }
    }

    @Test
    void builderRejectsNonPositiveInterval() {
        assertThatThrownBy(() -> PendingTurnReaper.builder().registry(registry).interval(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(
                () -> PendingTurnReaper.builder().registry(registry).interval(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    @Test
    void builderRejectsMissingRequiredFields() {
        assertThatThrownBy(() -> PendingTurnReaper.builder().interval(Duration.ofSeconds(10)).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("registry");
        assertThatThrownBy(() -> PendingTurnReaper.builder().registry(registry).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("interval");
    }

    private PendingTurn turn(Instant createdAt, Duration ttl) {
        return PendingTurn
                .builder().id(PendingTurnId.generate()).agentRuntimeId(ctx).pendingSkills(List.of(PendingSkillRequest
                        .builder().toolUseId("tu_" + createdAt.toEpochMilli()).skillName("commit").build()))
                .createdAt(createdAt).ttl(ttl).build();
    }
}
