package at.aimon.core.environment.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown by every filesystem and shell call of an {@code UnavailableExecutionEnvironment}: the execution's
 * environment could not be resolved, and there is no fallback to the host. Tools turn it into a
 * {@code ToolResult.error} carrying the cause.
 */
public class ExecutionEnvironmentUnavailableException extends AimonException {
    @Serial
    private static final long serialVersionUID = 4180371963510426527L;

    /**
     * @param message
     *            the message, naming the cause
     * @param cause
     *            why the environment could not be resolved, or null
     */
    public ExecutionEnvironmentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
