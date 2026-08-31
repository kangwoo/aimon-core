/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.scheduling.ScheduledTask;

/**
 * Base class for scheduled task events.
 */
public abstract class ScheduledTaskEvent {

    private final ScheduledTask task;
    private final Instant timestamp;

    protected ScheduledTaskEvent(ScheduledTask task) {
        this.task = Objects.requireNonNull(task, "Task cannot be null");
        timestamp = Instant.now();
    }

    /**
     * Returns the task associated with this event.
     *
     * @return the scheduled task
     */
    public ScheduledTask getTask() {
        return task;
    }

    /**
     * Returns the timestamp when this event was created.
     *
     * @return the event timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
}
