package at.aimon.core.filesystem.exception;

import java.io.Serial;

/** Thrown when a file path is invalid or contains illegal characters. */
public class InvalidPathException extends VirtualFileSystemException {
    @Serial
    private static final long serialVersionUID = -5786731293981662789L;

    /** InvalidPathException을 생성한다. */
    public InvalidPathException(String path, String reason) {
        super("Invalid path '" + path + "': " + reason);
    }
}
