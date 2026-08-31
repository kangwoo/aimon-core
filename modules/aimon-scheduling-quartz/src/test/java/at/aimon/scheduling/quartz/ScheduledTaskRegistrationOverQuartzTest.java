/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineExecutor;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;
import at.aimon.core.scheduling.quota.DefaultTaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;

/**
 * The manager and this backend, wired together the way an application wires them.
 *
 * <p>
 * Worth its own file because the failure it guards against needed exactly this pair to appear. Each half validated cron
 * expressions and each half was self-consistent, but they validated in different dialects whose accepted sets did not
 * overlap — so the backend rejected every task the manager had just accepted, stored and quota-charged. Neither
 * component's own tests could see it.
 */
@DisplayName("Registering a scheduled task against the Quartz backend")
class ScheduledTaskRegistrationOverQuartzTest {

    private static final Principal OWNER = Principal.system();

    private QuartzTaskScheduler taskScheduler;

    private DefaultTaskQuotaManager quotaManager;

    private ScheduledTaskManager taskManager;

    @BeforeEach
    void setUp() {
        taskScheduler = QuartzTaskSchedulerBuilder.create().taskExecutor(taskId -> {
        }).build();
        taskScheduler.start();

        quotaManager = new DefaultTaskQuotaManager(100);
        taskManager = new ScheduledTaskManager(new InMemoryScheduledTaskRepository(),
                new InMemoryScheduledTaskExecutionHistoryRepository(), mock(RoutineExecutor.class), taskScheduler,
                new SimpleScheduledTaskEventPublisher(), quotaManager);
    }

    @AfterEach
    void tearDown() {
        taskScheduler.shutdown();
    }

    @ParameterizedTest(name = "[{0}] reaches the scheduler")
    @ValueSource(strings = {"*/5 * * * *", "0 0 * * *", "30 9 * * MON-FRI", "0 3 1 * *", "0 0 * * 0", "0 0 * * 7",
            "0 0 * * 0-7", "15,45 * * * *", "0 */6 * * *"})
    void succeeds(String cronExpression) {
        final ScheduledTask task = task(cronExpression);

        final ScheduledTask registered = taskManager.register(task);

        assertThat(registered.getCronExpression()).isEqualTo(cronExpression);
        assertThat(taskScheduler.exists(task.getId())).isTrue();
    }

    @Test
    @DisplayName("stores the expression as it was written, not as Quartz needed it")
    void storesTheExpressionAsWritten() {
        // The translated form belongs to the backend. Writing it back would make the stored schedule change meaning if
        // the backend were ever swapped.
        final ScheduledTask registered = taskManager.register(task("30 9 * * MON-FRI"));

        assertThat(taskManager.getById(registered.getId(), OWNER).getCronExpression()).isEqualTo("30 9 * * MON-FRI");
    }

    @Test
    @DisplayName("rejects an expression the backend cannot express")
    void rejectsWhatTheBackendCannotExpress() {
        // "the 15th, and every Monday" is a legal schedule that Quartz has no shape for, so it passes the manager's
        // validation and is refused one step later. The rejection still has to reach the caller as the same failure a
        // malformed expression would produce.
        assertThatThrownBy(() -> taskManager.register(task("0 0 15 * MON")))
                .isInstanceOf(InvalidCronExpressionException.class);
    }

    @Test
    @DisplayName("leaves nothing behind when the backend refuses")
    void leavesNothingBehindWhenTheBackendRefuses() {
        // The refusal arrives after the task has been stored and the owner's quota charged. Both have to be undone —
        // otherwise the caller is told registration failed while holding a task that exists and will never fire.
        final ScheduledTask refused = task("0 0 15 * MON");

        assertThatThrownBy(() -> taskManager.register(refused)).isInstanceOf(InvalidCronExpressionException.class);

        assertThat(taskManager.listByOwner(OWNER)).isEmpty();
        assertThat(quotaManager.getCurrentUsage(OWNER)).isZero();
        assertThat(taskScheduler.exists(refused.getId())).isFalse();
    }

    private static ScheduledTask task(String cronExpression) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("task-" + cronExpression.hashCode())
                .cronExpression(cronExpression).routine(List.of(RoutineStep.of("Read", "{}"))).owner(OWNER)
                .boundRuntimeId(AgentRuntimeId.fromName("test-agent")).enabled(true).build();
    }
}
