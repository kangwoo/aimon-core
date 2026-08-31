package at.aimon.core.filesystem.exception;

import java.io.Serial;

/** Thrown when configuration is invalid or required properties are missing. */
public class ConfigurationException extends VirtualFileSystemException {
    @Serial
    private static final long serialVersionUID = -2484667369473713494L;

    /** ConfigurationException을 생성한다. */
    public ConfigurationException(String message) {
        super(message);
    }
}
