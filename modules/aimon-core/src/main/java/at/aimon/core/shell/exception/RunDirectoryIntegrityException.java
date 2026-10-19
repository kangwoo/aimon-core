package at.aimon.core.shell.exception;

import java.io.Serial;

/**
 * Exception thrown when what a shell placed for the run from bytes it was given &mdash; the
 * {@linkplain at.aimon.core.shell.ExecutionOptions#getRunDirectory() run directory}'s files, or the command's
 * {@linkplain at.aimon.core.shell.ExecutionOptions#getStdin() stdin} when the shell moved it through something the
 * agent can write (an upload) &mdash; no longer holds those bytes, so the command was not run
 * ({@link at.aimon.core.shell.ShellFeature#RUN_DIRECTORY}). It may be thrown for a command with no run directory: a
 * hook whose stdin was uploaded.
 *
 * <p>
 * It is evidence that something rewrote them between their transfer and the check — an upload the agent can
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
