package at.aimon.core.shell;

import at.aimon.core.shell.exception.ShellExecutionException;

/**
 * Abstraction for executing shell commands in different environments.
 *
 * <p>
 * This interface provides a unified API for executing shell commands across different platforms (local, remote,
 * containerized, etc.) and shells (bash, sh, zsh, cmd.exe, PowerShell, etc.).
 *
 * <p>
 * Implementations must be thread-safe and should properly manage resources. Use try-with-resources or explicitly call
 * {@link #close()} to release resources.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * try (VirtualShell shell = new LocalShell()) {
 *     ShellCommand cmd = () -> "echo 'Hello, World!'";
 *     ShellCommandResult result = shell.execute(cmd);
 *     if (result.isSuccess()) {
 *         System.out.println(result.stdout());
 *     }
 * }
 * }
 * </pre>
 */
public interface VirtualShell extends AutoCloseable {

    /**
     * Executes a shell command with default options.
     *
     * @param command
     *            the command to execute, must not be null
     * @return the execution result containing exit code, stdout, stderr, and duration
     * @throws ShellExecutionException
     *             if command execution fails
     */
    ShellCommandResult execute(ShellCommand command) throws ShellExecutionException;

    /**
     * Executes a shell command with custom execution options.
     *
     * @param command
     *            the command to execute, must not be null
     * @param options
     *            the execution options (timeout, environment, working directory, etc.), must not be null
     * @return the execution result containing exit code, stdout, stderr, and duration
     * @throws ShellExecutionException
     *             if command execution fails
     */
    ShellCommandResult execute(ShellCommand command, ExecutionOptions options) throws ShellExecutionException;

    /**
     * Returns the current working directory of this shell.
     *
     * @return the working directory path, or null if not set
     */
    String getWorkingDirectory();

    /**
     * Checks if this shell supports a specific feature.
     *
     * @param feature
     *            the feature to check, must not be null
     * @return true if the feature is supported, false otherwise
     */
    boolean supports(ShellFeature feature);

    /**
     * Releases resources associated with this shell.
     *
     * <p>
     * Implementations should ensure all background tasks are properly terminated and resources are cleaned up.
     */
    @Override
    void close();
}
