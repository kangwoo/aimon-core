package at.aimon.core.filesystem.exception;

import java.io.Serial;

import at.aimon.core.filesystem.BackendType;

/**
 * Thrown when backend connection fails or backend is unavailable. Includes contextual information about the backend
 * type for better debugging.
 */
public class BackendConnectionException extends VirtualFileSystemException {
    @Serial
    private static final long serialVersionUID = -5885218702712279922L;

    private final BackendType backendType;

    /**
     * Creates a new BackendConnectionException with backend type information.
     *
     * @param backendType
     *            The backend type that failed to connect
     * @param cause
     *            The underlying cause of the connection failure
     */
    public BackendConnectionException(BackendType backendType, Throwable cause) {
        super("Failed to connect to backend: " + backendType, cause);
        this.backendType = backendType;
    }

    /**
     * Creates a new BackendConnectionException with a custom message. Use this constructor when backend type is not
     * available or when a custom error message is needed.
     *
     * @param message
     *            The error message
     */
    public BackendConnectionException(String message) {
        super(message);
        this.backendType = null;
    }

    /**
     * Creates a new BackendConnectionException with backend type and custom message.
     *
     * @param backendType
     *            The backend type that failed to connect
     * @param message
     *            The error message
     * @param cause
     *            The underlying cause of the connection failure
     */
    public BackendConnectionException(BackendType backendType, String message, Throwable cause) {
        super(message, cause);
        this.backendType = backendType;
    }

    /**
     * Returns the backend type that failed to connect, if available.
     *
     * @return The backend type, or null if not available
     */
    public BackendType getBackendType() {
        return backendType;
    }
}
