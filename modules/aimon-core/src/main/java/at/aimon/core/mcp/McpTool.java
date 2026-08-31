package at.aimon.core.mcp;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Adapter that wraps an MCP server's tool as an AIMON Tool.
 *
 * <p>
 * From the Agent's perspective, this is used identically to local Tools. Tool names follow the
 * {@code mcp__<serverName>__<toolName>} format.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is thread-safe. It does not modify internal state and relies on the thread-safety of the shared
 * {@link McpClient}.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {@code
 * McpClient client = new DefaultMcpClient(transport, "github");
 * McpToolSchema schema = McpToolSchema.of("create_issue", "Create a GitHub issue", inputSchema);
 * Tool tool = new McpTool("github", schema, client);
 * // tool.getDefinition().getName() -> "mcp__github__create_issue"
 * }
 * </pre>
 */
public class McpTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(McpTool.class);

    private final McpClient mcpClient;
    private final String mcpToolName;
    private final McpToolTraits traits;

    /**
     * Creates an McpTool that declares the conservative end of both side-effect axes, whatever the server claimed.
     *
     * @param serverName
     *            MCP server identifying name
     * @param schema
     *            tool definition provided by the MCP server
     * @param mcpClient
     *            MCP client (must be thread-safe)
     */
    public McpTool(String serverName, McpToolSchema schema, McpClient mcpClient) {
        this(serverName, schema, mcpClient, McpToolTraits.untrusted());
    }

    /**
     * Creates an McpTool with pre-resolved declarations.
     *
     * <p>
     * The traits are resolved by the caller rather than derived from {@code schema} here, because the deciding input is
     * not the schema but the trust configured for the server it came from — which this class has no reference to. See
     * {@link McpToolTraits#resolve(McpToolAnnotations, McpServerConfig.AnnotationTrust)}.
     *
     * @param serverName
     *            MCP server identifying name
     * @param schema
     *            tool definition provided by the MCP server
     * @param mcpClient
     *            MCP client (must be thread-safe)
     * @param traits
     *            the declarations this tool will make (must not be null)
     */
    public McpTool(String serverName, McpToolSchema schema, McpClient mcpClient, McpToolTraits traits) {
        super(formatToolName(serverName, schema.getName()), schema.getDescription(), schema.getInputSchema());
        this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient cannot be null");
        this.mcpToolName = schema.getName();
        this.traits = Objects.requireNonNull(traits, "traits cannot be null");
    }

    private static String formatToolName(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        return traits.getSideEffectLevel();
    }

    @Override
    public DestructiveBehavior getDestructiveBehavior() {
        return traits.getDestructiveBehavior();
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            if (!mcpClient.isConnected()) {
                return ToolResult.error("MCP server '" + mcpClient.getServerName() + "' is not connected");
            }

            McpCallResult result = mcpClient.callTool(mcpToolName, input.toMap());

            if (result.isError()) {
                log.warn("MCP tool '{}' returned error: {}", mcpToolName, result.getContent());
                return ToolResult.error(result.getContent());
            }

            log.debug("MCP tool '{}' executed successfully", mcpToolName);
            return ToolResult.success(result.getContent());

        } catch (Exception e) {
            log.error("MCP tool '{}' execution failed: {}", mcpToolName, e.getMessage(), e);
            return ToolResult.error("MCP tool execution failed: " + e.getMessage());
        }
    }

}
