/**
 * Local machine shell implementation for executing commands on the host system.
 *
 * <p>
 * This package provides {@link at.aimon.core.shell.impl.local.LocalShell}, a production-ready implementation of
 * {@link at.aimon.core.shell.VirtualShell} that executes shell commands on the local machine using the system's native
 * shell.
 *
 * <h2>LocalShell Implementation</h2>
 *
 * <p>
 * {@link at.aimon.core.shell.impl.local.LocalShell} is a shell implementation that provides:
 *
 * <ul>
 * <li><strong>Unix shells:</strong> Runs commands through the configured Unix shell. A {@code cmd.exe} branch exists
 * but
 * Windows is not a supported platform — see "Platform scope" below
 * <li><strong>File-backed capture:</strong> The child's stdout/stderr are redirected to per-execution temp files, so no
 * pipe fd is inherited and no reader thread can be left blocked by a backgrounded child
 * <li><strong>Timeout support:</strong> Configurable command execution timeouts with graceful termination
 * <li><strong>Bounded capture:</strong> Reads are limited by {@code maxCaptureBytes} and report truncation
 * <li><strong>Environment control:</strong> Custom environment variables and working directory
 * <li><strong>Shell selection:</strong> Configurable Unix shell (bash, sh, zsh, etc.)
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <p>
 * The implementation uses Java's {@link ProcessBuilder} API with the following design:
 *
 * <pre>
 * ┌──────────────┐
 * │ LocalShell   │
 * └──────┬───────┘
 *        │
 *        ├─→ ProcessBuilder
 *        │   ├─→ redirectOutput/redirectError → per-execution temp files
 *        │   └─→ Platform-specific command execution
 *        │       ├─ Unix: /bin/bash -c "command"
 *        │       └─ Windows: cmd.exe /c "command"
 *        │
 *        ├─→ Timeout Management
 *        │   └─→ Process.waitFor(timeout) + destroyForcibly()
 *        │
 *        └─→ Capture: read temp files (bounded by maxCaptureBytes) → delete
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * {@link at.aimon.core.shell.impl.local.LocalShell} is <strong>thread-safe</strong> and can be safely used from
 * multiple threads concurrently. Each {@code execute} call is self-contained (its own process and temp files, no shared
 * mutable state), so concurrent calls do not interfere.
 *
 * <pre>
 * {
 *     &#64;code
 *     // Shared shell instance for multiple threads
 *     LocalShell shell = new LocalShell();
 *
 *     // Execute commands from multiple threads safely
 *     ExecutorService executor = Executors.newFixedThreadPool(10);
 *     for (int i = 0; i &lt; 100; i++) {
 *         executor.submit(() -> {
 *             ShellCommandResult result = shell.execute(() -> "echo 'test'");
 *             // Process result
 *         });
 *     }
 * }
 * </pre>
 *
 * <h2>Configuration Options</h2>
 *
 * <h3>Constructor Configuration</h3>
 *
 * <p>
 * {@link at.aimon.core.shell.impl.local.LocalShell} provides multiple constructors for different use cases:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Default configuration
 *     LocalShell shell1 = new LocalShell();
 *
 *     // Custom default working directory
 *     LocalShell shell2 = new LocalShell(Paths.get("/tmp"));
 *
 *     // The ioThreads argument is retained for compatibility and ignored (output is captured to files)
 *     LocalShell shell3 = new LocalShell(4);
 *
 *     // Full customization
 *     LocalShell shell4 = new LocalShell(4, // ignored (retained for compatibility)
 *             Paths.get("/tmp"), // default working directory
 *             "zsh" // Unix shell
 *     );
 * }
 * </pre>
 *
 * <h3>Per-Command Configuration</h3>
 *
 * <p>
 * Use {@link at.aimon.core.shell.ExecutionOptions} to override defaults per command:
 *
 * <pre>
 * {
 *     &#64;code
 *     LocalShell shell = new LocalShell();
 *
 *     ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(30))
 *             .workingDirectory("/var/log").environment(Map.of("DEBUG", "true")).unixShell("bash") // Override shell
 *                                                                                                  // for this command
 *             .build();
 *
 *     ShellCommandResult result = shell.execute(() -> "grep ERROR *.log", options);
 * }
 * </pre>
 *
 * <p>
 * That builder used to carry a {@code drainTimeout} naming how long to keep draining an output pipe. There is no pipe
 * any more — the child writes to a temp file that is read after it exits — so the option was inert and has been
 * removed; bound output with {@code maxCaptureBytes} instead. (The identically named {@code SessionSpec.drainTimeout}
 * in {@code aimon-bootstrap} is unrelated and still live.)
 *
 * <h2>Performance Characteristics</h2>
 *
 * <h3>Output Capture</h3>
 *
 * <p>
 * The child writes stdout/stderr directly to temp files, so a full output pipe never blocks the process and no reader
 * thread is required. Only the first {@code maxCaptureBytes} of each stream (default 16 MiB) are read into memory; more
 * than that is dropped and reported via {@link at.aimon.core.shell.ShellCommandResult#outputTruncated()}.
 *
 * <h3>Timeout Handling</h3>
 *
 * <p>
 * When a timeout occurs:
 * <ol>
 * <li>The process's descendants are snapshotted and asked to stop, then the process itself
 * ({@code Process.destroy()}). The order is deliberate — destroying the parent first re-parents its children, after
 * which {@code descendants()} no longer enumerates them
 * <li>Whatever has not terminated within 200ms is forcibly killed ({@code Process.destroyForcibly()}), descendants
 * included
 * <li>Whatever the process flushed to the capture files before termination is read and returned in
 * {@link at.aimon.core.shell.exception.ShellTimeoutException}
 * </ol>
 *
 * <p>
 * The same path runs when the calling thread is interrupted. A timeout that killed only the direct child would leave
 * the work running with nobody waiting on it, which is what the descendant sweep exists to prevent.
 *
 * <h2>Resource Cleanup</h2>
 *
 * <p>
 * Always use try-with-resources or explicitly call {@code close()} to ensure proper cleanup:
 *
 * <pre>
 * {@code
 * // Recommended: try-with-resources
 * try (LocalShell shell = new LocalShell()) {
 *     // Execute commands
 * } // Automatically closes and releases resources
 *
 * // Manual cleanup
 * LocalShell shell = new LocalShell();
 * try {
 *     // Execute commands
 * } finally {
 *     shell.close();
 * }
 * }
 * </pre>
 *
 * <p>
 * {@code close()} is a no-op: output is captured to per-execution temp files that are deleted as each command finishes,
 * so there are no long-lived resources (thread pools, open streams) to release. It is retained to satisfy the
 * {@link AutoCloseable} contract, and try-with-resources remains the recommended usage style.
 *
 * <h2>Platform-Specific Behavior</h2>
 *
 * <h3>Unix/Linux/macOS</h3>
 *
 * <p>
 * Commands are executed via the specified Unix shell (default: bash):
 *
 * <pre>
 * /bin/bash -c "your command here"
 * </pre>
 *
 * <p>
 * Supported shells: bash, sh, zsh, fish, ksh, dash, etc.
 *
 * <h3>Windows — out of scope</h3>
 *
 * <p>
 * There is a branch that runs {@code cmd.exe /c "your command here"} when {@code os.name} contains "win", and the
 * {@code unixShell} option is ignored there. Do not read it as platform support. The option's own name says what this
 * class assumes, and everything above it assumes the same: {@code BashTool} sends POSIX shell syntax, and the tests
 * that cover timeouts, descendant termination and temp-file capture run on Unix only.
 *
 * <p>
 * Since {@link at.aimon.core.tools.bash.BashTool} consumes {@link at.aimon.core.shell.VirtualShell} directly, Windows
 * support — if it is ever wanted — is <strong>another {@code VirtualShell} implementation</strong>, not a branch inside
 * the tool and not an extension of this class. Nothing in {@code BashTool} would change.
 *
 * <h2>Feature Support</h2>
 *
 * <p>
 * {@link at.aimon.core.shell.impl.local.LocalShell} supports the following {@link at.aimon.core.shell.ShellFeature
 * features}:
 *
 * <table border="1">
 * <caption>Feature Support Matrix</caption>
 * <tr>
 * <th>Feature</th>
 * <th>Supported</th>
 * <th>Description</th>
 * </tr>
 * <tr>
 * <td>PIPE</td>
 * <td>✓ Yes</td>
 * <td>Command chaining (command1 | command2)</td>
 * </tr>
 * <tr>
 * <td>REDIRECTION</td>
 * <td>✓ Yes</td>
 * <td>I/O redirection (&gt;, &lt;, &gt;&gt;, 2&gt;)</td>
 * </tr>
 * <tr>
 * <td>INTERACTIVE</td>
 * <td>✗ No</td>
 * <td>PTY/pseudo-terminal not supported (requires pty library)</td>
 * </tr>
 * </table>
 *
 * <h2>Error Handling</h2>
 *
 * <h3>Execution Failures</h3>
 *
 * <p>
 * {@link at.aimon.core.shell.exception.ShellExecutionException} is thrown when:
 * <ul>
 * <li>Process cannot be started (IOException)
 * <li>Thread is interrupted during execution
 * </ul>
 *
 * <h3>Timeouts</h3>
 *
 * <p>
 * {@link at.aimon.core.shell.exception.ShellTimeoutException} is thrown when command exceeds timeout:
 *
 * <pre>
 * {@code
 * try {
 *     ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(5)).build();
 *     shell.execute(() -> "sleep 10", options);
 * } catch (ShellTimeoutException e) {
 *     System.err.println("Timeout after: " + e.timeout());
 *     System.err.println("Partial output: " + e.stdout());
 * }
 * }
 * </pre>
 *
 * <h3>Command Failures (Non-Zero Exit Codes)</h3>
 *
 * <p>
 * Commands that run but fail (non-zero exit code) do <strong>NOT</strong> throw exceptions. Check the result:
 *
 * <pre>
 * {
 *     &#64;code
 *     ShellCommandResult result = shell.execute(() -> "grep pattern missing_file.txt");
 *     if (result.isFailure()) {
 *         System.err.println("Command failed with exit code: " + result.exitCode());
 *         System.err.println("Error: " + result.stderr());
 *     }
 * }
 * </pre>
 *
 * <h2>Best Practices</h2>
 *
 * <h3>1. Use try-with-resources</h3>
 *
 * <pre>
 * {@code
 * try (LocalShell shell = new LocalShell()) {
 *     // Execute commands
 * }
 * }
 * </pre>
 *
 * <h3>2. Set reasonable timeouts</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(30)).build();
 * }
 * </pre>
 *
 * <h3>3. Check command results</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     ShellCommandResult result = shell.execute(cmd);
 *     if (result.isFailure()) {
 *         // Handle failure
 *     }
 * }
 * </pre>
 *
 * <h3>4. Sanitize user input</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Validate/sanitize before using in commands
 *     String safeInput = validatePath(userInput);
 *     ShellCommand cmd = () -> "cat " + safeInput;
 * }
 * </pre>
 *
 * <h3>5. Reuse shell instances</h3>
 *
 * <pre>
 * {@code
 * // Good: reuse for multiple commands
 * try (LocalShell shell = new LocalShell()) {
 *     shell.execute(cmd1);
 *     shell.execute(cmd2);
 *     shell.execute(cmd3);
 * }
 *
 * // Wasteful: creates new instance each time
 * try (LocalShell shell = new LocalShell()) {
 *     shell.execute(cmd1);
 * }
 * try (LocalShell shell = new LocalShell()) {
 *     shell.execute(cmd2);
 * }
 * }
 * </pre>
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 * <li><strong>No PTY support:</strong> Interactive commands requiring terminal control (like password prompts, text
 * editors) won't work properly
 * <li><strong>String-based commands only:</strong> Commands are passed as strings through shell, not as argv arrays
 * <li><strong>Shell-dependent behavior:</strong> Command syntax and behavior depend on the underlying shell
 * <li><strong>No background process management:</strong> A child started with {@code &} is not tracked after the shell
 * exits (output it produces after the parent exits is not captured); anything it writes before then is captured
 * </ul>
 *
 * <h2>Example: Production Usage Pattern</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     public class CommandExecutor {
 *         private final LocalShell shell;
 *         private final ExecutionOptions defaultOptions;
 *
 *         public CommandExecutor() {
 *             this.shell = new LocalShell(Runtime.getRuntime().availableProcessors(), Paths.get("/var/app"), "bash");
 *             this.defaultOptions = ExecutionOptions.builder().timeout(Duration.ofMinutes(5))
 *                     .charset(StandardCharsets.UTF_8).build();
 *         }
 *
 *         public Result executeCommand(String command) {
 *             try {
 *                 ShellCommandResult result = shell.execute(() -> command, defaultOptions);
 *
 *                 if (result.isSuccess()) {
 *                     return Result.success(result.stdout());
 *                 } else {
 *                     return Result.failure(result.exitCode(), result.stderr());
 *                 }
 *             } catch (ShellTimeoutException e) {
 *                 return Result.timeout(e.timeout(), e.stdout());
 *             } catch (ShellExecutionException e) {
 *                 return Result.error(e);
 *             }
 *         }
 *
 *         public void close() {
 *             shell.close();
 *         }
 *     }
 * }
 * </pre>
 *
 * @see at.aimon.core.shell.impl.local.LocalShell
 * @see at.aimon.core.shell.VirtualShell
 * @see at.aimon.core.shell
 * @since 1.0.0
 */
package at.aimon.core.shell.impl.local;
