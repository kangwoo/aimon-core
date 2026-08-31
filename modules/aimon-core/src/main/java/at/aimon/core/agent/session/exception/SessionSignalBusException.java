package at.aimon.core.agent.session.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a {@code SessionSignalBus} backend fails to communicate (e.g. Redis pub/sub channel closed,
 * MongoDB change-stream cursor errored, Postgres LISTEN connection lost).
 *
 * <p>
 * Indicates a transient backend failure rather than a closed-bus condition; "bus is closed" remains an
 * {@link IllegalStateException} so callers can distinguish lifecycle errors from backend errors.
 */
public class SessionSignalBusException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SessionSignalBusException(String message) {
        super(message);
    }

    public SessionSignalBusException(String message, Throwable cause) {
        super(message, cause);
    }
}
