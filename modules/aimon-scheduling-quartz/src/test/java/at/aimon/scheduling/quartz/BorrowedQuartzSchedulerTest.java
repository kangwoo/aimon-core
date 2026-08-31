/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.TaskSchedulerException;
import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;

/**
 * The borrowed half of {@link QuartzTaskScheduler} — a scheduler the application owns and AIMON only registers jobs
 * with.
 *
 * <p>
 * Everything here is about the blast radius of AIMON's own lifecycle calls. The owning path may shut the scheduler
 * down and wipe it, because it built it; on a borrowed one each of those reaches into somebody else's application,
 * and none of the three failures announces itself — a stopped scheduler and one with nothing left to run both look
 * exactly like a quiet afternoon.
 */
class BorrowedQuartzSchedulerTest {

    private static final String HOURLY = "0 * * * *";

    /** A job in a group that is not AIMON's, standing in for whatever the application scheduled first. */
    private static final JobKey APPLICATION_JOB = JobKey.jobKey("nightly-invoices", "billing");

    private Scheduler application;
    private ScheduledTaskExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        application = QuartzTaskScheduler.createDefaultScheduler();
        application.start();
        application.scheduleJob(applicationJob(), applicationTrigger());
        executor = taskId -> {
        };
    }

    @AfterEach
    void tearDown() throws Exception {
        if (application != null && !application.isShutdown()) {
            application.shutdown(false);
        }
    }

    @Test
    @DisplayName("shutdown leaves the application's scheduler running")
    void shutdownLeavesTheApplicationSchedulerRunning() throws Exception {
        final QuartzTaskScheduler borrowed = QuartzTaskScheduler.borrowing(application, executor);
        borrowed.start();

        borrowed.shutdown();

        // The owning path calls scheduler.shutdown(wait) here, which on this scheduler would stop the application's
        // Quartz along with AIMON's use of it — and, with wait=true, block the caller on the application's own jobs.
        assertThat(application.isShutdown()).isFalse();
        assertThat(application.isStarted()).isTrue();
        assertThat(application.checkExists(APPLICATION_JOB)).isTrue();
    }

    @Test
    @DisplayName("shutdown removes AIMON's jobs rather than leaving them to fire against nothing")
    void shutdownRemovesOnlyAimonsJobs() throws Exception {
        final QuartzTaskScheduler borrowed = QuartzTaskScheduler.borrowing(application, executor);
        borrowed.start();
        borrowed.scheduleRecurrently(ScheduledTaskId.of("task-1"), HOURLY);

        borrowed.shutdown();

        // Leaving them would be worse than leaving nothing: the executor they dispatch to is gone with the stack,
        // so every subsequent firing fails and logs, forever, in an application that merely closed a stack.
        assertThat(borrowed.exists(ScheduledTaskId.of("task-1"))).isFalse();
        assertThat(application.checkExists(APPLICATION_JOB)).isTrue();
        assertThat(application.getContext().get(QuartzTaskScheduler.EXECUTOR_CONTEXT_KEY)).isNull();
    }

    @Test
    @DisplayName("clear removes AIMON's jobs and nothing else")
    void clearRemovesOnlyAimonsJobs() throws Exception {
        final QuartzTaskScheduler borrowed = QuartzTaskScheduler.borrowing(application, executor);
        borrowed.start();
        borrowed.scheduleRecurrently(ScheduledTaskId.of("task-1"), HOURLY);

        borrowed.clear();

        // Scheduler.clear() is the whole scheduler, not the caller's share of it. On a borrowed one it silently
        // unschedules the application's work.
        assertThat(borrowed.exists(ScheduledTaskId.of("task-1"))).isFalse();
        assertThat(application.checkExists(APPLICATION_JOB)).isTrue();
    }

    @Test
    @DisplayName("start does not start a scheduler the application is holding in standby")
    void startDoesNotStartASchedulerHeldInStandby() throws Exception {
        application.standby();

        QuartzTaskScheduler.borrowing(application, executor).start();

        // Deciding when its scheduler runs is the application's business, and standby is a decision — an election
        // not yet won, a maintenance window. No AIMON cron fires until the application starts it, which is the
        // documented consequence of borrowing rather than a defect.
        assertThat(application.isInStandbyMode()).isTrue();
    }

    @Test
    @DisplayName("a second stack borrowing the same scheduler is refused")
    void secondBorrowerIsRefused() {
        QuartzTaskScheduler.borrowing(application, executor).start();

        // Both stacks would dispatch through one context key and share one job group, so the second silently takes
        // over the first's firings. There is no arrangement of two stacks over one scheduler that works.
        assertThatThrownBy(() -> QuartzTaskScheduler.borrowing(application, taskId -> {
        }).start()).isInstanceOf(TaskSchedulerException.class).hasMessageContaining("another AIMON stack")
                .hasMessageContaining(QuartzTaskScheduler.JOB_GROUP);
    }

    @Test
    @DisplayName("starting the same scheduler twice is not a second borrower")
    void restartingTheSameSchedulerIsAllowed() throws Exception {
        final QuartzTaskScheduler borrowed = QuartzTaskScheduler.borrowing(application, executor);
        borrowed.start();
        borrowed.shutdown();

        // The guard is about two executors, not two calls. A stack that stopped and started again is the same one.
        borrowed.start();

        assertThat(application.getContext().get(QuartzTaskScheduler.EXECUTOR_CONTEXT_KEY)).isSameAs(executor);
    }

    private static JobDetail applicationJob() {
        return JobBuilder.newJob(NoOpJob.class).withIdentity(APPLICATION_JOB).build();
    }

    private static Trigger applicationTrigger() {
        return TriggerBuilder.newTrigger().withIdentity("nightly-invoices", "billing")
                .withSchedule(SimpleScheduleBuilder.repeatHourlyForever())
                .startAt(Date.from(Instant.now().plus(Duration.ofDays(3650)))).build();
    }

    /** Stands in for the application's own job; it must never run for the assertions above to mean anything. */
    public static class NoOpJob implements Job {

        @Override
        public void execute(JobExecutionContext context) {
        }
    }
}
