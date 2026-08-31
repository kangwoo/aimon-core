/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Base exception for scheduling-related errors.
 *
 * <p>
 * Extends {@link AimonException} for a unified exception hierarchy.
 */
public class SchedulingException extends AimonException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** SchedulingException을 생성한다. */
    public SchedulingException(String message) {
        super(message);
    }

    /** SchedulingException을 생성한다. */
    public SchedulingException(String message, Throwable cause) {
        super(message, cause);
    }
}
