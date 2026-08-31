package at.aimon.core.agent.tool;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Represents the result of a tool execution.
 *
 * <p>
 * Contains the execution result which can be either successful content or an error. For error cases, both a
 * human-readable message and the original exception can be stored.
 *
 * <h2>Design Rationale</h2>
 * <ul>
 * <li><strong>content</strong>: Human-readable message for LLM consumption
 * <li><strong>exception</strong>: Original exception for debugging and type-specific handling
 * <li><strong>renderPayload</strong>: Optional sidecar payload for non-LLM consumers (e.g. rendering hooks). Never
 * serialized to LLM-facing or persistence-facing JSON.
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Success case
 *     ToolResult success = ToolResult.success("File created successfully");
 *
 *     // Error with custom message
 *     ToolResult error = ToolResult.error("Permission denied: Cannot write to /etc");
 *
 *     // Error with exception (preserves stack trace for debugging)
 *     try {
 *         fileSystem.write(path, content);
 *     } catch (FileNotFoundException e) {
 *         return ToolResult.error("File not found: " + path, e);
 *     }
 *
 *     // Attach sidecar render payload (invisible to LLM)
 *     ToolResult rendered = ToolResult.success(body)
 *             .withRenderPayload(Map.of("kind", "metric-series", "block", block));
 *
 *     // Type-specific error handling
 *     boolean isSecurityError = result.isError()
 *             &amp;&amp; result.getException()
 *                     .filter(e -&gt; e instanceof SecurityException)
 *                     .isPresent();
 *     if (isSecurityError) {
 *         logger.warn("Security violation detected", result.getException().get());
 *     }
 * }
 * </pre>
 *
 * <p>
 * Immutable value object.
 */
public final class ToolResult {
    private final String content;
    private final boolean isError;
    private final Exception exception;
    private final Map<String, Object> renderPayload;

    /**
     * Creates a new ToolResult.
     *
     * @param content
     *            The result content or error message (must not be null)
     * @param isError
     *            Whether this represents an error
     * @param exception
     *            The original exception (nullable, only for errors)
     * @param renderPayload
     *            Optional sidecar payload for non-LLM consumers (nullable)
     * @throws NullPointerException
     *             if content is null
     */
    private ToolResult(String content, boolean isError, Exception exception, Map<String, Object> renderPayload) {
        this.content = Objects.requireNonNull(content, "Content cannot be null");
        this.isError = isError;
        this.exception = exception;
        this.renderPayload = renderPayload;
    }

    /**
     * Creates a successful tool result.
     *
     * @param content
     *            The result content (must not be null)
     * @return A new successful ToolResult
     * @throws NullPointerException
     *             if content is null
     */
    public static ToolResult success(String content) {
        return new ToolResult(content, false, null, null);
    }

    /**
     * Creates an error tool result with a message only.
     *
     * @param errorMessage
     *            The error message for LLM (must not be null)
     * @return A new error ToolResult
     * @throws NullPointerException
     *             if errorMessage is null
     */
    public static ToolResult error(String errorMessage) {
        return new ToolResult(errorMessage, true, null, null);
    }

    /**
     * Creates an error tool result with both message and exception.
     *
     * <p>
     * The message should be human-readable and suitable for LLM consumption. The exception is preserved for debugging
     * and type-specific error handling.
     *
     * @param errorMessage
     *            The error message for LLM (must not be null)
     * @param exception
     *            The original exception (must not be null)
     * @return A new error ToolResult
     * @throws NullPointerException
     *             if errorMessage or exception is null
     */
    public static ToolResult error(String errorMessage, Exception exception) {
        Objects.requireNonNull(exception, "Exception cannot be null");
        return new ToolResult(errorMessage, true, exception, null);
    }

    /**
     * Creates an error tool result from an exception.
     *
     * <p>
     * Uses the exception's message as the content. Useful for quick error creation. If the exception has no message,
     * uses the exception's simple class name.
     *
     * @param exception
     *            The exception (must not be null)
     * @return A new error ToolResult
     * @throws NullPointerException
     *             if exception is null
     */
    public static ToolResult error(Exception exception) {
        Objects.requireNonNull(exception, "Exception cannot be null");
        String message = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
        return new ToolResult(message, true, exception, null);
    }

