package at.aimon.core.subagent.exception;

import at.aimon.core.base.exception.AimonException;

/**
 * Base exception for all subagent-related errors.
 *
 * <p>
 * This is the parent exception for all subagent system errors.
 */
public class SubagentException extends AimonException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new SubagentException with the given message.
     *
     * @param message
     *            The error message
     */
    public SubagentException(String message) {
        super(message);
    }

    /**
     * Creates a new SubagentException with the given message and cause.
     *
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause
     */
    public SubagentException(String message, Throwable cause) {
        super(message, cause);
    }
}
