package at.aimon.core.filesystem.exception;

import java.io.Serial;
import java.util.Objects;

import at.aimon.core.filesystem.BackendType;

/**
 * Thrown when attempting to access a file that does not exist. Includes contextual information about the path and
 * backend type for better debugging.
 */
public class FileNotFoundException extends VirtualFileSystemException {
    @Serial
    private static final long serialVersionUID = 6632121864476921964L;

    private final String path;
    private final BackendType backendType;

    /**
     * Creates a new FileNotFoundException.
     *
     * @param path
     *            The path that was not found
     * @param backendType
     *            The backend type where the file was not found
     */
    public FileNotFoundException(String path, BackendType backendType) {
        super("File not found: " + path + " (backend: " + backendType + ")");
        this.path = Objects.requireNonNull(path, "Path cannot be null");
        this.backendType = Objects.requireNonNull(backendType, "BackendType cannot be null");
    }

    /**
     * Creates a new FileNotFoundException without backend type information. Use this constructor when backend type is
     * not available or not relevant.
     *
     * @param path
     *            The path that was not found
     */
    public FileNotFoundException(String path) {
        super("File not found: " + path);
        this.path = Objects.requireNonNull(path, "Path cannot be null");
        this.backendType = null;
    }

    /**
     * Returns the path that was not found.
     *
     * @return The file path
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the backend type where the file was not found, if available.
     *
     * @return The backend type, or null if not available
     */
    public BackendType getBackendType() {
        return backendType;
    }
}
