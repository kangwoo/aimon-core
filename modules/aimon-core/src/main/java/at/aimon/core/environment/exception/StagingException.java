package at.aimon.core.environment.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown by {@code ExecutionEnvironment.stage(...)} when a resource cannot be staged: over the size limit, a recorded
 * file unreadable at copy time, or the source changed since it was scanned. No {@code .staged} marker is written, so
 * the next call retries. Callers turn it into a {@code ToolResult.error} or a command error.
 */
public class StagingException extends AimonException {
    @Serial
    private static final long serialVersionUID = -2349823740192837461L;

    /**
     * @param message
     *            the message
     */
    public StagingException(String message) {
        super(message);
    }

    /**
     * @param message
     *            the message
     * @param cause
     *            the cause
     */
    public StagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
