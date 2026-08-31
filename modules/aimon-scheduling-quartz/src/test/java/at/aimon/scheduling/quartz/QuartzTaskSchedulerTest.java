/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;

import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;
import at.aimon.core.scheduling.exception.TaskSchedulerException;
import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;

class QuartzTaskSchedulerTest {

    /**
     * Every cron literal here is in the five-field dialect this scheduler's callers write, because that is the dialect
     * the interface promises. This file used to hold Quartz expressions throughout — which is how the mismatch
     * survived:
     * the backend was only ever tested with input no caller could produce.
     */
    private static final String HOURLY = "0 * * * *";

    private static final String HALF_PAST = "30 * * * *";

    private static final String EVERY_MINUTE = "* * * * *";

    private QuartzTaskScheduler scheduler;
    private AtomicReference<ScheduledTaskId> lastExecutedTaskId;

    @BeforeEach
    void setUp() {
        lastExecutedTaskId = new AtomicReference<>();
        ScheduledTaskExecutor executor = taskId -> lastExecutedTaskId.set(taskId);
        scheduler = new QuartzTaskScheduler(QuartzTaskScheduler.createDefaultScheduler(), executor);
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void testScheduleRecurrently_ValidCron_SchedulesTask() {
        // Arrange
        ScheduledTaskId taskId = ScheduledTaskId.of("test-task");

        // Act
        scheduler.scheduleRecurrently(taskId, HOURLY);

        // Assert
        assertThat(scheduler.exists(taskId)).isTrue();
    }

    @Test
    void testScheduleRecurrently_InvalidCron_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> scheduler.scheduleRecurrently(ScheduledTaskId.of("test-task"), "invalid"))
                .isInstanceOf(InvalidCronExpressionException.class);
    }

    @Test
    void testScheduleRecurrently_QuartzDialectExpression_IsRejected() {
        // The backend's own dialect is not the interface's. Accepting six fields here would mean the same string means
        // two different schedules depending on which scheduler is installed.
        assertThatThrownBy(() -> scheduler.scheduleRecurrently(ScheduledTaskId.of("test-task"), "0 0 12 * * ?"))
                .isInstanceOf(InvalidCronExpressionException.class);
    }

    @Test
    void testScheduleRecurrently_TranslatesTheExpressionForQuartz() throws Exception {
        // Arrange
        ScheduledTaskId taskId = ScheduledTaskId.of("weekday-task");

        // Act — weekdays at 09:30, written the way a scheduled task stores it
        scheduler.scheduleRecurrently(taskId, "30 9 * * MON-FRI");

        // Assert: the trigger Quartz actually holds is the translated form — seconds prepended, day-of-week renumbered
        // off Sunday=1, and the unused day field blanked with '?'.
        CronTrigger trigger = (CronTrigger) scheduler.getQuartzScheduler()
                .getTrigger(TriggerKey.triggerKey("weekday-task", "aimon-triggers"));
        assertThat(trigger.getCronExpression()).isEqualTo("0 30 9 ? * 2,3,4,5,6");
    }

    @Test
    void testScheduleRecurrently_BothDayFieldsRestricted_ThrowsException() {
        // "the 15th, and every Monday" — a union Quartz cannot express either way round, so it is refused rather than
        // half-translated.
        assertThatThrownBy(() -> scheduler.scheduleRecurrently(ScheduledTaskId.of("test-task"), "0 0 15 * MON"))
                .isInstanceOf(InvalidCronExpressionException.class).hasMessageContaining("separate tasks");
    }

    @Test
    void testScheduleRecurrently_NullTaskId_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> scheduler.scheduleRecurrently(null, HOURLY)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Task ID cannot be null");
    }

    @Test
    void testScheduleRecurrently_NullCronExpression_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> scheduler.scheduleRecurrently(ScheduledTaskId.of("test-task"), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Cron expression cannot be null");
    }

    @Test
    void testScheduleRecurrently_SchedulerNotRunning_ThrowsException() {
        // Arrange
        scheduler.shutdown();

        // Act & Assert
        assertThatThrownBy(() -> scheduler.scheduleRecurrently(ScheduledTaskId.of("test-task"), HOURLY))
                .isInstanceOf(TaskSchedulerException.class).hasMessageContaining("Scheduler is not running");
    }

    @Test
    void testScheduleRecurrently_ReschedulesExistingTask() {
        // Arrange
        ScheduledTaskId taskId = ScheduledTaskId.of("test-task");
        scheduler.scheduleRecurrently(taskId, HOURLY);

        // Act
        scheduler.scheduleRecurrently(taskId, HALF_PAST);

        // Assert
        assertThat(scheduler.exists(taskId)).isTrue();
    }

    @Test
    void testUnschedule_ExistingTask_RemovesTask() {
        // Arrange
        ScheduledTaskId taskId = ScheduledTaskId.of("test-task");
        scheduler.scheduleRecurrently(taskId, HOURLY);
        assertThat(scheduler.exists(taskId)).isTrue();

        // Act
        scheduler.unschedule(taskId);

        // Assert
        assertThat(scheduler.exists(taskId)).isFalse();
    }

    @Test
    void testUnschedule_NonExistingTask_NoException() {
        // Act & Assert - should not throw
        scheduler.unschedule(ScheduledTaskId.of("non-existing-task"));
    }

    @Test
    void testExists_ExistingTask_ReturnsTrue() {
        // Arrange
        ScheduledTaskId taskId = ScheduledTaskId.of("test-task");
        scheduler.scheduleRecurrently(taskId, HOURLY);

        // Act & Assert
        assertThat(scheduler.exists(taskId)).isTrue();
    }

    @Test
    void testExists_NonExistingTask_ReturnsFalse() {
        // Act & Assert
        assertThat(scheduler.exists(ScheduledTaskId.of("non-existing-task"))).isFalse();
    }

    @Test
    void testClear_RemovesAllTasks() {
        // Arrange
        ScheduledTaskId taskId1 = ScheduledTaskId.of("task-1");
        ScheduledTaskId taskId2 = ScheduledTaskId.of("task-2");
        scheduler.scheduleRecurrently(taskId1, HOURLY);
        scheduler.scheduleRecurrently(taskId2, HALF_PAST);
        assertThat(scheduler.exists(taskId1)).isTrue();
        assertThat(scheduler.exists(taskId2)).isTrue();

        // Act
        scheduler.clear();

        // Assert
        assertThat(scheduler.exists(taskId1)).isFalse();
        assertThat(scheduler.exists(taskId2)).isFalse();
    }

    @Test
    void testStart_AlreadyRunning_NoEffect() {
        // Act - should not throw
        scheduler.start();
        scheduler.start();

        // Assert
        assertThat(scheduler.exists(ScheduledTaskId.of("any"))).isFalse(); // scheduler is still functional
    }

    @Test
    void testShutdown_AlreadyShutdown_NoEffect() {
        // Act - should not throw
        scheduler.shutdown();
        scheduler.shutdown();
    }

    @Test
    void testTaskExecution_RunsTask() throws Exception {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        scheduler.shutdown();

        ScheduledTaskExecutor executor = taskId -> latch.countDown();
        scheduler = new QuartzTaskScheduler(QuartzTaskScheduler.createDefaultScheduler(), executor);
        scheduler.start();

        scheduler.scheduleRecurrently(ScheduledTaskId.of("fast-task"), EVERY_MINUTE);

        // Act — fire the scheduled job now rather than waiting for its tick. The five-field dialect has no seconds
        // field, so a minute is the shortest schedule it can express, and the point being tested is the wiring from a
        // scheduled job to the executor, not the clock.
        scheduler.getQuartzScheduler().triggerJob(JobKey.jobKey("fast-task", QuartzTaskScheduler.JOB_GROUP));

        // Assert
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
    }

    @Test
    void testGetQuartzScheduler_ReturnsScheduler() {
        // Act & Assert
        assertThat(scheduler.getQuartzScheduler()).isNotNull();
    }

    @Test
    void testTaskExecutor_ExecutesWithCorrectTaskId() throws Exception {
        // Arrange
        scheduler.shutdown();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ScheduledTaskId> executedTaskId = new AtomicReference<>();
        ScheduledTaskExecutor executor = taskId -> {
            executedTaskId.set(taskId);
            latch.countDown();
        };

        scheduler = new QuartzTaskScheduler(QuartzTaskScheduler.createDefaultScheduler(), executor);
        scheduler.start();

        // Schedule with a specific task ID
        scheduler.scheduleRecurrently(ScheduledTaskId.of("executor-task"), EVERY_MINUTE);

        // Act
        scheduler.getQuartzScheduler().triggerJob(JobKey.jobKey("executor-task", QuartzTaskScheduler.JOB_GROUP));

        // Assert
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(executedTaskId.get()).isEqualTo(ScheduledTaskId.of("executor-task"));
    }

    @Nested
    class DelegatingJobTest {

        @Test
        void testExecute_NullExecutor_ThrowsJobExecutionException() throws SchedulerException {
            // Arrange
            QuartzTaskScheduler.DelegatingJob job = new QuartzTaskScheduler.DelegatingJob();

            JobDataMap dataMap = new JobDataMap();
            dataMap.put("task", "test-task");

            JobDetail jobDetail = mock(JobDetail.class);
            when(jobDetail.getJobDataMap()).thenReturn(dataMap);

            SchedulerContext schedulerContext = new SchedulerContext();
            // Executor is intentionally not set in context

            Scheduler mockScheduler = mock(Scheduler.class);
            when(mockScheduler.getContext()).thenReturn(schedulerContext);

            JobExecutionContext jobContext = mock(JobExecutionContext.class);
            when(jobContext.getJobDetail()).thenReturn(jobDetail);
            when(jobContext.getScheduler()).thenReturn(mockScheduler);

            // Act & Assert
            assertThatThrownBy(() -> job.execute(jobContext)).isInstanceOf(JobExecutionException.class)
                    .hasMessageContaining("Task executor not found");
        }

        @Test
        void testExecute_WithExecutor_DelegatesToExecutor() throws Exception {
            // Arrange
            QuartzTaskScheduler.DelegatingJob job = new QuartzTaskScheduler.DelegatingJob();

            JobDataMap dataMap = new JobDataMap();
            dataMap.put("task", "delegated-task");

            JobDetail jobDetail = mock(JobDetail.class);
            when(jobDetail.getJobDataMap()).thenReturn(dataMap);

            AtomicReference<ScheduledTaskId> executedId = new AtomicReference<>();
            ScheduledTaskExecutor executor = executedId::set;

            SchedulerContext schedulerContext = new SchedulerContext();
            schedulerContext.put(QuartzTaskScheduler.EXECUTOR_CONTEXT_KEY, executor);

            Scheduler mockScheduler = mock(Scheduler.class);
            when(mockScheduler.getContext()).thenReturn(schedulerContext);

            JobExecutionContext jobContext = mock(JobExecutionContext.class);
            when(jobContext.getJobDetail()).thenReturn(jobDetail);
            when(jobContext.getScheduler()).thenReturn(mockScheduler);

            // Act
            job.execute(jobContext);

            // Assert
            assertThat(executedId.get()).isEqualTo(ScheduledTaskId.of("delegated-task"));
        }

        @Test
        void testExecute_ExecutorThrowsException_WrapsInJobExecutionException() throws Exception {
            // Arrange
            QuartzTaskScheduler.DelegatingJob job = new QuartzTaskScheduler.DelegatingJob();

            JobDataMap dataMap = new JobDataMap();
            dataMap.put("task", "failing-task");

            JobDetail jobDetail = mock(JobDetail.class);
            when(jobDetail.getJobDataMap()).thenReturn(dataMap);

            ScheduledTaskExecutor executor = taskId -> {
                throw new RuntimeException("Execution failed");
            };

            SchedulerContext schedulerContext = new SchedulerContext();
            schedulerContext.put(QuartzTaskScheduler.EXECUTOR_CONTEXT_KEY, executor);

            Scheduler mockScheduler = mock(Scheduler.class);
            when(mockScheduler.getContext()).thenReturn(schedulerContext);

            JobExecutionContext jobContext = mock(JobExecutionContext.class);
            when(jobContext.getJobDetail()).thenReturn(jobDetail);
            when(jobContext.getScheduler()).thenReturn(mockScheduler);

            // Act & Assert
            assertThatThrownBy(() -> job.execute(jobContext)).isInstanceOf(JobExecutionException.class)
                    .hasMessageContaining("Error executing task: failing-task");
        }
    }
}
