package at.aimon.core.shell;

/**
 * Why a shell ended a command itself, rather than the command ending on its own, timing out or being cancelled
 * ({@link ShellCommandResult#killCause()}).
 *
 * <p>
 * A remote shell that ends a command for a limit of its own reports only an exit status the wrapper saw ("exit 137"),
 * which a caller cannot tell from a command that crashed. The shell says why instead, so a caller that reads the
 * command's output as a protocol — the shell engine of {@code ExecuteCode}
 * ({@code docs/design/tool/programmatic-tool-calling-shell.md} §13 S8) — can tell a flooded channel from a failed
 * program.
 */
public enum ShellKillCause {

    /**
     * A single line of output exceeded the shell's line limit, so the shell ended the command before materializing
     * the line (the streaming contract on
     * {@link VirtualShell#execute(ShellCommand, ExecutionOptions, ShellLineListener)}).
     * Both streams are then truncated.
     */
    LINE_LIMIT_EXCEEDED,

    /** The shell ended the command for a reason of its own that has no constant here. */
    OTHER
}
