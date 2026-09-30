package at.aimon.core.filesystem.exception;

import java.io.Serial;

/**
 * Thrown when a path rule forbids an operation: the path is hidden ({@code DENY}) or may only be read
 * ({@code READ_ONLY}).
 */
public class FileAccessDeniedException extends VirtualFileSystemException {
    @Serial
    private static final long serialVersionUID = 6012893517734690912L;

    /**
     * @param path
     *            the path the operation targeted
     * @param reason
     *            why it was refused
     */
    public FileAccessDeniedException(String path, String reason) {
        super("Access denied: " + path + " (" + reason + ")");
    }
}
