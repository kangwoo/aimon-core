package at.aimon.core.shell;

/**
 * Receives a running command's output one line at a time, as the command prints it: standard output always, standard
 * error when the shell streams it too ({@link #onStderrLine(String)}).
 *
 * <p>
 * Passed to {@link VirtualShell#execute(ShellCommand, ExecutionOptions, ShellLineListener)}, which a shell declaring
 * {@link ShellFeature#STREAMING} implements. The shell calls this from a thread of its own choosing, one line at a time
 * — never two calls at once — so an implementation needs no locking of its own against itself. Order is kept
 * <em>within</em> each stream, not across the two: a caller that needs one ordered stream merges them
 * ({@link ExecutionOptions#isRedirectErrorStream()}). A line is handed over without its terminator ({@code \n}, or
 * {@code \r\n}).
 *
 * <p>
 * The listener runs on the shell's waiting path: a slow listener delays the shell's next read, not the command, which
 * keeps writing to wherever the shell captures it. A listener that throws loses that line; the shell logs it and goes
 * on.
 */
@FunctionalInterface
public interface ShellLineListener {

    /**
     * Called for each complete line the command writes to its standard output.
     *
     * @param line
     *            the line without its terminator (never null; may be empty)
     */
    void onStdoutLine(String line);

    /**
     * Called for each complete line the command writes to its standard error, by a shell that streams it — the local
     * shell does, unless the call merges the two streams. A shell that does not leaves stderr to the result only.
     * Does nothing by default.
     *
     * <p>
     * Standard error is also in the result, cut to the capture limit from its <em>start</em>; a caller that wants the
     * <em>last</em> lines of a long run — the failure that ended it — keeps them from here.
     *
     * @param line
     *            the line without its terminator (never null; may be empty)
     */
    default void onStderrLine(String line) {
    }
}
