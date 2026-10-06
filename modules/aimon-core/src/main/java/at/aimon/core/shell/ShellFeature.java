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
    CANCELLATION
}
