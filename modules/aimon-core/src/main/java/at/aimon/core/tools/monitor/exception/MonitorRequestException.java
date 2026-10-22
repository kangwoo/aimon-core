package at.aimon.core.tools.monitor.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * A monitor could not be started or found as asked. The message is written for the model, which reads it as the
 * {@code Monitor} tool's error.
 */
public class MonitorRequestException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message
     *            why, for the model
     */
    public MonitorRequestException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message
     *            why, for the model
     * @param cause
     *            the underlying failure
     */
    public MonitorRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
