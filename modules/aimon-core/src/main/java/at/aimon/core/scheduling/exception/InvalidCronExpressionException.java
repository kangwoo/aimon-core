/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.exception;

/**
 * Thrown when a cron expression is invalid.
 */
public class InvalidCronExpressionException extends SchedulingException {

    private final String cronExpression;

    /** InvalidCronExpressionException을 생성한다. */
    public InvalidCronExpressionException(String cronExpression, String message) {
        super("Invalid cron expression '" + cronExpression + "': " + message);
        this.cronExpression = cronExpression;
    }

    /** InvalidCronExpressionException을 생성한다. */
    public InvalidCronExpressionException(String cronExpression, Throwable cause) {
        super("Invalid cron expression '" + cronExpression + "': " + cause.getMessage(), cause);
        this.cronExpression = cronExpression;
    }

    public String getCronExpression() {
        return cronExpression;
    }
}
