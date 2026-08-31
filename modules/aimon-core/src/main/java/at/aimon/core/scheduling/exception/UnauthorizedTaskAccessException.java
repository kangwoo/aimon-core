/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.exception;

import at.aimon.core.base.Principal;

/**
 * Thrown when a principal attempts to access a task it doesn't own.
 */
public class UnauthorizedTaskAccessException extends SchedulingException {

    private final String taskId;
    private final Principal principal;

    /** UnauthorizedTaskAccessException을 생성한다. */
    public UnauthorizedTaskAccessException(String taskId, Principal principal) {
        super("Principal " + principal + " is not authorized to access task: " + taskId);
        this.taskId = taskId;
        this.principal = principal;
    }

    public String getTaskId() {
        return taskId;
    }

    public Principal getPrincipal() {
        return principal;
    }
}
