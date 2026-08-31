/**
 * Core abstractions and interfaces for shell command execution.
 *
 * <p>
 * This package provides a unified API for executing shell commands across different environments and platforms. The
 * design follows the Dependency Inversion Principle (DIP), allowing applications to depend on abstractions rather than
 * concrete implementations.
 *
 * <h2>Key Abstractions</h2>
 *
 * <h3>VirtualShell</h3>
 * <p>
 * The central interface for executing shell commands. Implementations can provide different execution strategies
 * (local, remote, containerized, etc.) while maintaining a consistent API.
 *
 * <pre>
 * {@code
 * try (VirtualShell shell = new LocalShell()) {
 *     ShellCommand cmd = () -> "ls -la /tmp";
 *     ShellCommandResult result = shell.execute(cmd);
 *     if (result.isSuccess()) {
 *         System.out.println(result.stdout());
 *     }
 * }
 * }
 * </pre>
 *
 * <h3>ShellCommand</h3>
 * <p>
 * A functional interface representing a command to be executed. Commands are represented as strings that will be passed
 * to the underlying shell (bash, sh, cmd.exe, etc.).
 *
 * <h3>ShellCommandResult</h3>
 * <p>
 * An immutable value object capturing the complete outcome of command execution:
 * <ul>
 * <li><strong>Exit Code:</strong> Command success/failure indicator (0 = success)
 * <li><strong>Standard Output:</strong> Command stdout content
 * <li><strong>Standard Error:</strong> Command stderr content
 * <li><strong>Duration:</strong> Execution time
 * </ul>
 *
 * <h3>ExecutionOptions</h3>
 * <p>
 * Configuration for command execution behavior. Supports:
 * <ul>
 * <li>Timeout control
 * <li>Environment variable overrides
 * <li>Working directory specification
 * <li>Character encoding configuration
 * <li>Stream redirection options
 * <li>Unix shell selection (bash, sh, zsh, etc.)
 * </ul>
 *
 * <p>
 * Use the builder pattern for clean configuration:
 *
 * <pre>
 * {
 *     &#64;code
 *     ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(30)).workingDirectory("/tmp")
 *             .environment(Map.of("PATH", "/usr/bin")).unixShell("bash").build();
 * }
 * </pre>
 *
 * <h3>ShellFeature</h3>
 * <p>
 * An enumeration of capabilities that shell implementations may support:
 * <ul>
 * <li><strong>INTERACTIVE:</strong> PTY/pseudo-terminal support for interactive commands
 * <li><strong>PIPE:</strong> Command chaining with pipes (command1 | command2)
 * <li><strong>REDIRECTION:</strong> I/O redirection (&gt;, &lt;, &gt;&gt;, 2&gt;)
 * </ul>
 *
 * <h2>Exception Hierarchy</h2>
 *
 * <pre>
 * ShellExecutionException (checked)
 *   └── ShellTimeoutException
 * </pre>
 *
 * <p>
 * <strong>Important:</strong> These exceptions indicate <em>execution failures</em> (process startup errors, timeouts),
 * NOT command failures. Commands that run but return non-zero exit codes complete successfully and return a
 * {@link at.aimon.core.shell.ShellCommandResult} with {@code isFailure() == true}.
 *
 * <h2>Design Principles</h2>
 *
 * <p>
 * This package strictly adheres to SOLID principles:
 *
 * <ul>
 * <li><strong>Single Responsibility:</strong> Each class has one clear purpose
 * <li><strong>Open/Closed:</strong> Extensible through new {@link at.aimon.core.shell.VirtualShell} implementations
 * <li><strong>Liskov Substitution:</strong> All implementations fully substitute their interfaces
 * <li><strong>Interface Segregation:</strong> Focused, cohesive interfaces
 * <li><strong>Dependency Inversion:</strong> Depend on abstractions, not implementations
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * All value objects ({@link at.aimon.core.shell.ShellCommandResult}, {@link at.aimon.core.shell.ExecutionOptions}) are
 * immutable and thread-safe. {@link at.aimon.core.shell.VirtualShell} implementations must document their thread-safety
 * guarantees.
 *
 * <h2>Resource Management</h2>
 *
 * <p>
 * {@link at.aimon.core.shell.VirtualShell} extends {@link AutoCloseable}, enabling automatic resource cleanup with
 * try-with-resources:
 *
 * <pre>
 * {@code
 * try (VirtualShell shell = new LocalShell()) {
 *     // Execute commands
 * } // Automatically closes and releases resources
 * }
 * </pre>
 *
 * <h2>Security Considerations</h2>
 *
 * <p>
 * <strong>Command Injection:</strong> When constructing commands with user input, always sanitize or validate input to
 * prevent shell injection attacks. Consider using argument-based APIs when available instead of string concatenation.
 *
 * <pre>
 * {
 *     &#64;code
 *     // UNSAFE - vulnerable to injection
 *     String userInput = getUserInput();
 *     ShellCommand cmd = () -> "rm -rf " + userInput;
 *
 *     // SAFER - validate input first
 *     String userInput = validatePath(getUserInput());
 *     ShellCommand cmd = () -> "rm -rf " + userInput;
 * }
 * </pre>
 *
 * <h2>Platform Compatibility</h2>
 *
 * <p>
 * This package is designed for cross-platform compatibility:
 * <ul>
 * <li><strong>Unix/Linux/macOS:</strong> Uses bash, sh, zsh, or other Unix shells
 * <li><strong>Windows:</strong> Uses cmd.exe or PowerShell
 * </ul>
 *
 * <p>
 * Implementations should handle platform-specific differences transparently.
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Basic Command Execution</h3>
 *
 * <pre>
 * {@code
 * try (VirtualShell shell = new LocalShell()) {
 *     ShellCommand cmd = () -> "echo 'Hello, World!'";
 *     ShellCommandResult result = shell.execute(cmd);
 *     System.out.println(result.stdout());
 * }
 * }
 * </pre>
 *
 * <h3>Command with Timeout</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(5)).build();
 *
 *     try (VirtualShell shell = new LocalShell()) {
 *         ShellCommand cmd = () -> "long-running-command";
 *         ShellCommandResult result = shell.execute(cmd, options);
 *     } catch (ShellTimeoutException e) {
 *         System.err.println("Command timed out");
 *         System.err.println("Partial output: " + e.stdout());
 *     }
 * }
 * </pre>
 *
 * <h3>Environment Variables and Working Directory</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     ExecutionOptions options = ExecutionOptions.builder().workingDirectory("/tmp")
 *             .environment(Map.of("PATH", "/usr/local/bin:/usr/bin", "MY_VAR", "value")).build();
 *
 *     try (VirtualShell shell = new LocalShell()) {
 *         ShellCommand cmd = () -> "echo $MY_VAR";
 *         ShellCommandResult result = shell.execute(cmd, options);
 *     }
 * }
 * </pre>
 *
 * <h3>Checking Feature Support</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualShell shell = new LocalShell();
 *     if (shell.supports(ShellFeature.PIPE)) {
 *         ShellCommand cmd = () -> "cat file.txt | grep pattern";
 *         shell.execute(cmd);
 *     }
 * }
 * </pre>
 *
 * @see at.aimon.core.shell.VirtualShell
 * @see at.aimon.core.shell.ShellCommand
 * @see at.aimon.core.shell.ShellCommandResult
 * @see at.aimon.core.shell.ExecutionOptions
 * @see at.aimon.core.shell.ShellFeature
 * @see at.aimon.core.shell.impl.local.LocalShell
 * @since 1.0.0
 */
package at.aimon.core.shell;
