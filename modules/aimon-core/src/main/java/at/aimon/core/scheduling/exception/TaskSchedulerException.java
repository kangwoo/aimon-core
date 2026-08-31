/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.exception;

/**
 * Thrown when a scheduler operation fails.
 */
public class TaskSchedulerException extends SchedulingException {

    private final String taskId;

    /** TaskSchedulerException을 생성한다. */
    public TaskSchedulerException(String message) {
        super(message);
        taskId = null;
    }

    /** TaskSchedulerException을 생성한다. */
    public TaskSchedulerException(String message, Throwable cause) {
        super(message, cause);
        taskId = null;
    }

    /** 태스크 ID와 메시지로 TaskSchedulerException을 생성한다. */
    public TaskSchedulerException(String taskId, String message) {
        super("Scheduler error for task '" + taskId + "': " + message);
        this.taskId = taskId;
    }

    /** 태스크 ID, 메시지, 원인으로 TaskSchedulerException을 생성한다. */
    public TaskSchedulerException(String taskId, String message, Throwable cause) {
        super("Scheduler error for task '" + taskId + "': " + message, cause);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
