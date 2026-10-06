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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.exception.QuotaExceededException;
import at.aimon.core.scheduling.exception.TaskSchedulerException;
import at.aimon.core.scheduling.quota.TaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
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
    @DisplayName("the default quota ledger counts the stored tasks again, enabled or not")
    void quotaCountsStoredTasksAfterRestart() {
        // The default ledger is in memory. Without the recount, a restart hands every owner a full quota on top of
        // the tasks they already have.
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        stored.save(task("one", true));
        stored.save(task("two", false));

        final SchedulingEngine engine = startedEngine(stored, new RecordingScheduler(), 2);

        assertThatThrownBy(() -> engine.getTaskManager().register(task("three", true)))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    @DisplayName("starting twice schedules nothing twice and counts nothing twice")
    void startingTwiceIsHarmless() {
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        stored.save(task("one", true));
        final RecordingScheduler scheduler = new RecordingScheduler();
        final SchedulingEngine engine = startedEngine(stored, scheduler, 2);

        engine.start();

        assertThat(scheduler.scheduleCalls).hasSize(1);
        // One slot of two is used, so exactly one more registration fits.
        engine.getTaskManager().register(task("two", true));
        assertThatThrownBy(() -> engine.getTaskManager().register(task("three", true)))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    @DisplayName("a quota manager that is not the default is left to keep its own ledger")
    void customQuotaManagerIsNotRecounted() {
        final ScheduledTaskRepository stored = new InMemoryScheduledTaskRepository();
        stored.save(task("one", true));
        final CountingQuotaManager custom = new CountingQuotaManager();
        final SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(stored)
                .taskSchedulerFactory(executor -> new RecordingScheduler()).quotaManager(custom).build();
        engines.add(engine);

        engine.start();

        assertThat(custom.increments).isZero();
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
        }

        @Override
        public void shutdown() {
        }
    }

    /** A ledger that only counts how often it was charged. */
    private static final class CountingQuotaManager implements TaskQuotaManager {

        private int increments;

        @Override
        public void checkQuota(Principal principal) {
        }

        @Override
        public void incrementUsage(Principal principal) {
            increments++;
        }

        @Override
        public void decrementUsage(Principal principal) {
        }

        @Override
        public int getCurrentUsage(Principal principal) {
            return 0;
        }

        @Override
        public int getMaxQuota(Principal principal) {
            return Integer.MAX_VALUE;
        }
    }
}
