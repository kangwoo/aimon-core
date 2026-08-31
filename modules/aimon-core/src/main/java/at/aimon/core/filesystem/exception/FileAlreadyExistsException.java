package at.aimon.core.filesystem.exception;

import java.io.Serial;
import java.util.Objects;

import at.aimon.core.filesystem.BackendType;

/**
 * Thrown when attempting to create a file that already exists (for non-overwrite operations). Includes contextual
 * information about the path and backend type for better debugging.
 */
public class FileAlreadyExistsException extends VirtualFileSystemException {
    @Serial
    private static final long serialVersionUID = 7433901086117349066L;

    private final String path;
    private final BackendType backendType;

    /**
     * Creates a new FileAlreadyExistsException.
     *
     * @param path
     *            The path that already exists
     * @param backendType
     *            The backend type where the file already exists
     */
    public FileAlreadyExistsException(String path, BackendType backendType) {
        super("File already exists: " + path + " (backend: " + backendType + ")");
        this.path = Objects.requireNonNull(path, "Path cannot be null");
        this.backendType = Objects.requireNonNull(backendType, "BackendType cannot be null");
    }

    /**
     * Creates a new FileAlreadyExistsException without backend type information. Use this constructor when backend type
     * is not available or not relevant.
     *
     * @param path
     *            The path that already exists
     */
    public FileAlreadyExistsException(String path) {
        super("File already exists: " + path);
        this.path = Objects.requireNonNull(path, "Path cannot be null");
        this.backendType = null;
    }

    /**
     * Returns the path that already exists.
     *
     * @return The file path
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the backend type where the file already exists, if available.
     *
     * @return The backend type, or null if not available
     */
    public BackendType getBackendType() {
        return backendType;
    }
}
