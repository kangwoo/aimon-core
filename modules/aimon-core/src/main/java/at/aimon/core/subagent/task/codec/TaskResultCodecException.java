package at.aimon.core.subagent.task.codec;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a {@link TaskResultCodec} cannot encode or decode a background task result.
 *
 * <p>
 * Signals a codec-level failure: malformed serialized input, an unsupported format version, or a payload whose shape
 * does not match the expected envelope. A persistent {@link at.aimon.core.subagent.task.TaskResultStore} treats this as
 * a routine, best-effort miss — it logs and returns empty rather than failing the agent that asked for the result.
 */
public class TaskResultCodecException extends AimonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given message.
     *
     * @param message
     *            the error message
     */
    public TaskResultCodecException(String message) {
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
    public TaskResultCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