    /**
     * Attaches a sidecar render payload to this ToolResult.
     *
     * <p>
     * The payload is delivered to {@code PostToolHook} implementations via
     * {@link at.aimon.core.llm.ToolUseResult#getRenderPayload()} but is excluded from LLM-facing serialization and from
     * the core conversation persistence path. Callers are responsible for persisting the payload out-of-band if
     * required.
     *
     * <p>
     * The payload shape is opaque to aimon-core — downstream hooks determine its interpretation.
     *
     * <p>
     * The supplied map is defensively shallow-copied via {@link Map#copyOf(Map)}; callers must not rely on later
     * mutations of the source map being observed. Because {@code Map.copyOf} rejects {@code null} keys and values, the
     * caller must ensure the payload contains no {@code null} entries — a non-null payload with {@code null} values
     * will cause a {@link NullPointerException} to propagate from this method.
     *
     * @param payload
     *            arbitrary map to attach; {@code null} clears any existing payload, but a non-null map with
     *            {@code null} keys or values is rejected by {@link Map#copyOf(Map)}
     * @return a new ToolResult instance with the payload set
     * @throws NullPointerException
     *             if the non-null {@code payload} contains a {@code null} key or value
     */
    public ToolResult withRenderPayload(Map<String, Object> payload) {
        Map<String, Object> copy = payload == null ? null : Map.copyOf(payload);
        return new ToolResult(content, isError, exception, copy);
    }

    /**
     * Gets the result content or error message.
     *
     * <p>
     * This message is designed to be human-readable and suitable for LLM consumption.
     *
     * @return The content (never null)
     */
    public String getContent() {
        return content;
    }

    /**
     * Gets the original exception if this is an error result.
     *
     * <p>
     * The exception is only present for error results that were created with an exception. Use this for:
     * <ul>
     * <li>Logging with stack traces
     * <li>Type-specific error handling
     * <li>Debugging
     * </ul>
     *
     * <p>
     * <strong>Serialization:</strong> excluded via {@link JsonIgnore} because {@code Optional<Exception>} is not
     * serializable by Jackson without the {@code jdk8} module, and the exception instance carries internal stack trace
     * / debugging state that must not leak into LLM-facing wire formats or persisted conversation history. Use
     * {@link #isError()} and {@link #getContent()} for serializable error signal.
     *
     * @return Optional containing the exception if present
     */
    @JsonIgnore
    public Optional<Exception> getException() {
        return Optional.ofNullable(exception);
    }

    /**
     * Gets the sidecar render payload attached to this result.
     *
     * <p>
     * The payload is excluded from Jackson-based serialization by {@link JsonIgnore} so that it does not leak into
     * LLM-facing wire formats or persisted conversation history.
     *
     * @return The render payload map, or {@code null} if no payload is attached
     */
    @JsonIgnore
    public Map<String, Object> getRenderPayload() {
        return renderPayload;
    }

    /**
     * Checks if this result represents an error.
     *
     * @return true if this is an error result, false otherwise
     */
    public boolean isError() {
        return isError;
    }

    /**
     * Checks if this result represents a success.
     *
     * @return true if this is a successful result, false otherwise
     */
    public boolean isSuccess() {
        return !isError;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ToolResult that = (ToolResult) o;
        return isError == that.isError && content.equals(that.content) && Objects.equals(exception, that.exception)
                && Objects.equals(renderPayload, that.renderPayload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, isError, exception, renderPayload);
    }

    @Override
    public String toString() {
        String exceptionInfo = exception != null
                ? ", exception=" + exception.getClass().getSimpleName() + ": " + exception.getMessage()
                : "";
        String renderInfo = renderPayload != null ? ", renderPayload=" + renderPayload.keySet() : "";
        return "ToolResult{isError=" + isError + ", content='" + content + "'" + exceptionInfo + renderInfo + "}";
    }
}
