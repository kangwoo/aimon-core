package at.aimon.core.shell.exception;

import java.io.Serial;

/**
 * Exception thrown when the files a shell placed in a
 * {@linkplain at.aimon.core.shell.ExecutionOptions#getRunDirectory()
 * run directory} do not hold the bytes it was given, so the command was not run
 * ({@link at.aimon.core.shell.ShellFeature#RUN_DIRECTORY}).
 *
 * <p>
 * It is evidence that something rewrote the files between their transfer and the check — an upload the agent can
 * write, a race on the directory — and not an infrastructure failure: a hook caller treats it as a reason to block,
 * not to fail open. The message names no file content.
 */
public final class RunDirectoryIntegrityException extends ShellExecutionException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new run-directory integrity exception.
     *
     * @param message
     *            the error message
     */
    public RunDirectoryIntegrityException(String message) {
        super(message);
    }

    /**
     * Creates a new run-directory integrity exception with a cause.
     *
     * @param message
     *            the error message
     * @param cause
     *            the underlying cause
     */
    public RunDirectoryIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
