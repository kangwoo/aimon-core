package at.aimon.core.shell;

/**
 * Represents features that a {@link VirtualShell} implementation may support.
 *
 * <p>
 * Different shell implementations may support different features based on their capabilities and underlying platform.
 */
public enum ShellFeature {
    /**
     * Interactive terminal support (PTY/pseudo-terminal).
     *
     * <p>
     * Shells that support this feature can handle interactive commands that require user input, terminal control codes,
     * and real-time I/O.
     */
    INTERACTIVE,

    /**
     * Pipe support for chaining commands.
     *
     * <p>
     * Shells that support this feature can execute commands like {@code command1 | command2}, where the output of one
     * command becomes the input of another.
     */
    PIPE,

    /**
     * I/O redirection support.
     *
     * <p>
     * Shells that support this feature can redirect input/output using operators like {@code >}, {@code <}, {@code >>},
     * {@code 2>}, etc.
     */
    REDIRECTION,

    /**
     * Stopping a running command through its {@link ExecutionOptions#getCancellation() cancellation signal}.
     *
     * <p>
     * A shell that supports this feature stops the command <em>and what the command started, as far as the shell
     * can reach it</em> when the signal is tripped — the process tree for a local shell, the remote command for a
     * remote one — and makes the
     * {@code execute} call that is running it throw
     * {@link at.aimon.core.shell.exception.ShellCancelledException}. A shell that does not support it ignores the
     * signal, and the command runs to its end or its timeout. The full contract is on {@link VirtualShell}.
     */
    CANCELLATION,

    /**
     * Running a command {@linkplain ExecutionOptions#isIsolated() isolated}: as a fresh shell of the environment would,
     * reading and saving no per-session state ({@code cd}, {@code export}) and taking no session lock.
     *
     * <p>
     * A shell that keeps no state between commands supports this trivially. A shell that does not declare it ignores
     * the flag, so a caller that must not run a command from the model's shell state &mdash; the configured
     * {@code hooks.json} path &mdash; refuses to run on it.
     */
    ISOLATED_EXECUTION,

    /**
     * Placing a {@linkplain ExecutionOptions#getRunDirectory() run directory}: the files a hook runs from, handed to
     * the shell as bytes with the command rather than staged ahead of it (EE-109).
     *
     * <p>
     * The contract, for a shell that declares it:
     * <ol>
     * <li>Before the command starts, create a directory that did not exist before, with permissions as tight as the
     * environment allows, outside the paths the model's file tools are given where the environment has such a
     * place.</li>
     * <li>Write exactly {@link RunDirectory#getPaths()} with their bytes. The builder guarantees that no path holds a
     * control character ({@link Character#isISOControl(int)}), that no path is both a file and the parent directory
     * of another, and that the total is at most {@link RunDirectory#MAX_TOTAL_BYTES}, so the shell need not check
     * those. Two paths the filesystem would keep as one file (letter case, Unicode form) remain the shell's error, and
     * the command is not run.</li>
     * <li>If the bytes travelled through anything the agent can write (an upload), check the placed files against
     * digests that did not travel that way, after placing them and before the command starts. On a mismatch, do not
     * run the command and throw {@link at.aimon.core.shell.exception.RunDirectoryIntegrityException}. The same
     * exception covers a {@linkplain ExecutionOptions#getStdin() stdin} the shell moved that way.</li>
     * <li>Export the directory's absolute path in {@link RunDirectory#getVariable()}; it wins over the same name in
     * {@link ExecutionOptions#getEnvironment()}.</li>
     * <li>Remove the directory after the command ends, on every outcome. A failure to remove it is logged, not the
     * command's failure.</li>
     * <li>Independent of {@link ExecutionOptions#isIsolated()} and {@link ExecutionOptions#isHook()}.</li>
     * </ol>
     *
     * <p>
     * A shell that does not declare the feature ignores the option, so a caller that needs the files asks
     * {@link VirtualShell#supports(ShellFeature)} first and does not run the command without it. What this closes is
     * a copy that outlives the run; a process running as the same user can still reach the directory while the
     * command runs.
     */
    RUN_DIRECTORY
}
