package at.aimon.core.filesystem.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Base unchecked exception for all file system errors. All file system exceptions extend this class.
 */
public class VirtualFileSystemException extends AimonException {
    @Serial
    private static final long serialVersionUID = -7058291324817427382L;

    /** VirtualFileSystemException을 생성한다. */
    public VirtualFileSystemException(String message) {
        super(message);
    }

    /** VirtualFileSystemException을 생성한다. */
    public VirtualFileSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
