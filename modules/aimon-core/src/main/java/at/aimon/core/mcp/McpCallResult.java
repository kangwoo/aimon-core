package at.aimon.core.mcp;

import java.util.Objects;

/**
 * MCP tool call result.
 *
 * <p>
 * An immutable object containing the result of a MCP protocol {@code tools/call} response. It is converted to
 * {@link at.aimon.core.agent.tool.ToolResult} before being returned to the Agent.
 */
public final class McpCallResult {

    private final String content;
    private final boolean isError;

    private McpCallResult(String content, boolean isError) {
        this.content = Objects.requireNonNull(content, "content cannot be null");
        this.isError = isError;
    }

    /** Creates a successful result. */
    public static McpCallResult success(String content) {
        return new McpCallResult(content, false);
    }

    /** Creates an error result. */
    public static McpCallResult error(String content) {
        return new McpCallResult(content, true);
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return isError;
    }

}
