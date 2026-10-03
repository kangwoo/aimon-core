package at.aimon.core.shell;

/**
 * Cancellation signal for one shell command, carried in {@link ExecutionOptions#getCancellation()}.
 *
 * <p>
 * This is the read side: a shell that declares {@link ShellFeature#CANCELLATION} registers a listener that stops the
 * command it is running. The side that trips it is {@link ShellCancellationSource}. It is defined here rather than
 * reusing {@code at.aimon.core.agent.interrupt.CancellationSignal} for the reason {@code LlmCancellation} gives: the
 * shell package cannot see {@code agent.interrupt}, and that signal is one execution's — it speaks of interrupt
 * reasons, where this one speaks of a single command.
 *
 * <p>
 * Semantics:
 * <ul>
 * <li><b>Single-shot</b> — once cancelled it stays cancelled.</li>
 * <li><b>Listener firing</b> — a listener registered through {@link #onCancel(Runnable)} runs when the signal is
 * tripped, on the thread that tripped it; one registered after the signal is already cancelled runs at once, on the
 * registering thread.</li>
 * <li><b>Listener contract</b> — a listener therefore runs on a thread other than the one executing the command. It
 * must be thread-safe and idempotent, and must not throw.</li>
 * </ul>
 *
 * @see ShellCancellationSource
 * @see ShellFeature#CANCELLATION
 */
public interface ShellCancellation {

    /**
     * Returns the signal that is never cancelled and discards every listener — the default of
     * {@link ExecutionOptions}.
     *
     * @return the shared inert signal (never null)
     */
    static ShellCancellation none() {
        return NoopShellCancellation.INSTANCE;
    }

    /**
     * @return {@code true} once cancellation has been requested
     */
    boolean isCancelled();

    /**
     * Registers a listener to run when this signal is cancelled. If it is already cancelled the listener runs
     * immediately, on the calling thread.
     *
     * @param listener
     *            what to run (must not be null; thread-safe, idempotent, never throws)
     * @return a handle that removes the listener — a shell removes it when its command ends, so a signal that outlives
     *         the command holds nothing of it
     */
    Registration onCancel(Runnable listener);

    /** Removes a listener registered with {@link ShellCancellation#onCancel(Runnable)}. */
    @FunctionalInterface
    interface Registration {

        /** Removes the listener. Idempotent; a listener that is already running is not interrupted. */
        void remove();
    }
}
