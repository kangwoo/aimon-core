package at.aimon.core.agent.session.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a {@code SessionLeaseStore} backend fails to communicate (e.g. Redis unreachable, network timeout).
 *
 * <p>
 * Indicates a transient backend failure rather than a held-lease condition; "held by another holder" is signaled by an
 * empty {@code Optional} from {@code tryAcquire} — or by {@code ClaimResult.HeldElsewhere} from {@code claim} — never
 * by
 * this exception. Callers (typically the session manager) translate this into a {@code 503 Service Unavailable}.
 *
 * <p>
 * Formerly {@code at.aimon.session.base.exception.ConversationLockException}; it moved with the SPI it belongs to.
 */
public class SessionLeaseException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SessionLeaseException(String message) {
        super(message);
    }

    public SessionLeaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
