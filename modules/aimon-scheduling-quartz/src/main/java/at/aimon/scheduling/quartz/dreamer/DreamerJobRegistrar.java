/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz.dreamer;

import java.util.Objects;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.memory.WorkspaceStore;
import at.aimon.core.memory.dreamer.DreamerEngine;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;
import at.aimon.core.scheduling.exception.TaskSchedulerException;
import at.aimon.scheduling.quartz.cron.QuartzCronTranslator;

/**
 * One-shot wiring helper that publishes {@link WorkspaceStore} and
 * {@link DreamerEngine} into a {@link Scheduler}'s {@code SchedulerContext} and
 * (re)schedules a {@link DreamerJob} per workspace on a cron cadence.
 *
 * <p>
 * The dreamer is a long-lived component (CLAUDE.md §"Scheduling Lifecycle"); a
 * single registrar instance is created at application start, before
 * {@code scheduler.start()}, and lives for the JVM's lifetime. Re-registering
 * with a different workspace simply adds another job; calling
 * {@link #unregister(String)} removes one without touching shared context.
 *
 * <h2>Why context, not JobDataMap, for deps</h2>
 *
 * Quartz serialises {@link org.quartz.JobDataMap JobDataMap} to the JDBC job
 * store; framework objects (LLM client, JDBC pool, etc.) are not serialisable.
 * The {@code SchedulerContext} stays in-memory and is the canonical place to
 * publish per-scheduler dependencies — {@code QuartzTaskScheduler} uses the
 * exact same pattern for its task executor.
 */
public final class DreamerJobRegistrar {

    /** Quartz job group used by {@link DreamerJob}. */
    public static final String JOB_GROUP = "aimon-dreamer";

    /** Quartz trigger group used by {@link DreamerJob}. */
    public static final String TRIGGER_GROUP = "aimon-dreamer-triggers";

    private static final Logger log = LoggerFactory.getLogger(DreamerJobRegistrar.class);

    private final Scheduler scheduler;

    /**
     * Wires the registrar around an existing Quartz scheduler.
     *
     * <p>
     * Publishes {@code workspaceStore} and {@code dreamerEngine} into the
     * scheduler's context immediately, so it does not matter whether the
     * scheduler has already started.
     *
     * @param scheduler
     *            the Quartz scheduler (must not be null)
     * @param workspaceStore
     *            workspace lookup (must not be null)
     * @param dreamerEngine
     *            consolidation engine (must not be null)
     * @throws TaskSchedulerException
     *             if the scheduler context cannot be written
     */
    public DreamerJobRegistrar(Scheduler scheduler, WorkspaceStore workspaceStore, DreamerEngine dreamerEngine) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        Objects.requireNonNull(workspaceStore, "workspaceStore cannot be null");
        Objects.requireNonNull(dreamerEngine, "dreamerEngine cannot be null");
        try {
            scheduler.getContext().put(DreamerJob.CONTEXT_KEY_WORKSPACE_STORE, workspaceStore);
            scheduler.getContext().put(DreamerJob.CONTEXT_KEY_DREAMER_ENGINE, dreamerEngine);
        } catch (SchedulerException e) {
            throw new TaskSchedulerException("Failed to register dreamer dependencies in scheduler context", e);
        }
    }

    /**
     * Schedules — or replaces — the {@link DreamerJob} for {@code workspaceId} on
     * {@code cronExpression}. Existing job/trigger pairs for the same workspace
     * are unscheduled first so the cron can be tuned without leaking duplicates.
     *
     * <p>
     * {@code cronExpression} is the framework's five-field dialect — the same one
     * {@code ScheduledTask} and {@code RewakeTriggerCron} carry — and is translated
     * to Quartz's six-field form by {@link QuartzCronTranslator}. Translation
     * validates as a side effect and runs <i>before</i> {@link #unregister(String)},
     * so a rejected expression leaves the previously scheduled dreamer in place.
     *
     * @param workspaceId
     *            workspace whose dreamer is being scheduled (must not be null or blank)
     * @param cronExpression
     *            five-field cron expression (must not be null)
     * @throws InvalidCronExpressionException
     *             if {@code cronExpression} is not a valid five-field cron, or restricts
     *             both day fields — a union Quartz cannot express
     * @throws TaskSchedulerException
     *             if Quartz fails to register the job
     */
    public void register(String workspaceId, String cronExpression) {
        Objects.requireNonNull(workspaceId, "workspaceId cannot be null");
        Objects.requireNonNull(cronExpression, "cronExpression cannot be null");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId cannot be blank");
        }
        final String quartzCron = QuartzCronTranslator.toQuartz(cronExpression);

        unregister(workspaceId);

        try {
            JobDetail jobDetail = JobBuilder.newJob(DreamerJob.class).withIdentity(workspaceId, JOB_GROUP)
                    .usingJobData(DreamerJob.DATA_KEY_WORKSPACE_ID, workspaceId).storeDurably(false).build();

            Trigger trigger = TriggerBuilder.newTrigger().withIdentity(workspaceId, TRIGGER_GROUP)
                    .withSchedule(
                            CronScheduleBuilder.cronSchedule(quartzCron).withMisfireHandlingInstructionFireAndProceed())
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Scheduled dreamer for workspace='{}' cron='{}' (quartz='{}')", workspaceId, cronExpression,
                    quartzCron);
        } catch (SchedulerException e) {
            throw new TaskSchedulerException("Failed to schedule DreamerJob for workspace " + workspaceId, e);
        }
    }

    /**
     * Removes any {@link DreamerJob} scheduled for {@code workspaceId}. No-op if
     * no job exists for the workspace.
     */
    public void unregister(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId cannot be null");
        try {
            JobKey jobKey = JobKey.jobKey(workspaceId, JOB_GROUP);
            TriggerKey triggerKey = TriggerKey.triggerKey(workspaceId, TRIGGER_GROUP);
            if (scheduler.checkExists(jobKey)) {
                scheduler.unscheduleJob(triggerKey);
                scheduler.deleteJob(jobKey);
                log.info("Unscheduled dreamer for workspace='{}'", workspaceId);
            }
        } catch (SchedulerException e) {
            throw new TaskSchedulerException("Failed to unschedule DreamerJob for workspace " + workspaceId, e);
        }
    }

    /** Returns true if a {@link DreamerJob} is currently scheduled for {@code workspaceId}. */
    public boolean isRegistered(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId cannot be null");
        try {
            return scheduler.checkExists(JobKey.jobKey(workspaceId, JOB_GROUP));
        } catch (SchedulerException e) {
            throw new TaskSchedulerException("Failed to check DreamerJob registration for " + workspaceId, e);
        }
    }
}
