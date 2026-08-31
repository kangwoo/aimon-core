/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import at.aimon.core.scheduling.ScheduledTask;

/**
 * Event published when a task is cancelled.
 */
public class TaskCancelledEvent extends ScheduledTaskEvent {

    /** TaskCancelledEvent를 생성한다. */
    public TaskCancelledEvent(ScheduledTask task) {
        super(task);
    }
}
