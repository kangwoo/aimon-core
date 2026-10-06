/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.exception.QuotaExceededException;
import at.aimon.core.scheduling.exception.SchedulingException;
import at.aimon.core.scheduling.exception.TaskSchedulerException;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.InMemoryTaskScheduler;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

/**
 * Pins what an engine owes the tasks it finds already stored when it starts.
 *
 * <p>
 * A restart is modelled the way it happens: one engine registers, is closed, and a second engine is built over the
 * <em>same repository</em> with a scheduler and a quota ledger of its own. Sharing the repository instance stands in
 * for a durable one — what survives a restart is exactly what that instance holds — and giving the second engine a
 * fresh scheduler is what makes "it is scheduled again" something the engine had to do rather than inherit.
 */
@DisplayName("Scheduling engine — tasks already stored when it starts")
class ScheduledTaskRehydrationTest {

    private final Principal alice = Principal.user("alice");
    private final List<SchedulingEngine> engines = new ArrayList<>();

    @AfterEach
    void closeEngines() {
        engines.forEach(SchedulingEngine::close);
    }

    @Test
    @DisplayName("an enabled task stored before the restart is scheduled again")
    void enabledTaskIsScheduledAgain() {
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        final ScheduledTask task = task("nightly", true);
        startedEngine(stored, new RecordingScheduler(), 5).getTaskManager().register(task);
        engines.get(0).close();

        final RecordingScheduler afterRestart = new RecordingScheduler();
        startedEngine(stored, afterRestart, 5);

        assertThat(afterRestart.exists(task.getId())).isTrue();
    }

    @Test
    @DisplayName("a disabled task stays stored and unscheduled")
    void disabledTaskIsLeftUnscheduled() {
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        final ScheduledTask task = task("paused", false);
        stored.save(task);

        final RecordingScheduler scheduler = new RecordingScheduler();
        startedEngine(stored, scheduler, 5);

        assertThat(scheduler.exists(task.getId())).isFalse();
        assertThat(stored.findById(task.getId())).isPresent();
    }

    @Test
    @DisplayName("a task the scheduler already holds is not scheduled a second time")
    void taskTheSchedulerKeptIsNotScheduledTwice() {
        // The durable-scheduler case: a JDBC job store brings its own triggers back, and scheduling over one would at
        // best be redundant and at worst reset its fire times.
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        final ScheduledTask task = task("kept", true);
        stored.save(task);
        final RecordingScheduler scheduler = new RecordingScheduler();
        scheduler.held.add(task.getId());

        startedEngine(stored, scheduler, 5);

        assertThat(scheduler.scheduleCalls).isEmpty();
    }

    @Test
    @DisplayName("one task the scheduler refuses does not cost the others their schedule")
    void oneRefusedTaskDoesNotBlockTheRest() {
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        final ScheduledTask refused = task("refused", true);
        final ScheduledTask fine = task("fine", true);
        stored.save(refused);
        stored.save(fine);
        final RecordingScheduler scheduler = new RecordingScheduler();
        scheduler.refuse.add(refused.getId());

        startedEngine(stored, scheduler, 5);

        assertThat(scheduler.exists(fine.getId())).isTrue();
        assertThat(scheduler.exists(refused.getId())).isFalse();
    }

