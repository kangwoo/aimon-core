package at.aimon.core.agent.session.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a submit request reuses an {@code idempotencyKey} but its input hash differs from the previously stored
 * entry.
 *
 * <p>
 * Per design §9.2, this is treated as a client bug. The manager surfaces it as an HTTP 409 Conflict in the typical web
 * adapter integration.
 */
public class IdempotencyConflictException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey, String message) {
        super(message);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
