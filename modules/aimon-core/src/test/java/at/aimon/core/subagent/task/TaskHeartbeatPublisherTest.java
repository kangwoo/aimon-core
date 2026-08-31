package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskHeartbeatPublisher — renews the lease of locally-owned tasks (design §5.3.2 ③, §7)")
class TaskHeartbeatPublisherTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryBackgroundTaskStore store;
    private AtomicReference<Instant> now;
    private Clock clock;
    private final TaskLeaseConfig config = TaskLeaseConfig.defaults();

    @BeforeEach
    void setUp() {
        store = new InMemoryBackgroundTaskStore();
        now = new AtomicReference<>(T0);
        clock = new FixedRefClock(now);
    }

    private void putRunning(String taskId) {
        store.put(BackgroundTask.builder().taskId(taskId).subagentName("explore").state(BackgroundTaskState.RUNNING)
                .startTime(T0).lastHeartbeat(T0).build());
    }

    @Test
    @DisplayName("publishOnce stamps the current instant on every locally-owned task and counts renewals")
    void renewsOwnedTasks() {
        putRunning("a");
        putRunning("b");
        Supplier<Set<String>> ids = () -> Set.of("a", "b");
        TaskHeartbeatPublisher publisher = new TaskHeartbeatPublisher(store, ids, config, clock);
        now.set(T0.plusSeconds(30));

        int renewed = publisher.publishOnce();

        assertThat(renewed).isEqualTo(2);
        assertThat(store.find("a").orElseThrow().getLastHeartbeat()).contains(T0.plusSeconds(30));
        assertThat(store.find("b").orElseThrow().getLastHeartbeat()).contains(T0.plusSeconds(30));
    }

    @Test
    @DisplayName("a heartbeat for a task that already completed is a guarded no-op (not counted)")
    void terminalTaskNotRenewed() {
        putRunning("a");
        store.transition("a", BackgroundTaskState.COMPLETED);
        TaskHeartbeatPublisher publisher = new TaskHeartbeatPublisher(store, () -> Set.of("a"), config, clock);

        assertThat(publisher.publishOnce()).isZero();
    }

    @Test
    @DisplayName("a stale id no longer in the store is skipped without error")
    void unknownIdSkipped() {
        putRunning("a");
        TaskHeartbeatPublisher publisher = new TaskHeartbeatPublisher(store, () -> Set.of("a", "gone"), config, clock);

        assertThat(publisher.publishOnce()).isEqualTo(1);
    }

    @Test
    @DisplayName("a per-task store failure is swallowed so the remaining tasks still get heartbeated")
    void perTaskFailureIsolated() {
        putRunning("a");
        putRunning("b");
        BackgroundTaskStore flaky = new DelegatingStore(store) {
            @Override
            public Optional<BackgroundTask> heartbeat(String taskId, Instant at) {
                if ("a".equals(taskId)) {
                    throw new IllegalStateException("boom");
                }
                return super.heartbeat(taskId, at);
            }
        };
        TaskHeartbeatPublisher publisher = new TaskHeartbeatPublisher(flaky, () -> Set.of("a", "b"), config, clock);
        now.set(T0.plusSeconds(5));

        int renewed = publisher.publishOnce();

        assertThat(renewed).isEqualTo(1);
        assertThat(store.find("b").orElseThrow().getLastHeartbeat()).contains(T0.plusSeconds(5));
    }

    @Test
    @DisplayName("no owned tasks renews nothing")
    void noOwnedTasks() {
        TaskHeartbeatPublisher publisher = new TaskHeartbeatPublisher(store, Set::of, config, clock);
        assertThat(publisher.publishOnce()).isZero();
    }

    /** A clock reading its instant from an external {@link AtomicReference}. */
    private static final class FixedRefClock extends Clock {
        private final AtomicReference<Instant> now;

        private FixedRefClock(AtomicReference<Instant> now) {
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

    /** Forwards every {@link BackgroundTaskStore} call to a delegate; subclasses override the one method under test. */
    private static class DelegatingStore implements BackgroundTaskStore {
        private final BackgroundTaskStore delegate;

        private DelegatingStore(BackgroundTaskStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(BackgroundTask task) {
            delegate.put(task);
        }

        @Override
        public Optional<BackgroundTask> find(String taskId) {
            return delegate.find(taskId);
        }

        @Override
        public java.util.List<BackgroundTask> list(TaskQuery query) {
            return delegate.list(query);
        }

        @Override
        public Optional<BackgroundTask> transition(String taskId, BackgroundTaskState newState) {
            return delegate.transition(taskId, newState);
        }

        @Override
        public Optional<BackgroundTask> heartbeat(String taskId, Instant at) {
            return delegate.heartbeat(taskId, at);
        }

        @Override
        public void remove(String taskId) {
            delegate.remove(taskId);
        }
    }
}
