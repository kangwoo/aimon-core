package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineExecutorExecutionAttributesTest.ContextCapturingTool;
import at.aimon.core.scheduling.RoutineExecutorExecutionAttributesTest.ProvidingRuntime;
import at.aimon.core.scheduling.RoutineExecutorExecutionAttributesTest.RecordingProvider;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.quota.DefaultTaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

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
    void tracerReachesTheRoutineExecutor() {
        InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        SchedulingEngine engine = SchedulingEngineBuilder.create().taskScheduler(mock(TaskScheduler.class))
                .tracer(new DefaultTracer(store, SpanExporter.noop())).build();
        try {
            ScheduledTask task = newTask();
            engine.getTaskManager().register(task);

            // The bound runtime is not registered, so the run fails at its first step — still a traced run.
            engine.getTaskManager().executeTask(task.getId());

            List<TraceSpan> spans = store.bySession("routine:" + task.getId().value());
            assertThat(spans).singleElement().satisfies(span -> {
                assertThat(span.getType()).isEqualTo(SpanType.ROUTINE);
                assertThat(span.getStatus()).isEqualTo(SpanStatus.ERROR);
            });
        } finally {
            engine.close();
        }
    }

    @Test
    void routineExecutionAttributesReachTheRoutineExecutor() {
        assertThat(firedRequestAttributes(
                (task, executionId) -> Map.of("environment.sandbox.workspace", "ws-7", "feature.flag", "on")))
                .containsExactly(Map.entry("sandbox.workspace", "ws-7"));
    }

    @Test
    void withoutRoutineExecutionAttributesTheRequestCarriesNone() {
        assertThat(firedRequestAttributes(null)).isEmpty();
    }

    /** Fires one task through an engine built with {@code resolver} and returns what its provider was asked for. */
    private static Map<String, String> firedRequestAttributes(RoutineExecutionAttributes resolver) {
        DefaultAgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
        Agent agent = DefaultAgent.builder().name("orca-attrs").systemPrompt("test").build();
        RecordingProvider provider = new RecordingProvider();
        registry.register(
                new ProvidingRuntime(AgentRuntimeId.from(agent), agent, List.of(new ContextCapturingTool()), provider));
        SchedulingEngineBuilder builder = SchedulingEngineBuilder.create().taskScheduler(mock(TaskScheduler.class))
                .agentRuntimeRegistry(registry);
        if (resolver != null) {
            builder.routineExecutionAttributes(resolver);
        }
        SchedulingEngine engine = builder.build();
        try {
            ScheduledTask task = RoutineExecutorExecutionAttributesTest.task(AgentRuntimeId.from(agent));
            engine.getTaskManager().register(task);
            engine.getTaskManager().executeTask(task.getId());

            assertThat(provider.requests).hasSize(1);
            return provider.requests.get(0).requestAttributes();
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
