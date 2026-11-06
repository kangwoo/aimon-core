package at.aimon.core.environment;

import java.io.IOException;

/**
 * The framework's own file access inside an execution environment, outside the path rules the model's file tools go
 * through ({@code docs/design/tool/programmatic-tool-calling-shell.md} §3, decision 3).
 *
 * <p>
 * {@link ExecutionEnvironment#fileSystem()} is the model's: every path passes the environment's path rules, and it has
 * no operation that creates a file only if it does not exist. The shell engine of {@code ExecuteCode} needs exactly
 * that — it answers the runtime's tool calls with reply files a co-resident process must not be able to pre-empt or
 * swap — and it needs it on paths the model's file tools are denied ({@code .aimon-ptc/}). This interface is that
 * access. It is <b>never handed to a model tool</b>.
 *
 * <p>
 * Paths are absolute, in the environment's own namespace — the paths its shell sees (a path under
 * {@link EnvironmentDescriptor#workingDirectory()}). An implementation may confine them further and refuses a path it
 * does not serve with an {@link IOException}.
 *
 * <p>
 * Implementations are thread-safe. The local one ({@code at.aimon.core.environment.impl.LocalRuntimeFileSystem},
 * {@code O_EXCL} creation) is what the engine contract suite runs the shell engine on with a local shell; the local
 * execution environment does not hand it out, because the shell engine does not run on the host (shell doc decision 1).
 *
 * @see ExecutionEnvironment#runtimeFileSystem()
 */
public interface RuntimeFileSystem {

    /**
     * Creates {@code path} with {@code bytes} as its whole content, atomically, only if nothing exists at
     * {@code path}: a reader sees either no file or the complete one, never a part, and of two writers to the same
     * path exactly one succeeds. The parent directory must exist.
     *
     * @param path
     *            the absolute path of the new file (must not be null)
     * @param bytes
     *            the content (must not be null)
     * @throws java.nio.file.FileAlreadyExistsException
     *             if anything — a file, a directory, a link — already exists at {@code path}; nothing is changed
     * @throws IOException
     *             if the file cannot be written; nothing is left at {@code path}
     */
    void writeNew(String path, byte[] bytes) throws IOException;

    /**
     * Reads a regular file, at most {@code maxBytes} of it, without following a symbolic link at {@code path}.
     *
     * @param path
     *            the absolute path of the file (must not be null)
     * @param maxBytes
     *            the most to read (non-negative)
     * @return the file's first {@code maxBytes} bytes, or all of them when it is smaller (never null)
     * @throws java.nio.file.NoSuchFileException
     *             if nothing exists at {@code path}
     * @throws IOException
     *             if {@code path} is a link, not a regular file, or cannot be read
     */
    byte[] read(String path, int maxBytes) throws IOException;

    /**
     * Deletes the file or the directory tree at {@code path}, without following links (a link is deleted, not its
     * target). Nothing at {@code path} is not an error.
     *
     * @param path
     *            the absolute path (must not be null)
     * @throws IOException
     *             if something at {@code path} remains
     */
    void delete(String path) throws IOException;

    /**
     * Creates the directory at {@code path} and any missing parents. An existing directory is not an error.
     *
     * @param path
     *            the absolute path of the directory (must not be null)
     * @throws IOException
     *             if the directory cannot be created, or something other than a directory is in the way
     */
    void createDirectories(String path) throws IOException;
}
