/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.TaskSchedulerException;
import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;
import at.aimon.core.scheduling.scheduler.TaskScheduler;
import at.aimon.scheduling.quartz.cron.QuartzCronTranslator;

/**
 * Quartz-based implementation of {@link TaskScheduler}.
 *
 * <p>
 * Provides enterprise-grade scheduling capabilities with support for:
 * <ul>
 * <li>Persistent job storage via JDBC (when configured)</li>
 * <li>Clustering for distributed environments</li>
 * <li>Misfire handling</li>
 * <li>Job recovery after failures</li>
 * </ul>
 *
 * <p>
 * By default, uses RAM-based job store suitable for single-node deployments. For clustered deployments, configure JDBC
 * job store via {@link QuartzTaskSchedulerBuilder}.
 *
 * <h2>Owned and borrowed schedulers</h2>
 *
 * <p>
 * A {@link Scheduler} reaching this class comes from one of two places, and they call for opposite teardown. One built
 * for AIMON — by {@link QuartzTaskSchedulerBuilder}, or handed over outright — is <b>owned</b>: shutting AIMON down
 * shuts it down. One that belongs to the surrounding application — Spring Boot's {@code quartzScheduler} bean, say,
 * which also runs that application's own jobs — is <b>borrowed</b>: shutting it down would stop scheduling for
 * everything else in the process, and {@code shutdown(true)} would additionally block on jobs that are none of AIMON's
 * business. Use {@link #owning(Scheduler, ScheduledTaskExecutor)} or
 * {@link #borrowing(Scheduler, ScheduledTaskExecutor)} to say which.
 *
 * <p>
 * The rule for a borrowed scheduler is symmetric: <b>AIMON neither starts nor stops it.</b> Starting it would be as
 * presumptuous as stopping it, and an application that has deliberately left its scheduler in standby
 * ({@code spring.quartz.auto-startup=false}) means it. The consequence is worth stating plainly: if the application
 * never starts the scheduler, no AIMON cron ever fires, and nothing here will say so — the jobs register successfully
 * and simply wait.
 */
