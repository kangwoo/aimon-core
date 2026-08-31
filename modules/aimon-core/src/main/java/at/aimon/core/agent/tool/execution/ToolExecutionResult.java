package at.aimon.core.agent.tool.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;

import at.aimon.core.agent.tool.ToolResult;

/**
 * Represents the result of a tool execution with execution ID.
 *
 * <p>
 * This class wraps a {@link ToolResult} along with the execution ID (tool use ID) to track which tool invocation
 * produced this result. It preserves all information from the original ToolResult including optional exception details
 * for debugging and the optional sidecar render payload.
 *
 * <h2>Design Rationale</h2>
 * <ul>
 * <li><strong>toolExecutionId</strong>: Links the result back to the specific tool invocation
 * <li><strong>content</strong>: Human-readable message for LLM consumption
 * <li><strong>exception</strong>: Original exception for debugging and type-specific error handling (optional)
 * <li><strong>renderPayload</strong>: Opaque sidecar payload for non-LLM consumers (optional); excluded from Jackson
 * serialization
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // From ToolResult
 *     ToolResult toolResult = ToolResult.success("Operation completed");
 *     ToolExecutionResult result = ToolExecutionResult.of("tool_123", toolResult);
 *
 *     // Direct construction
 *     ToolExecutionResult success = ToolExecutionResult.success("tool_456", "File created");
 *     ToolExecutionResult error = ToolExecutionResult.error("tool_789", "File not found");
 *
 *     // With exception
 *     try {
 *         // ... operation ...
 *     } catch (IOException e) {
 *         ToolResult toolResult = ToolResult.error("IO error occurred", e);
 *         ToolExecutionResult result = ToolExecutionResult.of("tool_999", toolResult);
 *         // Exception is preserved for debugging
 *         result.getException().ifPresent(ex -> logger.error("Tool failed", ex));
 *     }
 * }
 * </pre>
 *
 * <p>
 * This class is immutable and thread-safe.
 *
 * @see ToolResult
 * @see ToolExecutionRequest
 * @see ToolExecutionContext
 */
public final class ToolExecutionResult {

    private final String toolExecutionId;
    private final String content;
    private final boolean isError;
    private final Exception exception;
    private final Map<String, Object> renderPayload;

    private ToolExecutionResult(String toolExecutionId, ToolResult toolResult) {
        this(toolExecutionId, toolResult.getContent(), toolResult.isError(), toolResult.getException().orElse(null),
                toolResult.getRenderPayload());
    }

    private ToolExecutionResult(String toolExecutionId, String content, boolean isError) {
        this(toolExecutionId, content, isError, null, null);
    }

    private ToolExecutionResult(String toolExecutionId, String content, boolean isError, Exception exception) {
        this(toolExecutionId, content, isError, exception, null);
    }

    private ToolExecutionResult(String toolExecutionId, String content, boolean isError, Exception exception,
            Map<String, Object> renderPayload) {
        this.toolExecutionId = Objects.requireNonNull(toolExecutionId, "ToolExecution ID cannot be null");
        this.content = Objects.requireNonNull(content, "Content cannot be null");
        this.isError = isError;
        this.exception = exception;
        this.renderPayload = renderPayload;
    }

    /**
     * Gets the tool execution ID (tool use ID).
     *
     * <p>
     * This ID links the result back to the specific tool invocation request.
     *
     * @return The tool execution ID (never null)
     */
    public String getToolExecutionId() {
        return toolExecutionId;
    }

    /**
     * Creates a ToolExecutionResult from a tool use ID and ToolResult.
     *
     * <p>
     * This factory method preserves all information from the ToolResult including optional exception details and the
     * sidecar render payload.
     *
     * @param toolUseId
     *            The tool use ID (must not be null)
     * @param toolResult
     *            The tool result (must not be null)
     * @return A new ToolExecutionResult
     * @throws NullPointerException
     *             if toolUseId or toolResult is null
     */
    public static ToolExecutionResult of(String toolUseId, ToolResult toolResult) {
        Objects.requireNonNull(toolResult, "ToolResult cannot be null");
        return new ToolExecutionResult(toolUseId, toolResult);
    }

    /**
     * Creates a successful tool execution result.
     *
     * @param toolExecutionId
     *            The tool execution ID (must not be null)
     * @param content
     *            The success content (must not be null)
     * @return A new successful ToolExecutionResult
     * @throws NullPointerException
     *             if toolExecutionId or content is null
     */
    public static ToolExecutionResult success(String toolExecutionId, String content) {
        return new ToolExecutionResult(toolExecutionId, content, false);
    }

    /**
     * Creates an error tool execution result.
     *
     * @param toolExecutionId
     *            The tool execution ID (must not be null)
     * @param errorMessage
     *            The error message (must not be null)
     * @return A new error ToolExecutionResult
     * @throws NullPointerException
     *             if toolExecutionId or errorMessage is null
     */
    public static ToolExecutionResult error(String toolExecutionId, String errorMessage) {
        return new ToolExecutionResult(toolExecutionId, errorMessage, true);
    }

    /**
     * Creates an error tool execution result with an exception.
     *
     * @param toolExecutionId
     *            The tool execution ID (must not be null)
     * @param errorMessage
     *            The error message (must not be null)
     * @param exception
     *            The original exception (nullable)
     * @return A new error ToolExecutionResult
     * @throws NullPointerException
     *             if toolExecutionId or errorMessage is null
     */
    public static ToolExecutionResult error(String toolExecutionId, String errorMessage, Exception exception) {
        return new ToolExecutionResult(toolExecutionId, errorMessage, true, exception);
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
     * The exception is only present for error results that were created from a {@link ToolResult} with an exception.
     * Use this for:
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
     * Gets the sidecar render payload attached to the originating {@link ToolResult}.
     *
     * <p>
     * Excluded from Jackson serialization by {@link JsonIgnore} to preserve the invariant that the payload does not
     * reach LLM-facing wire formats or persisted conversation history.
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
        final ToolExecutionResult that = (ToolExecutionResult) o;
        return isError == that.isError && Objects.equals(toolExecutionId, that.toolExecutionId)
                && Objects.equals(content, that.content) && Objects.equals(exception, that.exception)
                && Objects.equals(renderPayload, that.renderPayload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolExecutionId, content, isError, exception, renderPayload);
    }

    @Override
    public String toString() {
        String exceptionInfo = exception != null
                ? ", exception=" + exception.getClass().getSimpleName() + ": " + exception.getMessage()
                : "";
        String renderInfo = renderPayload != null ? ", renderPayload=" + renderPayload.keySet() : "";
        return "ToolExecutionResult{toolExecutionId='" + toolExecutionId + "', isError=" + isError + ", content='"
                + content + "'" + exceptionInfo + renderInfo + "}";
    }
}
