/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import at.aimon.core.scheduling.ScheduledTask;

/**
 * Event published when a task is registered.
 */
public class TaskRegisteredEvent extends ScheduledTaskEvent {

    /** TaskRegisteredEvent를 생성한다. */
    public TaskRegisteredEvent(ScheduledTask task) {
        super(task);
    }
}