public class QuartzTaskScheduler implements TaskScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzTaskScheduler.class);

    /** Prefix of the derived scheduler names — see {@link #deriveInstanceName()}. */
    private static final String INSTANCE_NAME_PREFIX = "AimonScheduler-";

    private static final AtomicLong INSTANCE_COUNTER = new AtomicLong();

    static final String JOB_GROUP = "aimon-tasks";
    private static final String TRIGGER_GROUP = "aimon-triggers";
    private static final String TASK_KEY = "task";
    static final String EXECUTOR_CONTEXT_KEY = "scheduledTaskExecutor";

    private final Scheduler scheduler;
    private final ScheduledTaskExecutor taskExecutor;
    private final boolean ownsScheduler;
    private final boolean waitForJobsOnShutdown;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a new Quartz scheduler with the provided Quartz Scheduler instance and task executor.
     *
     * <p>
     * The scheduler is taken as <em>owned</em>: {@link #shutdown()} shuts it down and waits for running jobs. For a
     * scheduler that belongs to the surrounding application, use {@link #borrowing(Scheduler, ScheduledTaskExecutor)}.
     *
     * @param scheduler
     *            the Quartz Scheduler instance
     * @param taskExecutor
     *            the executor for running scheduled tasks
     */
    public QuartzTaskScheduler(Scheduler scheduler, ScheduledTaskExecutor taskExecutor) {
        this(scheduler, taskExecutor, true, true);
    }

    private QuartzTaskScheduler(Scheduler scheduler, ScheduledTaskExecutor taskExecutor, boolean ownsScheduler,
            boolean waitForJobsOnShutdown) {
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler cannot be null");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "Task executor cannot be null");
        this.ownsScheduler = ownsScheduler;
        this.waitForJobsOnShutdown = waitForJobsOnShutdown;
    }

    /**
     * Takes ownership of a scheduler: AIMON starts it, and shuts it down waiting for running jobs.
     *
     * @param scheduler
     *            the Quartz Scheduler instance
     * @param taskExecutor
     *            the executor for running scheduled tasks
     * @return the task scheduler
     */
    public static QuartzTaskScheduler owning(Scheduler scheduler, ScheduledTaskExecutor taskExecutor) {
        return new QuartzTaskScheduler(scheduler, taskExecutor, true, true);
    }

    /**
     * Takes ownership of a scheduler, choosing whether shutdown waits for running jobs.
     *
     * <p>
     * Waiting is the safe default and the wrong one where shutdown order matters: a wait here happens before whatever
     * drains incoming requests, so a long-running job holds the whole process open. Passing {@code false} interrupts
     * instead — a job that has not finished is abandoned mid-flight and will simply be retried at its next trigger.
     *
     * @param scheduler
     *            the Quartz Scheduler instance
     * @param taskExecutor
     *            the executor for running scheduled tasks
     * @param waitForJobsOnShutdown
     *            whether {@link #shutdown()} blocks until running jobs finish
     * @return the task scheduler
     */
    public static QuartzTaskScheduler owning(Scheduler scheduler, ScheduledTaskExecutor taskExecutor,
            boolean waitForJobsOnShutdown) {
        return new QuartzTaskScheduler(scheduler, taskExecutor, true, waitForJobsOnShutdown);
    }

    /**
     * Borrows a scheduler that belongs to the surrounding application.
     *
     * <p>
     * AIMON neither starts nor stops it. {@link #start()} only registers the task executor with it, and
     * {@link #shutdown()} removes AIMON's own jobs and unregisters the executor, leaving the scheduler running for
     * whatever else uses it. See the class javadoc for what that implies when the application has not started its
     * scheduler.
     *
     * @param scheduler
     *            the application's Quartz Scheduler
     * @param taskExecutor
     *            the executor for running scheduled tasks
     * @return the task scheduler
     */
    public static QuartzTaskScheduler borrowing(Scheduler scheduler, ScheduledTaskExecutor taskExecutor) {
        return new QuartzTaskScheduler(scheduler, taskExecutor, false, false);
    }

    /**
     * Builds a standalone RAM-backed scheduler with a name no other scheduler in this JVM has.
     *
     * <p>
     * The name is derived rather than fixed for the reason spelled out in {@link QuartzTaskSchedulerBuilder}: Quartz
     * hands back an existing scheduler when one is already registered under the requested name, so a constant here
     * would make every caller in the process share one.
     */
    static Scheduler createDefaultScheduler() {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", deriveInstanceName());
        props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount",
                String.valueOf(Runtime.getRuntime().availableProcessors()));
        props.setProperty("org.quartz.threadPool.threadNamePrefix", "aimon-quartz");
        props.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
        props.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        return createScheduler(props);
    }

    /**
     * Returns a scheduler name not yet used in this JVM.
     *
     * <p>
     * A counter suffices: the repository the name has to be unique within is a static of this class loader, so anything
     * that could collide with this name is counting on the same counter.
     */
    static String deriveInstanceName() {
        return INSTANCE_NAME_PREFIX + INSTANCE_COUNTER.incrementAndGet();
    }

    static Scheduler createScheduler(Properties properties) {
        try {
            StdSchedulerFactory factory = new StdSchedulerFactory(properties);
            return factory.getScheduler();
        } catch (SchedulerException e) {
            throw new TaskSchedulerException("Failed to create Quartz scheduler", e);
        }
    }

    @Override
    public void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(cronExpression, "Cron expression cannot be null");

        if (!running.get()) {
            throw new TaskSchedulerException("Scheduler is not running");
        }

        // A scheduled task's expression is in the framework's five-field dialect and Quartz's parser reads six, so it
        // is translated rather than validated. Validating it here was the bug: the check ran in the wrong dialect and
        // rejected every expression that could reach this backend.
        final String quartzExpression = QuartzCronTranslator.toQuartz(cronExpression);

        // Unschedule existing task if present
        unschedule(taskId);

        final String taskIdValue = taskId.value();

        try {
            JobDetail jobDetail = JobBuilder.newJob(DelegatingJob.class).withIdentity(taskIdValue, JOB_GROUP)
                    .usingJobData(TASK_KEY, taskIdValue).storeDurably(false).build();

            Trigger trigger = TriggerBuilder.newTrigger().withIdentity(taskIdValue, TRIGGER_GROUP).withSchedule(
                    CronScheduleBuilder.cronSchedule(quartzExpression).withMisfireHandlingInstructionFireAndProceed())
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
            // Both forms: the caller can only recognise the one it wrote, and only the other one explains the trigger.
            LOGGER.info("Scheduled task '{}' with cron '{}' (Quartz: '{}')", taskIdValue, cronExpression,
                    quartzExpression);
        } catch (SchedulerException e) {
            throw new TaskSchedulerException("Failed to schedule task: " + taskIdValue, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * This method can be called regardless of the scheduler's running state, allowing cleanup of previously scheduled
     * tasks even when the scheduler is stopped.
     */
    @Override
    public void unschedule(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");

        final String taskIdValue = taskId.value();

        try {
            JobKey jobKey = JobKey.jobKey(taskIdValue, JOB_GROUP);
            TriggerKey triggerKey = TriggerKey.triggerKey(taskIdValue, TRIGGER_GROUP);

            if (scheduler.checkExists(jobKey)) {
                scheduler.unscheduleJob(triggerKey);
                scheduler.deleteJob(jobKey);
                LOGGER.info("Unscheduled task '{}'", taskIdValue);
            }
        } catch (SchedulerException e) {
            throw new TaskSchedulerException("Failed to unschedule task: " + taskIdValue, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * This method can be called regardless of the scheduler's running state, allowing task existence checks even when
     * the scheduler is stopped.
     */
    @Override
    public boolean exists(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");

        final String taskIdValue = taskId.value();

        try {
            return scheduler.checkExists(JobKey.jobKey(taskIdValue, JOB_GROUP));
        } catch (SchedulerException e) {
            throw new TaskSchedulerException("Failed to check task existence: " + taskIdValue, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * This method can be called regardless of the scheduler's running state, allowing cleanup of all tasks even when
     * the scheduler is stopped.
     *
     * <p>
     * On a borrowed scheduler this removes only AIMON's own jobs. Quartz's own {@code clear()} empties the whole
     * scheduler, which on a shared one would silently delete the application's jobs as well.
     */
    @Override
    public void clear() {
        try {
            if (ownsScheduler) {
                scheduler.clear();
            } else {
                deleteAimonJobs();
            }
            LOGGER.info("Cleared all scheduled tasks");
        } catch (SchedulerException e) {
            throw new TaskSchedulerException("Failed to clear scheduler", e);
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            try {
                claimExecutorSlot();
                if (ownsScheduler) {
                    scheduler.start();
                    LOGGER.info("Quartz task scheduler started");
                } else {
                    LOGGER.info("Borrowing Quartz scheduler '{}' — AIMON neither starts nor stops it, so nothing fires"
                            + " unless the application has started it", scheduler.getSchedulerName());
                }
            } catch (SchedulerException e) {
                running.set(false);
                throw new TaskSchedulerException("Failed to start scheduler", e);
            } catch (RuntimeException e) {
                running.set(false);
                throw e;
            }
        }
    }

    @Override
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            try {
                if (ownsScheduler) {
                    scheduler.shutdown(waitForJobsOnShutdown);
                    LOGGER.info("Quartz task scheduler shut down (waited for running jobs: {})", waitForJobsOnShutdown);
                } else {
                    // Leaving the jobs behind would be worse than leaving nothing: their executor is gone, so every
                    // subsequent firing fails and logs, forever, in an application that has merely closed its stack.
                    deleteAimonJobs();
                    scheduler.getContext().remove(EXECUTOR_CONTEXT_KEY);
                    LOGGER.info("Released borrowed Quartz scheduler '{}' — left running for the application",
                            scheduler.getSchedulerName());
                }
            } catch (SchedulerException e) {
                throw new TaskSchedulerException("Failed to shutdown scheduler", e);
            }
        }
    }

    /**
     * Registers the executor this scheduler dispatches to, refusing to displace another stack's.
     *
     * <p>
     * The executor lives in the scheduler context under one key, so two stacks sharing a scheduler would have the
     * second overwrite the first — after which the first stack's tasks run against the second stack's agents. The jobs
     * themselves collide too, in the shared {@link #JOB_GROUP}. Neither shows up as an error; both show up as a task
     * doing the wrong thing. Sharing is refused instead.
     */
    private void claimExecutorSlot() throws SchedulerException {
        final Object present = scheduler.getContext().get(EXECUTOR_CONTEXT_KEY);
        if (present != null && present != taskExecutor) {
            throw new TaskSchedulerException("Quartz scheduler '" + scheduler.getSchedulerName()
                    + "' already dispatches to another AIMON stack's task executor. Two stacks cannot share one"
                    + " scheduler — they would overwrite each other's executor and collide in job group '" + JOB_GROUP
                    + "'. Give each stack its own scheduler.");
        }
        scheduler.getContext().put(EXECUTOR_CONTEXT_KEY, taskExecutor);
    }

    /** Removes every job this scheduler registered, and nothing else. */
    private void deleteAimonJobs() throws SchedulerException {
        scheduler.deleteJobs(new ArrayList<>(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))));
    }

    /**
     * Returns the underlying Quartz Scheduler instance.
     *
     * @return the Quartz Scheduler
     */
    public Scheduler getQuartzScheduler() {
        return scheduler;
    }

    /**
     * Quartz Job implementation that delegates to {@link ScheduledTaskExecutor}.
     */
    public static class DelegatingJob implements Job {

        private static final Logger JOB_LOGGER = LoggerFactory.getLogger(DelegatingJob.class);

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            JobDataMap dataMap = context.getJobDetail().getJobDataMap();
            String taskId = dataMap.getString(TASK_KEY);

            try {
                ScheduledTaskExecutor executor = (ScheduledTaskExecutor) context.getScheduler().getContext()
                        .get(EXECUTOR_CONTEXT_KEY);

                if (executor == null) {
                    JOB_LOGGER.error("Task executor not found in scheduler context");
                    throw new JobExecutionException("Task executor not found");
                }

                JOB_LOGGER.debug("Executing task '{}'", taskId);
                executor.execute(ScheduledTaskId.of(taskId));
            } catch (JobExecutionException e) {
                throw e;
            } catch (SchedulerException e) {
                JOB_LOGGER.error("Failed to execute task '{}': {}", taskId, e.getMessage(), e);
                throw new JobExecutionException("Failed to execute task: " + taskId, e);
            } catch (Exception e) {
                JOB_LOGGER.error("Error executing task '{}': {}", taskId, e.getMessage(), e);
                throw new JobExecutionException("Error executing task: " + taskId, e);
            }
        }
    }
}
