package at.aimon.core.shell;

/**
 * Represents a shell command to be executed.
 *
 * <p>
 * Implementations should provide a string representation of the command that can be executed by the underlying shell
 * (bash, cmd.exe, etc.). The command will be passed to the shell using the appropriate invocation method for the
 * platform (e.g., {@code bash -c "command"} on Unix, {@code cmd.exe /c "command"} on Windows).
 *
 * <p>
 * This is a functional interface and can be implemented using lambda expressions:
 *
 * <pre>
 * {
 *     &#64;code
 *     ShellCommand cmd = () -> "ls -la /tmp";
 *     ShellCommandResult result = shell.execute(cmd);
 * }
 * </pre>
 *
 * <p>
 * <strong>Security Note:</strong> Commands should be constructed carefully to avoid shell injection vulnerabilities.
 * Avoid incorporating unsanitized user input directly into commands.
 */
@FunctionalInterface
public interface ShellCommand {
    /**
     * Returns the string representation of this command.
     *
     * <p>
     * The returned string will be passed to the shell for execution. It should be a valid command that the target shell
     * can interpret.
     *
     * @return the command as a string, never null
     */
    String asString();
}
