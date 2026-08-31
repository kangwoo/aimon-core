package at.aimon.core.filesystem.exception;

import java.io.Serial;

/** Thrown when storage is full or quota is exceeded. */
public class InsufficientStorageException extends VirtualFileSystemException {
    @Serial
    private static final long serialVersionUID = 1155330395589849857L;

    /** InsufficientStorageException을 생성한다. */
    public InsufficientStorageException(String message) {
        super(message);
    }
}
