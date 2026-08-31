package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ZombieTaskReaper — reaps heartbeat-expired non-terminal tasks to FAILED (design §5.3.2 ③, §7)")
class ZombieTaskReaperTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryBackgroundTaskStore store;
    private AtomicReference<Instant> now;
    private Clock clock;
    private TaskLeaseConfig config;

    @BeforeEach
    void setUp() {
        store = new InMemoryBackgroundTaskStore();
        now = new AtomicReference<>(T0);
        clock = new MovableClock(now);
        // heartbeat 1s, ttl 5s: a task is reapable once its heartbeat is older than 5s.
        config = TaskLeaseConfig.of(Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    private void putRunning(String taskId, Instant heartbeat) {
        store.put(BackgroundTask.builder().taskId(taskId).subagentName("explore").description("desc")
                .state(BackgroundTaskState.RUNNING).startTime(T0).lastHeartbeat(heartbeat).build());
    }

    @Test
    @DisplayName("reaps a RUNNING task whose heartbeat is older than the lease TTL")
    void reapsExpired() {
        putRunning("zombie", T0);
        now.set(T0.plusSeconds(6)); // 6s > 5s TTL

        ZombieTaskReaper reaper = new ZombieTaskReaper(store, config, clock);
        List<String> reaped = reaper.sweepOnce();

        assertThat(reaped).containsExactly("zombie");
        BackgroundTask task = store.find("zombie").orElseThrow();
        assertThat(task.getState()).isEqualTo(BackgroundTaskState.FAILED);
        assertThat(task.getEndTime()).isPresent();
    }

    @Test
    @DisplayName("leaves a task whose heartbeat is within the lease TTL")
    void leavesFresh() {
        putRunning("live", T0);
        now.set(T0.plusSeconds(4)); // 4s <= 5s TTL

        List<String> reaped = new ZombieTaskReaper(store, config, clock).sweepOnce();

        assertThat(reaped).isEmpty();
        assertThat(store.find("live").orElseThrow().getState()).isEqualTo(BackgroundTaskState.RUNNING);
    }

    @Test
    @DisplayName("boundary: age exactly equal to the TTL is not reaped (strictly greater required)")
    void boundaryNotReaped() {
        putRunning("edge", T0);
        now.set(T0.plusSeconds(5)); // exactly 5s == TTL

        assertThat(new ZombieTaskReaper(store, config, clock).sweepOnce()).isEmpty();
    }

    @Test
    @DisplayName("never touches a terminal task even if its heartbeat is ancient")
    void skipsTerminal() {
        store.put(BackgroundTask.builder().taskId("done").subagentName("explore").state(BackgroundTaskState.COMPLETED)
                .startTime(T0).lastHeartbeat(T0).endTime(T0).build());
        now.set(T0.plusSeconds(1_000));

        assertThat(new ZombieTaskReaper(store, config, clock).sweepOnce()).isEmpty();
        assertThat(store.find("done").orElseThrow().getState()).isEqualTo(BackgroundTaskState.COMPLETED);
    }

    @Test
    @DisplayName("falls back to startTime when a task has no recorded heartbeat")
    void fallsBackToStartTime() {
        store.put(BackgroundTask.builder().taskId("legacy").subagentName("explore").state(BackgroundTaskState.RUNNING)
                .startTime(T0).build()); // no lastHeartbeat
        now.set(T0.plusSeconds(6));

        assertThat(new ZombieTaskReaper(store, config, clock).sweepOnce()).containsExactly("legacy");
    }

    @Test
    @DisplayName("reaping is idempotent: a second sweep finds nothing left to reap")
    void idempotentSecondSweep() {
        putRunning("zombie", T0);
        now.set(T0.plusSeconds(6));
        ZombieTaskReaper reaper = new ZombieTaskReaper(store, config, clock);

        assertThat(reaper.sweepOnce()).containsExactly("zombie");
        assertThat(reaper.sweepOnce()).isEmpty();
    }

    @Test
    @DisplayName("reaps only the expired tasks in a mixed store")
    void reapsOnlyExpiredInMixedStore() {
        putRunning("old", T0);
        putRunning("fresh", T0.plusSeconds(5));
        now.set(T0.plusSeconds(7)); // old age 7s (reap), fresh age 2s (keep)

        List<String> reaped = new ZombieTaskReaper(store, config, clock).sweepOnce();

        assertThat(reaped).containsExactly("old");
        assertThat(store.find("fresh").orElseThrow().getState()).isEqualTo(BackgroundTaskState.RUNNING);
    }

    /** A clock whose instant is driven by an external {@link AtomicReference}, for deterministic staleness tests. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MovableClock(AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
