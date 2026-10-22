package at.aimon.session.routing.exception;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.TurnId;

/**
 * A submitted turn failed, and it is known that <b>no node ran it</b> — its input was never handed to an agent, so
 * submitting it again cannot run it twice.
 *
 * <p>
 * Most failures of a forwarded turn cannot say that. A deadline, a lost reservation or a lost holder leaves open that
 * the input ran, or is running, somewhere; those fail with a plain {@link IllegalStateException}. This type is used
 * only
 * where the "not run" is proven by the node that had the message in hand: it removed the message from the inbox itself
 * ({@link Reason#PURGED}), or it took the message out and gave up on it before starting ({@link Reason#NOT_HOLDER},
 * {@link Reason#UNREADABLE}, {@link Reason#REJECTED}). Design: {@code docs/design/session/routing.md} §11.3.
 *
 * <p>
 * Extends {@link IllegalStateException} so code that caught the failures before this type existed keeps catching
 * them.
 */
public class TurnNotRunException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** How it is known that the turn did not run. */
    public enum Reason {
        /** A release or delete of the session removed the queued message from the inbox. */
        PURGED,
        /** The node holding the message stopped holding the session (its shutdown grace ran out) before starting it. */
        NOT_HOLDER,
        /** The node that collected the message could not decode it. */
        UNREADABLE,
        /** The holder refused the message: the session is bound to a different agent. */
        REJECTED
    }

    /** The turn that did not run, or null when known by its idempotency key only; not serialized. */
    private final transient TurnId turnId;

    /** How it is known that the turn did not run. */
    private final Reason reason;

    /**
     * Creates the exception for a turn known not to have run.
     *
     * @param turnId
     *            the turn that did not run, or null when it is addressed by its idempotency key only
     * @param reason
     *            how it is known (must not be null)
     * @param message
     *            the detail
     */
    public TurnNotRunException(TurnId turnId, Reason reason, String message) {
        super(message);
        this.turnId = turnId;
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * @return the turn that did not run, when it is known by id
     */
    public Optional<TurnId> getTurnId() {
        return Optional.ofNullable(turnId);
    }

    /**
     * @return how it is known that the turn did not run
     */
    public Reason getReason() {
        return reason;
    }
}
