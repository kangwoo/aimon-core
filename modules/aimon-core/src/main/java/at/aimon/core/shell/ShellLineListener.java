package at.aimon.core.shell;

/**
 * Receives a running command's standard output one line at a time, as the command prints it.
 *
 * <p>
 * Passed to {@link VirtualShell#execute(ShellCommand, ExecutionOptions, ShellLineListener)}, which a shell declaring
 * {@link ShellFeature#STREAMING} implements. The shell calls this from a thread of its own choosing, one line at a time
 * and in output order — never two calls at once — so an implementation needs no locking of its own against itself. A
 * line is handed over without its terminator ({@code \n}, or {@code \r\n}).
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
}
