package at.aimon.core.agent.session.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a {@code SessionLogSegmentStore} backend fails to carry out a read or a write — the database is
 * unreachable, a statement fails, a duplicate segment id is rejected.
 *
 * <p>
 * <b>Never signals "no such segment".</b> Absence has its own answers — an empty {@code Optional} from {@code get}, an
 * empty list from {@code list}, a no-op delete — for the same reason {@link SessionRecordStoreException} never
 * signals a missing record: a missing segment is something a reader reports as a gap, not a failure.
 */
public class SessionLogSegmentStoreException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SessionLogSegmentStoreException(String message) {
        super(message);
    }

    public SessionLogSegmentStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
