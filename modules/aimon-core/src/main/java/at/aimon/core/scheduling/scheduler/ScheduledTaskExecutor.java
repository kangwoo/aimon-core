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
}
