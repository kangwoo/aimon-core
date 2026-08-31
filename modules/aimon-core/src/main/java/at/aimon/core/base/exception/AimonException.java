package at.aimon.core.base.exception;

import java.io.Serial;
import java.util.Objects;

/**
 * Base runtime exception for all Aimon-related errors.
 *
 * <p>
 * This exception serves as the root exception type for the Aimon library. It extends {@link RuntimeException} to
 * indicate that it represents an unchecked exception that typically indicates programming errors or unrecoverable
 * conditions.
 *
 * <p>
 * Specific exception types should extend this class to provide more granular error handling. For example:
 * <ul>
 * <li>{@code AgentException} for agent-related errors</li>
 * <li>{@code FileSystemException} for filesystem operation errors</li>
 * <li>{@code LlmException} for LLM client errors</li>
 * </ul>
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {@code
 * try {
 *     agent.execute(context, request);
 * } catch (AimonException e) {
 *     logger.error("Agent execution failed: {}", e.getMessage(), e);
 *     // Handle or propagate the exception
 * }
 * }
 * </pre>
 *
 * @see RuntimeException
 */
public class AimonException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 5943229980696775274L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message
     *            the detail message (must not be null)
     * @throws NullPointerException
     *             if message is null
     */
    public AimonException(String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message
     *            the detail message (must not be null)
     * @param cause
     *            the cause (may be null if no underlying cause is available)
     * @throws NullPointerException
     *             if message is null
     */
    public AimonException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
    }

    /**
     * Constructs a new exception with the specified cause.
     *
     * <p>
     * The detail message is set to {@code (cause==null ? null : cause.toString())}, which typically contains the class
     * and detail message of the cause.
     *
     * @param cause
     *            the cause (must not be null)
     * @throws NullPointerException
     *             if cause is null
     */
    public AimonException(Throwable cause) {
        super(Objects.requireNonNull(cause, "cause must not be null"));
    }
}
