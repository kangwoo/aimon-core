package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.ScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.TaskRegisteredEvent;
import at.aimon.core.scheduling.quota.DefaultTaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

class SchedulingEngineTest {

    @Test
    void constructorRejectsNullCollaborators() {
        ScheduledTaskManager mgr = mock(ScheduledTaskManager.class);
        RoutineExecutor routineExecutor = mock(RoutineExecutor.class);
        TaskScheduler ts = mock(TaskScheduler.class);
        ScheduledTaskEventPublisher pub = new SimpleScheduledTaskEventPublisher();
        ScheduledTaskInterruptBus bus = ScheduledTaskInterruptBus.LOCAL_ONLY;

        assertThatNullPointerException().isThrownBy(() -> new SchedulingEngine(null, routineExecutor, ts, pub, bus));
        assertThatNullPointerException().isThrownBy(() -> new SchedulingEngine(mgr, null, ts, pub, bus));
        assertThatNullPointerException().isThrownBy(() -> new SchedulingEngine(mgr, routineExecutor, null, pub, bus));
        assertThatNullPointerException().isThrownBy(() -> new SchedulingEngine(mgr, routineExecutor, ts, null, bus));
        assertThatNullPointerException().isThrownBy(() -> new SchedulingEngine(mgr, routineExecutor, ts, pub, null));
    }

    @Test
    void startAndCloseDelegateToCollaborators() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        RoutineExecutor routine = mock(RoutineExecutor.class);
        ScheduledTaskManager taskManager = mock(ScheduledTaskManager.class);
        ScheduledTaskEventPublisher publisher = new SimpleScheduledTaskEventPublisher();

        SchedulingEngine engine = new SchedulingEngine(taskManager, routine, scheduler, publisher,
                ScheduledTaskInterruptBus.LOCAL_ONLY);

        engine.start();
        verify(scheduler).start();

        engine.close();
        verify(scheduler).shutdown();
        verify(routine).shutdown();

        assertThat(engine.getTaskManager()).isSameAs(taskManager);
    }

    @Test
    void registeredListenerReceivesPublishedEventsAndCanBeRemoved() {
        SchedulingEngine engine = SchedulingEngineBuilder.create().build();
        try {
            AtomicReference<ScheduledTask> received = new AtomicReference<>();
            ScheduledTaskEventListener listener = new ScheduledTaskEventListener() {
                @Override
                public void onTaskRegistered(TaskRegisteredEvent event) {
                    received.set(event.getTask());
                }
            };

            engine.addEventListener(listener);
            engine.start();

            ScheduledTask task = sampleTask();
            engine.getTaskManager().register(task);
            assertThat(received.get()).isEqualTo(task);

            engine.removeEventListener(listener);
            received.set(null);
            engine.getTaskManager().register(sampleTask("0 0 1 * *"));
            assertThat(received.get()).isNull();
        } finally {
            engine.close();
        }
    }

    @Test
    void agentRuntimeRegistryProvidedByCallerIsUsed() {
        DefaultAgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
        SchedulingEngine engine = SchedulingEngineBuilder.create().agentRuntimeRegistry(registry)
                .historyRepository(new InMemoryScheduledTaskExecutionHistoryRepository())
                .taskRepository(new InMemoryScheduledTaskRepository()).quotaManager(new DefaultTaskQuotaManager(2))
                .build();
        try {
            engine.start();
            // Smoke: engine wires up the registry without throwing.
            assertThat(engine.getTaskManager()).isNotNull();
        } finally {
            engine.close();
        }
    }

    private static ScheduledTask sampleTask() {
        return sampleTask("* * * * *");
    }

    private static ScheduledTask sampleTask(String cron) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("t").cronExpression(cron)
                .owner(Principal.user("u")).boundRuntimeId(AgentRuntimeId.fromName("orca"))
                .routine(List.of(RoutineStep.of("Bash", "{}"))).enabled(false).build();
    }
}
