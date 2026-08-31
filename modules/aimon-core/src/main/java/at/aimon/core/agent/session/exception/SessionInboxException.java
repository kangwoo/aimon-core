package at.aimon.core.agent.session.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a {@code SessionInbox} backend fails to communicate (e.g. Redis unreachable, stream operation
 * timeout).
 *
 * <p>
 * Indicates a transient backend failure. The manager catches this and decides whether to retry or surface a
 * user-visible error per design §10.1.
 */
public class SessionInboxException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SessionInboxException(String message) {
        super(message);
    }

    public SessionInboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
