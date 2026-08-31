/**
 * Execution environment abstractions and implementations for the Orca agent system.
 *
 * <p>
 * This package provides abstractions for the execution environment that agents operate in. An execution environment
 * consists of a file system and a shell, both abstracted through virtual interfaces.
 *
 * <h2>Abstraction Benefits</h2>
 *
 * <p>
 * By abstracting the execution environment, agents can operate on different backends without changing their code:
 *
 * <ul>
 * <li><b>Local file system</b> - Standard file system access on the host machine
 * <li><b>Remote file system</b> - Access to remote file systems over network protocols
 * <li><b>Sandboxed environment</b> - Restricted file system and shell for security
 * <li><b>Cloud storage</b> - Integration with cloud storage services (S3, GridFS, etc.)
 * <li><b>In-memory file system</b> - Temporary file system for testing or ephemeral tasks
 * </ul>
 *
 * <h2>Core Components</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.impl.orca.environment.VirtualExecutionEnvironment} - Interface defining the execution
 * environment
 * <li>{@link at.aimon.core.agent.impl.orca.environment.LocalExecutionEnvironment} - Local file system and shell
 * implementation
 * </ul>
 *
 * <h2>VirtualExecutionEnvironment</h2>
 *
 * <p>
 * The {@link at.aimon.core.agent.impl.orca.environment.VirtualExecutionEnvironment} interface provides:
 *
 * <ul>
 * <li>{@link at.aimon.core.filesystem.VirtualFileSystem} - Abstract file system operations (read, write, delete, list,
 * etc.)
 * <li>{@link at.aimon.core.shell.VirtualShell} - Abstract shell command execution
 * </ul>
 *
 * <h2>LocalExecutionEnvironment</h2>
 *
 * <p>
 * The {@link at.aimon.core.agent.impl.orca.environment.LocalExecutionEnvironment} provides access to:
 *
 * <ul>
 * <li>{@link at.aimon.core.filesystem.impl.local.LocalFileSystem} - Standard Java NIO file operations on the local file
 * system
 * <li>{@link at.aimon.core.shell.impl.local.LocalShell} - Process-based shell command execution on the local machine
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create local execution environment
 *     LocalFileSystemConfig config = LocalFileSystemConfig.builder().basePath("/path/to/workspace").build();
 *     VirtualExecutionEnvironment environment = new LocalExecutionEnvironment(config);
 *
 *     // Use file system
 *     VirtualFileSystem fs = environment.fileSystem();
 *     fs.write("hello.txt", "Hello, World!");
 *     String content = fs.read("hello.txt");
 *
 *     // Use shell
 *     VirtualShell shell = environment.shell();
 *     String output = shell.execute("ls -la");
 *     System.out.println(output);
 * }
 * </pre>
 *
 * <h2>Creating Custom Environments</h2>
 *
 * <p>
 * To create a custom execution environment:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class DockerExecutionEnvironment implements VirtualExecutionEnvironment {
 *         private final DockerFileSystem fileSystem;
 *         private final DockerShell shell;
 *
 *         public DockerExecutionEnvironment(DockerConfig config) {
 *             this.fileSystem = new DockerFileSystem(config);
 *             this.shell = new DockerShell(config);
 *         }
 *
 *         &#64;Override
 *         public VirtualFileSystem fileSystem() {
 *             return fileSystem;
 *         }
 *
 *         &#64;Override
 *         public VirtualShell shell() {
 *             return shell;
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Security Considerations</h2>
 *
 * <p>
 * When implementing custom execution environments, consider:
 *
 * <ul>
 * <li><b>Sandboxing</b> - Restrict file system access to specific directories
 * <li><b>Command whitelisting</b> - Only allow specific shell commands
 * <li><b>Resource limits</b> - Limit CPU, memory, and disk usage
 * <li><b>Network isolation</b> - Control network access for shell commands
 * <li><b>Timeout enforcement</b> - Prevent long-running operations
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * Implementations should be thread-safe if they will be used by multiple agents concurrently. The
 * {@link at.aimon.core.agent.impl.orca.environment.LocalExecutionEnvironment} is thread-safe as long as the underlying
 * file
 * system and shell implementations are thread-safe.
 *
 * @see at.aimon.core.filesystem.VirtualFileSystem
 * @see at.aimon.core.shell.VirtualShell
 */
package at.aimon.core.agent.impl.orca.environment;