    @Test
    @DisplayName("the default quota counts the stored tasks after a restart, enabled or not")
    void quotaCountsStoredTasksAfterRestart() {
        // A ledger kept in memory beside a durable repository is emptied by the restart the tasks survive, and every
        // owner gets a full quota on top of what they hold. The default counts the repository instead.
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        stored.save(task("one", true));
        stored.save(task("two", false));

        final SchedulingEngine engine = startedEngine(stored, new RecordingScheduler(), 2);

        assertThatThrownBy(() -> engine.getTaskManager().register(task("three", true)))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    @DisplayName("two engines over one repository share one quota, in both directions")
    void quotaIsSharedByEnginesOverOneRepository() {
        // Two nodes. With a ledger per node, node B admits a second task at quota 1, and after both are cancelled
        // through B, node A — which never heard — goes on refusing an owner who now has none.
        final ScheduledTaskRepository shared = new InMemoryScheduledTaskRepository();
        final SchedulingEngine nodeA = startedEngine(shared, new RecordingScheduler(), 1);
        final SchedulingEngine nodeB = startedEngine(shared, new RecordingScheduler(), 1);
        final ScheduledTask first = task("first", true);
        nodeA.getTaskManager().register(first);

        assertThatThrownBy(() -> nodeB.getTaskManager().register(task("second", true)))
                .isInstanceOf(QuotaExceededException.class);

        nodeB.getTaskManager().cancel(first.getId(), alice);

        nodeA.getTaskManager().register(task("third", true));
        assertThat(shared.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("starting twice schedules nothing twice")
    void startingTwiceIsHarmless() {
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        stored.save(task("one", true));
        final RecordingScheduler scheduler = new RecordingScheduler();
        final SchedulingEngine engine = startedEngine(stored, scheduler, 2);

        engine.start();

        assertThat(scheduler.scheduleCalls).hasSize(1);
    }

    @Test
    @DisplayName("rehydrate reports how many tasks it scheduled")
    void rehydrateReportsWhatItScheduled() {
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        final SchedulingEngine engine = startedEngine(stored, new RecordingScheduler(), 5);
        stored.save(task("late-one", true));
        stored.save(task("late-two", true));
        stored.save(task("late-off", false));

        assertThat(engine.getTaskManager().rehydrate()).isEqualTo(2);
        assertThat(engine.getTaskManager().rehydrate()).isZero();
    }

    @Test
    @DisplayName("the real in-memory scheduler holds the stored task after start")
    void realInMemorySchedulerIsRehydrated() {
        // Every other test here uses a scheduler that accepts a schedule before it is started. The real one refuses,
        // so this is the test that fails if start() schedules before it starts the scheduler.
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        final ScheduledTask task = task("real", true);
        stored.save(task);
        final AtomicReference<TaskScheduler> real = new AtomicReference<>();
        final SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(stored)
                .taskSchedulerFactory(executor -> {
                    real.set(new InMemoryTaskScheduler(executor));
                    return real.get();
                }).build();
        engines.add(engine);

        engine.start();

        assertThat(real.get().exists(task.getId())).isTrue();
    }

    @Test
    @DisplayName("a repository that cannot be read fails the start with the scheduler not started, and a retry works")
    void unreadableRepositoryFailsTheStartCleanly() {
        final AtomicBoolean broken = new AtomicBoolean(true);
        final ScheduledTask task = task("after-recovery", true);
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository() {

            @Override
            public List<ScheduledTask> findByEnabledTrue() {
                if (broken.get()) {
                    throw new SchedulingException("store unreachable");
                }
                return super.findByEnabledTrue();
            }
        };
        stored.save(task);
        final RecordingScheduler scheduler = new RecordingScheduler();
        final SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(stored)
                .taskSchedulerFactory(executor -> scheduler).build();
        engines.add(engine);

        assertThatThrownBy(engine::start).isInstanceOf(SchedulingException.class);
        // Not half-started: a running scheduler with no stored task scheduled is the state the throw exists to avoid.
        assertThat(scheduler.started).isFalse();

        broken.set(false);
        engine.start();

        assertThat(scheduler.started).isTrue();
        assertThat(scheduler.exists(task.getId())).isTrue();
    }

    @Test
    @DisplayName("a task cancelled between the read and the schedule is not left with a trigger")
    void cancelRacingRehydrationLeavesNoOrphanTrigger() {
        // The interleaving is forced: the repository deletes the task as soon as it has been listed, which is what a
        // cancel on another thread — or another node — does between rehydrate's read and its schedule.
        final ScheduledTask task = task("cancelled-meanwhile", true);
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository() {

            @Override
            public List<ScheduledTask> findByEnabledTrue() {
                final List<ScheduledTask> listed = super.findByEnabledTrue();
                listed.forEach(listedTask -> deleteById(listedTask.getId()));
                return listed;
            }
        };
        stored.save(task);
        final RecordingScheduler scheduler = new RecordingScheduler();

        startedEngine(stored, scheduler, 5);

        assertThat(scheduler.exists(task.getId())).isFalse();
    }

    @Test
    @DisplayName("on one node of several, a per-node scheduler is not handed the stored tasks")
    void multiNodeWithAPerNodeSchedulerDoesNotRehydrate() {
        // Every node would rebuild every trigger and every node would fire every task, with a node-local guard
        // between them. Not firing after a restart is the lesser failure, and it is the one that was there before.
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        final ScheduledTask task = task("shared", true);
        stored.save(task);
        final RecordingScheduler scheduler = new RecordingScheduler();
        final SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(stored)
                .taskSchedulerFactory(executor -> scheduler).multiNode(true).build();
        engines.add(engine);

        engine.start();

        assertThat(engine.rehydratesAtStart()).isFalse();
        assertThat(scheduler.started).isTrue();
        assertThat(scheduler.exists(task.getId())).isFalse();
    }

    @Test
    @DisplayName("on one node of several, a cluster-wide scheduler is")
    void multiNodeWithAClusterWideSchedulerRehydrates() {
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        final ScheduledTask task = task("shared", true);
        stored.save(task);
        final RecordingScheduler scheduler = new RecordingScheduler();
        scheduler.clusterWide = true;
        final SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(stored)
                .taskSchedulerFactory(executor -> scheduler).multiNode(true).build();
        engines.add(engine);

        engine.start();

        assertThat(engine.rehydratesAtStart()).isTrue();
        assertThat(scheduler.exists(task.getId())).isTrue();
    }

    @Test
    @DisplayName("on one node of several, a supplied execution guard makes a per-node scheduler safe to rehydrate")
    void multiNodeWithASuppliedGuardRehydrates() {
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        final ScheduledTask task = task("guarded", true);
        stored.save(task);
        final RecordingScheduler scheduler = new RecordingScheduler();
        final SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(stored)
                .taskSchedulerFactory(executor -> scheduler).multiNode(true)
                .executionGuard(new InMemoryScheduledExecutionGuard()).build();
        engines.add(engine);

        engine.start();

        assertThat(scheduler.exists(task.getId())).isTrue();
    }

    private SchedulingEngine startedEngine(ScheduledTaskRepository stored, TaskScheduler scheduler, int quota) {
        final SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(stored)
                .taskSchedulerFactory(executor -> scheduler).defaultMaxQuota(quota).build();
        engines.add(engine);
        engine.start();
        return engine;
    }

    private ScheduledTask task(String name, boolean enabled) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name(name).cronExpression("0 0 1 1 *")
                .owner(alice).boundRuntimeId(AgentRuntimeId.fromName("rehydration"))
                .routine(List.of(RoutineStep.of("noop", "{}"))).enabled(enabled).build();
    }

    /** A scheduler that remembers what it was asked to hold, and nothing fires. */
    private static final class RecordingScheduler implements TaskScheduler {

        private final Set<ScheduledTaskId> held = new HashSet<>();
        private final Set<ScheduledTaskId> refuse = new HashSet<>();
        private final List<ScheduledTaskId> scheduleCalls = new ArrayList<>();
        private boolean started;
        private boolean clusterWide;

        @Override
        public void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression) {
            scheduleCalls.add(taskId);
            if (refuse.contains(taskId)) {
                throw new TaskSchedulerException(taskId.value(), "this backend cannot express the schedule");
            }
            held.add(taskId);
        }

        @Override
        public void unschedule(ScheduledTaskId taskId) {
            held.remove(taskId);
        }

        @Override
        public boolean exists(ScheduledTaskId taskId) {
            return held.contains(taskId);
        }

        @Override
        public void clear() {
            held.clear();
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public boolean isClusterWide() {
            return clusterWide;
        }

        @Override
        public void shutdown() {
        }
    }
}
