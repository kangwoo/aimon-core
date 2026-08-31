package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.quota.DefaultTaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

class SchedulingEngineBuilderTest {

    @Test
    void defaultBuildProducesUsableEngine() {
        SchedulingEngine engine = SchedulingEngineBuilder.create().build();
        try {
            assertThat(engine.getTaskManager()).isNotNull();
        } finally {
            engine.close();
        }
    }

    @Test
    void defaultMaxQuotaRejectsNonPositive() {
        SchedulingEngineBuilder b = SchedulingEngineBuilder.create();
        assertThatIllegalArgumentException().isThrownBy(() -> b.defaultMaxQuota(0));
        assertThatIllegalArgumentException().isThrownBy(() -> b.defaultMaxQuota(-3));
    }

    @Test
    void customDependenciesAreUsedWhenProvided() {
        TaskScheduler scheduler = mock(TaskScheduler.class);

        SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(new InMemoryScheduledTaskRepository())
                .historyRepository(new InMemoryScheduledTaskExecutionHistoryRepository())
                .eventPublisher(new SimpleScheduledTaskEventPublisher()).quotaManager(new DefaultTaskQuotaManager(5))
                .agentRuntimeRegistry(new DefaultAgentRuntimeRegistry()).taskScheduler(scheduler).build();

        // start() forwards to the scheduler
        engine.start();
        verify(scheduler).start();

        engine.close();
        verify(scheduler).shutdown();
    }

    @Test
    void buildWithoutSchedulerCreatesInternalSchedulerLinkedToTaskManager() {
        SchedulingEngine engine = SchedulingEngineBuilder.create().defaultMaxQuota(3).build();
        try {
            engine.start();
            // Engine has its own scheduler; closing should be idempotent and not throw.
            engine.close();
        } finally {
            // close again to ensure no double-close failure
            engine.close();
        }
    }

    @Test
    void injectedExecutionGuardIsConsultedBeforeEachFire() {
        InMemoryScheduledTaskRepository taskRepo = new InMemoryScheduledTaskRepository();
        InMemoryScheduledTaskExecutionHistoryRepository historyRepo = new InMemoryScheduledTaskExecutionHistoryRepository();

        // Stands in for a distributed guard that has granted this cron time to another node.
        List<ScheduledTaskId> asked = new ArrayList<>();
        ScheduledExecutionGuard denyingGuard = taskId -> {
            asked.add(taskId);
            return Optional.empty();
        };

        SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(taskRepo)
                .historyRepository(historyRepo).taskScheduler(mock(TaskScheduler.class)).executionGuard(denyingGuard)
                .build();
        try {
            ScheduledTask task = newTask();
            engine.getTaskManager().register(task);

            engine.getTaskManager().executeTask(task.getId());

            assertThat(asked).containsExactly(task.getId());
            assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).isEmpty();
        } finally {
            engine.close();
        }
    }

    @Test
    void defaultExecutionGuardStillLetsAFireThrough() {
        InMemoryScheduledTaskRepository taskRepo = new InMemoryScheduledTaskRepository();
        InMemoryScheduledTaskExecutionHistoryRepository historyRepo = new InMemoryScheduledTaskExecutionHistoryRepository();

        SchedulingEngine engine = SchedulingEngineBuilder.create().taskRepository(taskRepo)
                .historyRepository(historyRepo).taskScheduler(mock(TaskScheduler.class)).build();
        try {
            ScheduledTask task = newTask();
            engine.getTaskManager().register(task);

            engine.getTaskManager().executeTask(task.getId());

            assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).hasSize(1);
        } finally {
            engine.close();
        }
    }

    @Test
    void engineNeverCallsStartOnNullCustomSchedulerWhenNotStarted() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        SchedulingEngine engine = SchedulingEngineBuilder.create().taskScheduler(scheduler).build();
        try {
            verify(scheduler, never()).start();
        } finally {
            engine.close();
        }
    }

    private static ScheduledTask newTask() {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("nightly").cronExpression("* * * * *")
                .owner(Principal.user("alice")).boundRuntimeId(AgentRuntimeId.fromName("orca"))
                .routine(List.of(RoutineStep.of("Bash", "{}"))).enabled(true).build();
    }
}
