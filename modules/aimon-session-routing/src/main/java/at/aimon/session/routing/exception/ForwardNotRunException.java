package at.aimon.session.routing.exception;

import java.io.Serial;

/**
 * A forwarded submit's future failed <em>and its turn did not run</em>: the message never reached the cross-node inbox,
 * or it was purged from it before any holder drained it — the session was released, deleted or evicted while the
 * message waited.
 *
 * <p>
 * The one forward failure a caller may safely retry with the same input: no node ran it, and none will. Every other
 * failure of a forwarded future — the forward deadline passing, the idempotency reservation lost, the router shutting
 * down — leaves it unknown whether the turn ran, and a retry could run it twice.
 *
 * <p>
 * An {@link IllegalStateException}, as these failures were before, so a caller that catches that sees no difference.
 */
public class ForwardNotRunException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message
     *            why the forwarded turn did not run
     */
    public ForwardNotRunException(String message) {
        super(message);
    }

    /**
     * @param message
     *            why the forwarded turn did not run
     * @param cause
     *            the underlying failure
     */
    public ForwardNotRunException(String message, Throwable cause) {
        super(message, cause);
    }
}
