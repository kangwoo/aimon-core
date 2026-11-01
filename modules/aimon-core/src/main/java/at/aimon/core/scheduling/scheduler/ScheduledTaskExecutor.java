/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.scheduler;

import at.aimon.core.scheduling.ScheduledTaskId;

/**
 * Functional interface for executing scheduled tasks by their ID.
 *
 * <p>
 * This abstraction decouples the scheduler from the task execution logic, allowing the scheduler to trigger tasks
 * without needing to hold references to {@link Runnable} instances.
 * </p>
 *
 * <p>
 * <b>Schedulers call {@link #execute(ScheduledFire)}</b>, which carries the fire's scheduled time and cron slot so a
 * distributed {@code ScheduledExecutionGuard} can tell one occurrence from the next. A lambda implements only
 * {@link #execute(ScheduledTaskId)}, and the default bridge then drops what the fire carried — so wire the manager with
 * {@code ScheduledTaskManager#asExecutor()} rather than {@code taskId -> manager.executeTask(taskId)}.
 * </p>
 */
@FunctionalInterface
public interface ScheduledTaskExecutor {

    /**
     * Executes the scheduled task identified by the given task ID.
     *
     * @param taskId
     *            the unique identifier of the task to execute
     */
    void execute(ScheduledTaskId taskId);

    /**
     * Executes one fire of a scheduled task. The default runs {@link #execute(ScheduledTaskId)} and so drops the fire's
     * time and slot; an executor that can use them overrides this.
     *
     * @param fire
     *            the fire (must not be null)
     */
    default void execute(ScheduledFire fire) {
        execute(fire.getTaskId());
    }
}
