package at.aimon.core.subagent.task.codec;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a {@link SessionSnapshotCodec} cannot encode or decode a session snapshot.
 *
 * <p>
 * Signals a codec-level failure: malformed serialized input, an unsupported format version, a content block type the
 * codec does not understand, or a domain-type reconstruction that violates an invariant (e.g. an unknown role, an
 * unsupported MIME type, or invalid base64). A persistent
 * {@link at.aimon.core.subagent.task.SessionSnapshotStore} treats this as a routine, best-effort miss — it logs
 * and returns empty rather than aborting the agent whose transcript it is loading.
 */
public class SessionSnapshotCodecException extends AimonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given message.
     *
     * @param message
     *            the error message
     */
    public SessionSnapshotCodecException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message
     *            the error message
     * @param cause
     *            the underlying cause
     */
    public SessionSnapshotCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
