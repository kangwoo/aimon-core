package at.aimon.core.agent.impl.orca.environment;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;

/**
 * Local implementation of {@link VirtualExecutionEnvironment}.
 *
 * <p>
 * This implementation provides access to the local file system and shell. It uses {@link LocalFileSystem} for file
 * operations and {@link LocalShell} for shell command execution.
 *
 * <p>
 * All operations are performed on the actual local file system starting from the configured base path.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     LocalFileSystemConfig config = LocalFileSystemConfig.builder().basePath("/path/to/workspace").build();
 *     LocalExecutionEnvironment environment = new LocalExecutionEnvironment(config);
 *
 *     VirtualFileSystem fs = environment.fileSystem();
 *     VirtualShell shell = environment.shell();
 * }
 * </pre>
 *
 * @see VirtualExecutionEnvironment
 * @see LocalFileSystem
 * @see LocalShell
 */
public final class LocalExecutionEnvironment implements VirtualExecutionEnvironment {

    private final LocalFileSystem fileSystem;
    private final LocalShell shell;

    /**
     * Creates a new local execution environment.
     *
     * @param localFileSystemConfig
     *            the file system configuration (must not be null)
     * @throws NullPointerException
     *             if localFileSystemConfig is null
     */
    public LocalExecutionEnvironment(LocalFileSystemConfig localFileSystemConfig) {
        fileSystem = new LocalFileSystem(localFileSystemConfig);
        shell = new LocalShell(localFileSystemConfig.getBasePath());
    }

    /**
     * Returns the local file system.
     *
     * @return the local file system (never null)
     */
    @Override
    public VirtualFileSystem fileSystem() {
        return fileSystem;
    }

    /**
     * Returns the local shell.
     *
     * @return the local shell (never null)
     */
    @Override
    public VirtualShell shell() {
        return shell;
    }

}
