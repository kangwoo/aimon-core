package at.aimon.core.agent.impl.orca.environment;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.shell.VirtualShell;

/**
 * Defines the execution environment for Orca agents.
 *
 * <p>
 * An execution environment provides access to:
 *
 * <ul>
 * <li>{@link VirtualFileSystem} - Abstract file system operations
 * <li>{@link VirtualShell} - Abstract shell command execution
 * </ul>
 *
 * <p>
 * This abstraction allows agents to operate on different backends (local file system, remote file system, sandboxed
 * environment, etc.) without changing the agent code.
 *
 * <p>
 * Implementations should be thread-safe if they will be used by multiple agents concurrently.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualExecutionEnvironment environment = new LocalExecutionEnvironment(config);
 *
 *     // Use file system
 *     VirtualFileSystem fs = environment.fileSystem();
 *     fs.write("/path/to/file.txt", "content");
 *
 *     // Use shell
 *     VirtualShell shell = environment.shell();
 *     String output = shell.execute("ls -la");
 * }
 * </pre>
 *
 * @see LocalExecutionEnvironment
 * @see VirtualFileSystem
 * @see VirtualShell
 */
public interface VirtualExecutionEnvironment {

    /**
     * Returns the virtual file system for this execution environment.
     *
     * <p>
     * The file system provides operations for reading, writing, and manipulating files and directories.
     *
     * @return the virtual file system (never null)
     */
    VirtualFileSystem fileSystem();

    /**
     * Returns the virtual shell for this execution environment.
     *
     * <p>
     * The shell provides operations for executing shell commands and scripts.
     *
     * @return the virtual shell (never null)
     */
    VirtualShell shell();

}
