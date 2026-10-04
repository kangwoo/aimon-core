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
 * <h2>Cancellation</h2>
 *
 * <p>
 * Every {@code execute} call carries a {@link ShellCancellation} in its {@link ExecutionOptions}. What a shell does
 * with it is decided by {@link ShellFeature#CANCELLATION}:
 * <ul>
 * <li><b>A shell that supports it</b> stops the command and everything the command started (the process tree for a
 * local shell, the remote command for a remote one) when the signal is tripped while the command runs, and that
 * {@code execute} call throws {@link at.aimon.core.shell.exception.ShellCancelledException} carrying the output
 * captured so far. A signal that is already tripped when {@code execute} is called means the command is not started
 * at all, and the same exception is thrown. A signal tripped after the command ended changes nothing.</li>
 * <li><b>Listeners run on the cancelling thread.</b> The shell registers its stop action with
 * {@link ShellCancellation#onCancel(Runnable)}; that action must be thread-safe and idempotent and must not throw,
 * and the shell removes it when the command ends. {@link ShellCancellationSource#cancel()} returns once the stop has
 * been <em>requested</em> — a remote shell may return as soon as the request is sent, without waiting for the command
 * to die.</li>
 * <li><b>A shell that does not support it</b> ignores the signal. The command runs to its end or its timeout, so a
 * caller that needs to stop commands asks {@link #supports(ShellFeature)} first.</li>
 * </ul>
 *
 * <p>
 * A shell that wraps another must hand the signal on. {@link ExecutionOptions#toBuilder()} carries it, so a wrapper
 * that derives its options that way needs nothing more.
 *
 * <h2>Who the command is for</h2>
 *
 * <p>
 * Two flags tell a shell that keeps per-session state (a sandbox whose {@code cd}/{@code export} persist between
 * commands and which runs one command at a time per session) that a command must not be run as an ordinary command of
 * that session. {@link ExecutionOptions#isBackground()} marks a command that may outlive its call, so it must not hold
 * the session. {@link ExecutionOptions#isHook()} marks a command run on behalf of a hook rather than the model, so it
 * runs outside the session: it takes no session lock and saves no state, though it may start from the session's
 * current state. A shell that starts a new process per command with no persisted state ignores both. A wrapper must
 * carry them over; {@link ExecutionOptions#toBuilder()} does.
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
     *             if command execution fails; {@link at.aimon.core.shell.exception.ShellCancelledException} when the
     *             command was stopped through {@link ExecutionOptions#getCancellation()} (see the class javadoc)
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
