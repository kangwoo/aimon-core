package at.aimon.core.scheduling.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.scheduling.SchedulingEngine;
import at.aimon.core.scheduling.SchedulingEngineBuilder;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;

/**
 * Guards the wiring an application performs when it supplies its own {@link TaskScheduler} — the
 * {@code aimon-scheduling-quartz} case.
 *
 * <p>
 * This test deliberately lives outside {@code at.aimon.core.scheduling}: the documented wiring
 * ({@code .taskExecutor(taskId -> taskManager.executeTask(taskId))}) is written by application code in another
 * package, so if {@code ScheduledTaskManager#executeTask} loses its {@code public} modifier this file stops
 * compiling. The same-package tests would not notice.
 * </p>
 */
@DisplayName("External TaskScheduler wiring")
class ExternalSchedulerWiringTest {

    @Test
    @DisplayName("a ScheduledTaskExecutor built from the manager outside the scheduling package fires the task")
    void externallyWiredExecutorReachesTheTaskManager() {
        final InMemoryScheduledTaskRepository taskRepo = new InMemoryScheduledTaskRepository();
        final InMemoryScheduledTaskExecutionHistoryRepository historyRepo = new InMemoryScheduledTaskExecutionHistoryRepository();

        // The lazy reference breaks the scheduler <-> manager cycle, exactly as the deployment guide prescribes.
        final AtomicReference<ScheduledTaskManager> managerRef = new AtomicReference<>();
        final ScheduledTaskExecutor executor = taskId -> managerRef.get().executeTask(taskId);
        final CapturingTaskScheduler scheduler = new CapturingTaskScheduler(executor);

        final SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(taskRepo)
                .historyRepository(historyRepo).taskScheduler(scheduler).build();
        try {
            managerRef.set(engine.getTaskManager());

            final ScheduledTask task = newTask();
            engine.getTaskManager().register(task);
            assertThat(scheduler.scheduled).containsExactly(task.getId());

            // Simulate the trigger firing on the external scheduler.
            scheduler.fire(task.getId());

            assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).hasSize(1);
            assertThat(taskRepo.findById(task.getId()).orElseThrow().getLastExecutedAt()).isPresent();
        } finally {
            engine.close();
        }
    }

    private static ScheduledTask newTask() {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("nightly").cronExpression("* * * * *")
                .owner(Principal.user("alice")).boundRuntimeId(AgentRuntimeId.fromName("orca"))
                .routine(List.of(RoutineStep.of("Bash", "{}"))).enabled(true).build();
    }

    /**
     * Stands in for {@code QuartzTaskScheduler}: it is handed the executor at construction time and invokes it when a
     * trigger fires.
     */
    private static final class CapturingTaskScheduler implements TaskScheduler {

        private final ScheduledTaskExecutor executor;
        private final List<ScheduledTaskId> scheduled = new ArrayList<>();

        private CapturingTaskScheduler(ScheduledTaskExecutor executor) {
            this.executor = executor;
        }

        void fire(ScheduledTaskId taskId) {
            executor.execute(taskId);
        }

        @Override
        public void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression) {
            scheduled.add(taskId);
        }

        @Override
        public void unschedule(ScheduledTaskId taskId) {
            scheduled.remove(taskId);
        }

        @Override
        public boolean exists(ScheduledTaskId taskId) {
            return scheduled.contains(taskId);
        }

        @Override
        public void clear() {
            scheduled.clear();
        }

        @Override
        public void start() {
        }

        @Override
        public void shutdown() {
        }
    }
}
