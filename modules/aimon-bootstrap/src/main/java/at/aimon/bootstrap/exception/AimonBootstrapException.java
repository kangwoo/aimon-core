package at.aimon.bootstrap.exception;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when assembling an {@code AimonStack} fails.
 *
 * <p>
 * Assembly failures are unrecoverable by design: a partially wired stack has collaborators that reference each
 * other but were never started, so there is no meaningful degraded mode to fall back to. The builder closes
 * whatever it had already registered before rethrowing, attaching any close failure as a suppressed exception.
 */
public class AimonBootstrapException extends AimonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given message.
     *
     * @param message
     *            the detail message
     */
    public AimonBootstrapException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given message and cause.
     *
     * @param message
     *            the detail message
     * @param cause
     *            the underlying failure
     */
    public AimonBootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
