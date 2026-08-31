/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import java.util.Objects;

import at.aimon.core.scheduling.RoutineResult;
import at.aimon.core.scheduling.ScheduledTask;

/**
 * Event published when a task execution fails.
 */
public class TaskFailedEvent extends ScheduledTaskEvent {

    private final RoutineResult result;

    /** TaskFailedEvent를 생성한다. */
    public TaskFailedEvent(ScheduledTask task, RoutineResult result) {
        super(task);
        this.result = Objects.requireNonNull(result, "Result cannot be null");
    }

    /**
     * Returns the routine execution result.
     *
     * @return the routine result
     */
    public RoutineResult getResult() {
        return result;
    }
}
