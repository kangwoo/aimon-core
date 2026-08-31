package at.aimon.core.agent.session.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a {@code SessionRecordStore} backend fails to carry out a read or a write (e.g. the database is
 * unreachable, a statement fails, a stored record cannot be decoded).
 *
 * <p>
 * The in-memory store never throws this: its failure modes are programming errors, which stay
 * {@code NullPointerException}
 * / {@code IllegalArgumentException}. A distributed backend has a third category the in-memory one does not — the
 * infrastructure itself — and it is the one callers must be able to tell apart, because it is the only one where
 * retrying or failing the request is the right answer rather than fixing the caller.
 *
 * <p>
 * <b>Never signals "no such session".</b> Absence is a normal outcome with its own encoding on every method that can
 * observe it: an empty {@code Optional} from {@code load}, a no-op from
 * {@code setTotalsAndBudgetOverride} / {@code resetCompactionFailureCount}, {@code 0} from
 * {@code incrementCompactionFailureCount}. A backend that threw here for a missing record would make the
 * distributed stores behave differently from {@code InMemorySessionRecordStore} on the path that every session takes
 * on its first turn.
 *
 * <p>
 * Companion of {@link SessionLeaseException}, which plays the same role for the lease SPI.
 */
public class SessionRecordStoreException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SessionRecordStoreException(String message) {
        super(message);
    }

    public SessionRecordStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
