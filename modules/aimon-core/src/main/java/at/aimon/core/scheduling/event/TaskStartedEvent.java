/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import at.aimon.core.scheduling.ScheduledTask;

/**
 * Event published when a task execution starts.
 */
public class TaskStartedEvent extends ScheduledTaskEvent {

    /** TaskStartedEvent를 생성한다. */
    public TaskStartedEvent(ScheduledTask task) {
        super(task);
    }
}
