/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.scheduler;

import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.cron.UnixCronExpression;
import at.aimon.core.scheduling.exception.TaskSchedulerException;

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
     * Schedules a task to run recurrently, evaluating the cron expression in the given time zone.
     *
     * <p>
     * {@code "0 9 * * *"} in {@code Asia/Seoul} fires at 09:00 Seoul time whatever zone the JVM runs in, and follows
     * that zone's daylight-saving rules. {@code ScheduledTaskManager} calls this for every task that carries a
     * {@link at.aimon.core.scheduling.ScheduledTask#getTimezone() timezone}, and the two-argument method for one that
     * does not.
     *
     * <p>
     * The default exists so an implementation written before zones reached this interface keeps compiling, and it
     * refuses rather than ignores: for any zone other than the JVM's own it throws, because scheduling a "09:00 Seoul"
     * task at 09:00 of whatever zone the node happens to run in is the silent failure this method was added to end.
     * {@code ScheduledTaskManager.register} then undoes the registration and reports the error. Override it.
     *
     * @param taskId
     *            the unique task identifier
     * @param cronExpression
     *            the schedule, as a five-field {@link UnixCronExpression}
     * @param zone
     *            the zone the expression is evaluated in (must not be null)
     * @throws at.aimon.core.scheduling.exception.InvalidCronExpressionException
     *             as for {@link #scheduleRecurrently(ScheduledTaskId, String)}
     * @throws TaskSchedulerException
     *             if this implementation cannot evaluate the expression in {@code zone}
     */
    default void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression, ZoneId zone) {
        if (!zone.normalized().equals(ZoneId.systemDefault().normalized())) {
            throw new TaskSchedulerException(getClass().getName() + " cannot schedule in time zone " + zone
                    + "; it only evaluates cron in the JVM's zone (" + ZoneId.systemDefault() + ")");
        }
        scheduleRecurrently(taskId, cronExpression);
    }

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
     * Lists the tasks this scheduler currently holds a trigger for.
     *
     * <p>
     * {@link #exists} answers for one task the caller already knows about, which cannot find a trigger whose task the
     * caller has never heard of — the trigger a cancel on another node left behind. This can, and
     * {@code ScheduledTaskManager.reconcile()} uses it to remove such triggers. Empty means the implementation cannot
     * list (the default); reconciliation then only adds missing triggers.
     *
     * <p>
     * An implementation on a scheduler it shares with the application lists only the framework's own triggers, never
     * the application's. On a clustered store that is the cluster's set, which is also what the caller compares it
     * with.
     *
     * @return the ids of the scheduled tasks, or empty if this scheduler cannot list them
     */
    default Optional<Set<ScheduledTaskId>> scheduledTaskIds() {
        return Optional.empty();
    }

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

    /**
     * Whether the triggers this scheduler holds are shared by every node of the deployment.
     *
     * <p>
     * {@code false} — the default, and the answer of the in-memory scheduler and of Quartz on a RAM or unclustered job
     * store — means each node holds its own triggers and fires them itself. That decides what a node may do with a
     * task repository several nodes share: rebuilding every stored task's trigger on each of them makes each of them
     * fire it. {@code true} means a trigger exists once for the whole deployment and one node fires it.
     *
     * @return whether one trigger here is one trigger for the whole deployment
     */
    default boolean isClusterWide() {
        return false;
    }
}
