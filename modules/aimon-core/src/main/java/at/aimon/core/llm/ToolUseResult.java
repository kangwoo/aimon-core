package at.aimon.core.llm;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Represents the result of a tool execution.
 *
 * <p>
 * Contains the tool use ID and execution result, which can be either successful content or an error message.
 *
 * <p>
 * This is an immutable value object used to communicate tool execution results back to the LLM.
 *
 * <p>
 * An optional sidecar {@link #getRenderPayload() render payload} can be attached via
 * {@link #withRenderPayload(java.util.Map)}. The payload is available to {@code PostToolHook} implementations but is
 * excluded from Jackson serialization — it must never appear in the LLM-facing wire format or in the conversation
 * persistence path.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Success case
 *     ToolUseResult success = ToolUseResult.success("tool_abc123", "File created successfully");
 *
 *     // Error case
 *     ToolUseResult error = ToolUseResult.error("tool_xyz789", "Permission denied");
 *
 *     // Check result status
 *     if (result.isSuccess()) {
 *         System.out.println(result.getContent());
 *     }
 *
 *     // Access sidecar payload (hook side)
 *     Map&lt;String, Object&gt; payload = result.getRenderPayload(); // may be null
 * }
 * </pre>
 */
public final class ToolUseResult {
    /**
     * Creates a successful tool execution result.
     *
     * @param toolUseId
     *            The unique identifier of the tool use (must not be null)
     * @param content
     *            The result content (must not be null)
     * @return A new successful ToolUseResult
     * @throws NullPointerException
     *             if toolUseId or content is null
     */
    public static ToolUseResult success(String toolUseId, String content) {
        return new ToolUseResult(toolUseId, content, false, null);
    }

    /**
     * Creates an error tool execution result.
     *
     * @param toolUseId
     *            The unique identifier of the tool use (must not be null)
     * @param errorMessage
     *            The error message (must not be null)
     * @return A new error ToolUseResult
     * @throws NullPointerException
     *             if toolUseId or errorMessage is null
     */
    public static ToolUseResult error(String toolUseId, String errorMessage) {
        return new ToolUseResult(toolUseId, errorMessage, true, null);
    }

    private final String toolUseId;
    private final String content;
    private final boolean isError;
    private final Map<String, Object> renderPayload;

    /**
     * Creates a new ToolUseResult.
     *
     * @param toolUseId
     *            The unique identifier of the tool use (must not be null)
     * @param content
     *            The result content or error message (must not be null)
     * @param isError
     *            Whether this represents an error
     * @param renderPayload
     *            Optional sidecar payload for non-LLM consumers (nullable)
     * @throws NullPointerException
     *             if toolUseId or content is null
     */
    private ToolUseResult(String toolUseId, String content, boolean isError, Map<String, Object> renderPayload) {
        this.toolUseId = Objects.requireNonNull(toolUseId, "ToolUse ID cannot be null");
        this.content = Objects.requireNonNull(content, "Content cannot be null");
        this.isError = isError;
        this.renderPayload = renderPayload;
    }

    /**
     * Returns a copy of this result with the given sidecar render payload attached.
     *
     * <p>
     * The payload is available to hook consumers via {@link #getRenderPayload()} but is never serialized to LLM
     * providers or to the conversation persistence path (enforced by {@link JsonIgnore}).
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
     * @return a new ToolUseResult instance with the payload set
     * @throws NullPointerException
     *             if the non-null {@code payload} contains a {@code null} key or value
     */
    public ToolUseResult withRenderPayload(Map<String, Object> payload) {
        Map<String, Object> copy = payload == null ? null : Map.copyOf(payload);
        return new ToolUseResult(toolUseId, content, isError, copy);
    }

    /**
     * Gets the unique identifier of the tool use that generated this result.
     *
     * @return The tool use ID (never null)
     */
    public String getToolUseId() {
        return toolUseId;
    }

    /**
     * Gets the result content or error message.
     *
     * @return The content (never null)
     */
    public String getContent() {
        return content;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ToolUseResult that = (ToolUseResult) o;
        return isError == that.isError && toolUseId.equals(that.toolUseId) && content.equals(that.content)
                && Objects.equals(renderPayload, that.renderPayload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolUseId, content, isError, renderPayload);
    }

    @Override
    public String toString() {
        String renderInfo = renderPayload != null ? ", renderPayload=" + renderPayload.keySet() : "";
        return "ToolUseResult{toolUseId='" + toolUseId + "', isError=" + isError + ", content='" + content + "'"
                + renderInfo + "}";
    }
}
