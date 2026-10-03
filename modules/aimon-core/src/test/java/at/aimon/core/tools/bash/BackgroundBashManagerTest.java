package at.aimon.core.tools.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellation;

/**
 * {@link BackgroundBashManager} as the application-scoped task list (EE-7): who may see a task, what survives in the
 * store, what closing does. The id-only methods that predate it are covered through {@code BashOutputToolTest}.
 */
class BackgroundBashManagerTest {

    private static final AgentRuntimeId ACME = AgentRuntimeId.fromName("ops", "acme");
    private static final AgentRuntimeId GLOBEX = AgentRuntimeId.fromName("ops", "globex");
    private static final ExecutionOptions OPTIONS = ExecutionOptions.builder().timeout(Duration.ofHours(1))
            .background(true).build();

    private final List<BackgroundBashManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        managers.forEach(BackgroundBashManager::close);
    }

    private BackgroundBashManager manager(BackgroundBashManager.Builder builder) {
        final BackgroundBashManager manager = builder.build();
        managers.add(manager);
        return manager;
    }

    private BackgroundBashManager manager() {
        return manager(BackgroundBashManager.builder());
    }

    @Nested
    @DisplayName("Starting")
    class Starting {

        @Test
        @DisplayName("runs the command with a cancellation signal when the shell can honour one")
        void startGivesACancellableShellASignal() throws Exception {
            final ControllableShell shell = ControllableShell.cancellable();

            final BackgroundBashTask task = manager().start(ACME, "npm run dev", shell, OPTIONS);
            shell.awaitStarted();

            assertThat(task.getTaskId()).matches("bash_[0-9a-f]{8}");
            assertThat(task.getStatus()).isEqualTo(BashTaskStatus.RUNNING);
            assertThat(task.isCancellable()).isTrue();
            assertThat(task.getOwnerRuntimeId()).contains(ACME);
            assertThat(task.getTimeout()).contains(Duration.ofHours(1));
            assertThat(shell.lastOptions().getCancellation()).isNotSameAs(ShellCancellation.none());
            // Everything else the caller asked for is passed on untouched.
            assertThat(shell.lastOptions().isBackground()).isTrue();
            assertThat(shell.lastOptions().getTimeout()).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("gives a shell that cannot cancel no signal, and records the task as unstoppable")
        void startLeavesAnUncancellableShellAlone() throws Exception {
            final ControllableShell shell = ControllableShell.uncancellable();

            final BackgroundBashTask task = manager().start(ACME, "npm run dev", shell, OPTIONS);
            shell.awaitStarted();

            assertThat(task.isCancellable()).isFalse();
            assertThat(shell.lastOptions().getCancellation()).isSameAs(ShellCancellation.none());
            shell.finish("done");
        }

        @Test
        @DisplayName("records the task in the store before running it, and its end when it ends")
        void startRecordsTheTask() throws Exception {
            final InMemoryBackgroundBashStore store = new InMemoryBackgroundBashStore();
            final BackgroundBashManager manager = manager(
                    BackgroundBashManager.builder().store(store).nodeId("node-a"));
            final ControllableShell shell = ControllableShell.cancellable();

            final BackgroundBashTask task = manager.start(ACME, "npm run dev", shell, OPTIONS);

            final BackgroundBashRecord running = store.find(task.getTaskId()).orElseThrow();
            assertThat(running.getStatus()).isEqualTo(BashTaskStatus.RUNNING);
            assertThat(running.getOwnerRuntimeId()).contains(ACME);
            assertThat(running.getNodeId()).isEqualTo("node-a");
            assertThat(running.getExpiresAt()).contains(running.getStartedAt().plus(Duration.ofHours(1)));

            shell.finish("done");
            assertThat(task.awaitCompletion(Duration.ofSeconds(5))).isTrue();

            awaitStatus(store, task.getTaskId(), BashTaskStatus.COMPLETED);
            assertThat(store.find(task.getTaskId()).orElseThrow().getExitCode()).contains(0);
        }

        @Test
        @DisplayName("draws another id when the store says the first is taken")
        void startRetriesATakenId() {
            final AtomicInteger refusals = new AtomicInteger(2);
            final BackgroundBashStore store = new DelegatingStore() {
                @Override
                public boolean putIfAbsent(BackgroundBashRecord record) {
                    return refusals.getAndDecrement() <= 0 && super.putIfAbsent(record);
                }
            };
            final ControllableShell shell = ControllableShell.cancellable();

            final BackgroundBashTask task = manager(BackgroundBashManager.builder().store(store)).start(ACME, "true",
                    shell, OPTIONS);

            assertThat(refusals.get()).isLessThan(0);
            assertThat(store.find(task.getTaskId())).isPresent();
            shell.finish("");
        }

        @Test
        @DisplayName("does not run a command the store could not record")
        void startFailsWhenTheStoreFails() {
            final BackgroundBashStore store = new DelegatingStore() {
                @Override
                public boolean putIfAbsent(BackgroundBashRecord record) {
                    throw new IllegalStateException("store is down");
                }
            };
            final BackgroundBashManager manager = manager(BackgroundBashManager.builder().store(store));
            final ControllableShell shell = ControllableShell.cancellable();

            assertThatThrownBy(() -> manager.start(ACME, "rm -rf build", shell, OPTIONS))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("store is down");

            assertThat(shell.wasStarted()).isFalse();
            assertThat(manager.getActiveTaskCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Finding")
    class Finding {

        @Test
        @DisplayName("a task is found by the runtime that started it and by no other")
        void findIsScopedToTheOwner() {
            final BackgroundBashManager manager = manager();
            final ControllableShell shell = ControllableShell.cancellable();
            final String taskId = manager.start(ACME, "npm run dev", shell, OPTIONS).getTaskId();

            assertThat(manager.find(ACME, taskId).kind()).isEqualTo(BackgroundBashLookup.Kind.LOCAL);
            assertThat(manager.find(GLOBEX, taskId).kind()).isEqualTo(BackgroundBashLookup.Kind.NOT_FOUND);
            // A caller with no runtime id sees only tasks started with none — it is not a wildcard.
            assertThat(manager.find(null, taskId).kind()).isEqualTo(BackgroundBashLookup.Kind.NOT_FOUND);
            assertThat(manager.find(ACME, "bash_ffffffff").kind()).isEqualTo(BackgroundBashLookup.Kind.NOT_FOUND);
            shell.finish("");
        }

        @Test
        @DisplayName("an ownerless task is found only by a caller without a runtime id")
        void ownerlessTasks() {
            final BackgroundBashManager manager = manager();
            final ControllableShell shell = ControllableShell.cancellable();
            final String taskId = manager.start(null, "true", shell, OPTIONS).getTaskId();

            assertThat(manager.find(null, taskId).kind()).isEqualTo(BackgroundBashLookup.Kind.LOCAL);
            assertThat(manager.find(ACME, taskId).kind()).isEqualTo(BackgroundBashLookup.Kind.NOT_FOUND);
            shell.finish("");
        }

        @Test
        @DisplayName("a record another node wrote is reported as running elsewhere, to its owner only")
        void recordOfAnotherNode() {
            final InMemoryBackgroundBashStore shared = new InMemoryBackgroundBashStore();
            final BackgroundBashManager nodeA = manager(BackgroundBashManager.builder().store(shared).nodeId("a"));
            final BackgroundBashManager nodeB = manager(BackgroundBashManager.builder().store(shared).nodeId("b"));
            final ControllableShell shell = ControllableShell.cancellable();
            final String taskId = nodeA.start(ACME, "npm run dev", shell, OPTIONS).getTaskId();

            final BackgroundBashLookup fromB = nodeB.find(ACME, taskId);

            assertThat(fromB.kind()).isEqualTo(BackgroundBashLookup.Kind.ELSEWHERE);
            assertThat(fromB.record().orElseThrow().getNodeId()).isEqualTo("a");
            assertThat(fromB.lostByThisNode()).isFalse();
            assertThat(fromB.expired()).isFalse();
            assertThat(nodeB.find(GLOBEX, taskId).kind()).isEqualTo(BackgroundBashLookup.Kind.NOT_FOUND);
            assertThat(nodeB.kill(ACME, taskId).outcome()).isEqualTo(BackgroundBashKill.Outcome.ELSEWHERE);
            assertThat(shell.lastOptions() == null || !shell.lastOptions().getCancellation().isCancelled())
                    .as("another node cannot stop the command").isTrue();
            shell.finish("");
        }

        @Test
        @DisplayName("a record naming this node without a task is one the node lost in a restart")
        void recordThisNodeLost() {
            final InMemoryBackgroundBashStore durable = new InMemoryBackgroundBashStore();
            durable.putIfAbsent(BackgroundBashRecord.builder().taskId("bash_0000aaaa").ownerRuntimeId(ACME).nodeId("a")
                    .startedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build());
            final BackgroundBashManager restarted = manager(BackgroundBashManager.builder().store(durable).nodeId("a"));

            final BackgroundBashLookup lookup = restarted.find(ACME, "bash_0000aaaa");

            assertThat(lookup.kind()).isEqualTo(BackgroundBashLookup.Kind.ELSEWHERE);
            assertThat(lookup.lostByThisNode()).isTrue();
        }

        @Test
        @DisplayName("a record still running past its expiry is reported once as unknown, then dropped")
        void expiredRecordIsDropped() {
            final InMemoryBackgroundBashStore shared = new InMemoryBackgroundBashStore();
            final Instant started = Instant.parse("2026-10-01T00:00:00Z");
            shared.putIfAbsent(BackgroundBashRecord.builder().taskId("bash_0000bbbb").ownerRuntimeId(ACME)
                    .nodeId("gone").startedAt(started).expiresAt(started.plusSeconds(3600)).build());
            final BackgroundBashManager manager = manager(BackgroundBashManager.builder().store(shared).nodeId("b")
                    .clock(Clock.fixed(started.plusSeconds(7200), ZoneOffset.UTC)));

            final BackgroundBashLookup first = manager.find(ACME, "bash_0000bbbb");

            assertThat(first.kind()).isEqualTo(BackgroundBashLookup.Kind.ELSEWHERE);
            assertThat(first.expired()).isTrue();
            assertThat(shared.find("bash_0000bbbb")).isEmpty();
            assertThat(manager.find(ACME, "bash_0000bbbb").kind()).isEqualTo(BackgroundBashLookup.Kind.NOT_FOUND);
        }

        @Test
        @DisplayName("a store that cannot be read does not hide this node's own tasks")
        void storeFailureOnFind() {
            final AtomicReference<RuntimeException> findFailure = new AtomicReference<>();
            final BackgroundBashStore store = new DelegatingStore() {
                @Override
                public Optional<BackgroundBashRecord> find(String taskId) {
                    if (findFailure.get() != null) {
                        throw findFailure.get();
                    }
                    return super.find(taskId);
                }
            };
            final BackgroundBashManager manager = manager(BackgroundBashManager.builder().store(store));
            final ControllableShell shell = ControllableShell.cancellable();
            final String taskId = manager.start(ACME, "true", shell, OPTIONS).getTaskId();
            findFailure.set(new IllegalStateException("store is down"));

            // The handle is here, so the store is never asked.
            assertThat(manager.find(ACME, taskId).kind()).isEqualTo(BackgroundBashLookup.Kind.LOCAL);
            assertThatThrownBy(() -> manager.find(ACME, "bash_ffffffff")).hasMessageContaining("store is down");
            shell.finish("");
        }
    }

    @Nested
    @DisplayName("Killing")
    class Killing {

        @Test
        @DisplayName("trips the signal of a running command, which settles as KILLED with what it had printed")
        void killStopsARunningCommand() throws Exception {
            final InMemoryBackgroundBashStore store = new InMemoryBackgroundBashStore();
            final BackgroundBashManager manager = manager(BackgroundBashManager.builder().store(store));
            final ControllableShell shell = ControllableShell.cancellable();
            final BackgroundBashTask task = manager.start(ACME, "npm run dev", shell, OPTIONS);
            shell.awaitStarted();

            final BackgroundBashKill kill = manager.kill(ACME, task.getTaskId());

            assertThat(kill.outcome()).isEqualTo(BackgroundBashKill.Outcome.REQUESTED);
            assertThat(task.awaitCompletion(Duration.ofSeconds(5))).isTrue();
            assertThat(task.getStatus()).isEqualTo(BashTaskStatus.KILLED);
            assertThat(task.getExitCode()).as("a killed command has no exit code").isNull();
            assertThat(task.readNewOutput()).contains(ControllableShell.PARTIAL_OUTPUT);
            awaitStatus(store, task.getTaskId(), BashTaskStatus.KILLED);
        }

        @Test
        @DisplayName("answers UNSUPPORTED for a shell that cannot cancel, and the command keeps running")
        void killOfAnUncancellableCommand() throws Exception {
            final BackgroundBashManager manager = manager();
            final ControllableShell shell = ControllableShell.uncancellable();
            final BackgroundBashTask task = manager.start(ACME, "npm run dev", shell, OPTIONS);
            shell.awaitStarted();

            assertThat(manager.kill(ACME, task.getTaskId()).outcome())
                    .isEqualTo(BackgroundBashKill.Outcome.UNSUPPORTED);

            assertThat(task.getStatus()).isEqualTo(BashTaskStatus.RUNNING);
            shell.finish("done");
        }

        @Test
        @DisplayName("answers NOT_RUNNING for a command that already ended, NOT_FOUND for another runtime's")
        void killEdges() {
            final BackgroundBashManager manager = manager();
            final ControllableShell shell = ControllableShell.cancellable();
            final BackgroundBashTask task = manager.start(ACME, "true", shell, OPTIONS);

            assertThat(manager.kill(GLOBEX, task.getTaskId()).outcome())
                    .isEqualTo(BackgroundBashKill.Outcome.NOT_FOUND);
            assertThat(task.getStatus()).as("a stranger's kill reaches nothing").isEqualTo(BashTaskStatus.RUNNING);

            shell.finish("done");
            assertThat(task.awaitCompletion(Duration.ofSeconds(5))).isTrue();

            assertThat(manager.kill(ACME, task.getTaskId()).outcome())
                    .isEqualTo(BackgroundBashKill.Outcome.NOT_RUNNING);
            assertThat(task.getStatus()).isEqualTo(BashTaskStatus.COMPLETED);
        }

        @Test
        @DisplayName("a task registered from a bare future cannot be killed")
        void registeredTasksAreNotCancellable() {
            final BackgroundBashManager manager = manager();
            manager.registerTask("bash_legacy01", "sleep 10", new java.util.concurrent.CompletableFuture<>());

            assertThat(manager.kill(null, "bash_legacy01").outcome()).isEqualTo(BackgroundBashKill.Outcome.UNSUPPORTED);
        }
    }

    @Nested
    @DisplayName("Retention and closing")
    class RetentionAndClosing {

        @Test
        @DisplayName("a task finished for longer than the retention period is dropped, record and all, at next start")
        void finishedTasksAreSweptAfterRetention() {
            final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T10:00:00Z"));
            final InMemoryBackgroundBashStore store = new InMemoryBackgroundBashStore();
            final BackgroundBashManager manager = manager(
                    BackgroundBashManager.builder().store(store).retention(Duration.ofHours(1)).clock(clock));
            final ControllableShell finished = ControllableShell.cancellable();
            final BackgroundBashTask old = manager.start(ACME, "true", finished, OPTIONS);
            finished.finish("done");
            assertThat(old.awaitCompletion(Duration.ofSeconds(5))).isTrue();
            final ControllableShell stillRunning = ControllableShell.cancellable();
            final BackgroundBashTask running = manager.start(ACME, "npm run dev", stillRunning, OPTIONS);

            clock.advance(Duration.ofMinutes(59));
            final ControllableShell second = ControllableShell.cancellable();
            manager.start(ACME, "true", second, OPTIONS);
            assertThat(manager.find(ACME, old.getTaskId()).kind()).as("still within retention")
                    .isEqualTo(BackgroundBashLookup.Kind.LOCAL);

            clock.advance(Duration.ofMinutes(2));
            final ControllableShell third = ControllableShell.cancellable();
            manager.start(ACME, "true", third, OPTIONS);

            assertThat(manager.find(ACME, old.getTaskId()).kind()).isEqualTo(BackgroundBashLookup.Kind.NOT_FOUND);
            assertThat(store.find(old.getTaskId())).isEmpty();
            // Age alone never drops a task: one still running is kept however long ago it started.
            assertThat(manager.find(ACME, running.getTaskId()).kind()).isEqualTo(BackgroundBashLookup.Kind.LOCAL);
            List.of(stillRunning, second, third).forEach(shell -> shell.finish(""));
        }

        @Test
        @DisplayName("close() stops what can be stopped, refuses new tasks, and keeps finished ones readable")
        void closeStopsRunningCommands() throws Exception {
            final BackgroundBashManager manager = manager();
            final ControllableShell first = ControllableShell.cancellable();
            final ControllableShell second = ControllableShell.cancellable();
            final BackgroundBashTask one = manager.start(ACME, "npm run dev", first, OPTIONS);
            final BackgroundBashTask two = manager.start(GLOBEX, "npm run dev", second, OPTIONS);
            first.awaitStarted();
            second.awaitStarted();

            manager.close();
            manager.close();

            assertThat(one.getStatus()).isEqualTo(BashTaskStatus.KILLED);
            assertThat(two.getStatus()).isEqualTo(BashTaskStatus.KILLED);
            assertThat(manager.find(ACME, one.getTaskId()).kind()).isEqualTo(BackgroundBashLookup.Kind.LOCAL);
            assertThatThrownBy(() -> manager.start(ACME, "true", ControllableShell.cancellable(), OPTIONS))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }

        @Test
        @DisplayName("a store that fails to record the end leaves this node's answer correct")
        void settleFailureIsTolerated() throws Exception {
            final BackgroundBashStore store = new DelegatingStore() {
                @Override
                public Optional<BackgroundBashRecord> settle(String taskId, BashTaskStatus terminal, Integer exitCode,
                        Instant at) {
                    throw new IllegalStateException("store is down");
                }
            };
            final BackgroundBashManager manager = manager(BackgroundBashManager.builder().store(store));
            final ControllableShell shell = ControllableShell.cancellable();
            final BackgroundBashTask task = manager.start(ACME, "true", shell, OPTIONS);

            shell.finish("done");

            assertThat(task.awaitCompletion(Duration.ofSeconds(5))).isTrue();
            assertThat(manager.find(ACME, task.getTaskId()).task().orElseThrow().getStatus())
                    .isEqualTo(BashTaskStatus.COMPLETED);
        }
    }

    /** The store records the end on the command's thread, a moment after the task reports it. */
    private static void awaitStatus(BackgroundBashStore store, String taskId, BashTaskStatus expected)
            throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (store.find(taskId).orElseThrow().getStatus() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("record of " + taskId + " is " + store.find(taskId).orElseThrow().getStatus()
                        + ", expected " + expected);
            }
            Thread.sleep(10);
        }
    }

    /** An in-memory store a test overrides one method of. */
    private static class DelegatingStore implements BackgroundBashStore {
        private final InMemoryBackgroundBashStore delegate = new InMemoryBackgroundBashStore();

        @Override
        public boolean putIfAbsent(BackgroundBashRecord record) {
            return delegate.putIfAbsent(record);
        }

        @Override
        public Optional<BackgroundBashRecord> find(String taskId) {
            return delegate.find(taskId);
        }

        @Override
        public Optional<BackgroundBashRecord> settle(String taskId, BashTaskStatus terminal, Integer exitCode,
                Instant at) {
            return delegate.settle(taskId, terminal, exitCode, at);
        }

        @Override
        public void remove(String taskId) {
            delegate.remove(taskId);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
