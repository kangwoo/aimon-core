/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.scheduler;

import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.cron.UnixCronExpression;

/**
 * Interface for scheduling and managing recurring tasks.
 *
 * <p>
 * <b>Cron expressions crossing this interface are in one dialect: {@link UnixCronExpression}.</b> An implementation
 * whose engine reads something else translates on the way in; it does not get to define what the caller may write,
 * because the caller — {@code ScheduledTaskManager} — has already accepted and stored the expression by the time it
 * arrives here, and a backend that then rejects it turns a swap of implementations into a change of contract.
 */
public interface TaskScheduler {

    /**
     * Schedules a task to run recurrently based on a cron expression.
     *
     * @param taskId
     *            the unique task identifier
     * @param cronExpression
     *            the schedule, as a five-field {@link UnixCronExpression}
     * @throws at.aimon.core.scheduling.exception.InvalidCronExpressionException
     *             if the expression is not valid in that dialect, or describes a schedule this implementation's engine
     *             cannot express
     */
    void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression);

    /**
     * Removes a scheduled task.
     *
     * @param taskId
     *            the task identifier to unschedule
     */
    void unschedule(ScheduledTaskId taskId);

    /**
     * Checks if a task is currently scheduled.
     *
     * @param taskId
     *            the task identifier
     * @return true if the task is scheduled
     */
    boolean exists(ScheduledTaskId taskId);

    /**
     * Removes all scheduled tasks.
     */
    void clear();

    /**
     * Starts the scheduler.
     */
    void start();

    /**
     * Shuts down the scheduler gracefully.
     */
    void shutdown();
}
