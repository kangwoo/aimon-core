package at.aimon.core.agent.session.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown by the fenced record view from {@code SessionStore#records()} when a write is attempted for a session this
 * node does not hold.
 *
 * <p>
 * Two distinct situations produce it, and the caller cannot always tell them apart:
 *
 * <ul>
 * <li><b>Never held.</b> A coordination bug — some component reached the fenced view without going through
 * {@code claim}. Nothing about the deployment will fix this; the wiring is wrong.
 * <li><b>Held, then lost.</b> The lease lapsed or was taken over mid-turn (a long GC pause, a partition, an eviction).
 * The write is refused so a superseded holder cannot keep appending to history behind the new holder's back.
 * </ul>
 *
 * <p>
 * This is deliberately loud. The alternative — dropping the write silently — turns a recoverable eviction into missing
 * conversation history that surfaces much later as an unexplained gap. A turn that fails on this exception has already
 * lost the right to finish; failing it is the correct outcome, not collateral damage.
 */
public class SessionNotHeldException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SessionNotHeldException(String message) {
        super(message);
    }

    public SessionNotHeldException(String message, Throwable cause) {
        super(message, cause);
    }
}
