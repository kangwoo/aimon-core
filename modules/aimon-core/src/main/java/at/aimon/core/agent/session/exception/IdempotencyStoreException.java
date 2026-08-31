package at.aimon.core.agent.session.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when an {@code IdempotencyStore} backend fails to communicate (e.g. Redis unreachable, Postgres connection
 * lost, MongoDB write rejected for transport reasons).
 *
 * <p>
 * Distinct from {@link IdempotencyConflictException}, which signals a logical conflict (same key, different input
 * hash) the manager surfaces to the caller.
 */
public class IdempotencyStoreException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyStoreException(String message) {
        super(message);
    }

    public IdempotencyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
