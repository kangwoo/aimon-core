/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.exception;

/**
 * Thrown when a task cannot be found by its ID.
 */
public class TaskNotFoundException extends SchedulingException {

    private final String taskId;

    /** TaskNotFoundException을 생성한다. */
    public TaskNotFoundException(String taskId) {
        super("Task not found: " + taskId);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
