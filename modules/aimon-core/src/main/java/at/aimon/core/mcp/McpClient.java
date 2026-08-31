package at.aimon.core.mcp;

import java.util.List;
import java.util.Map;

import at.aimon.core.mcp.exception.McpInitializeException;
import at.aimon.core.mcp.exception.McpTransportException;

/**
 * Protocol-level communication interface with an MCP server.
 *
 * <p>
 * Abstracts the MCP protocol operations: initialize, tools/list, and tools/call. Independent of the transport method
 * (stdio, SSE, Streamable HTTP).
 *
 * <h2>MCP Protocol Initialization</h2>
 * <p>
 * After connection, the MCP protocol requires an {@code initialize} handshake where client and server exchange
 * supported
 * capabilities. Call {@link #initialize()} explicitly to perform initialization. Calling other methods before
 * initialization throws {@link IllegalStateException}.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * Implementations must be thread-safe. Multiple {@link McpTool} instances share the same McpClient and may call
 * {@link #callTool} concurrently.
 *
 * <h2>Lifecycle</h2>
 * <p>
 * McpClient lifecycle matches {@code AgentRuntime}. Created via {@link McpClientFactory} when the Context is
 * created, and cleaned up via {@link McpClientManager#closeAll()} when the Context is destroyed.
 *
 * <h2>Post-close Behavior</h2>
 * <p>
 * After {@link #close()} is called, {@link #isConnected()} must immediately return {@code false}.
 * {@link McpTool#execute} checks connection status before calling {@code callTool}, so this guarantee prevents tool
 * calls to a closing client.
 *
 * @see McpClientFactory
 * @see McpClientManager
 * @see McpTool
 */
public interface McpClient extends AutoCloseable {

    /**
     * Performs MCP protocol initialization (handshake).
     *
     * <p>
     * Exchanges capabilities between client and server. This method must be called before any other method.
     *
     * @return server capability information
     * @throws McpInitializeException
     *             if initialization fails
     */
    McpServerCapabilities initialize();

    /**
     * Lists the tools provided by the server.
     *
     * @return tool schema list (immutable)
     * @throws IllegalStateException
     *             if not initialized
     */
    List<McpToolSchema> listTools();

    /**
     * Executes a specific tool.
     *
     * <p>
     * MCP protocol-level errors (server responds with {@code isError: true}) are returned as
     * {@link McpCallResult#error(String)}. Transport-level failures (network disconnection, timeout) throw
     * {@link McpTransportException}.
     *
     * <p>
     * This distinction matters in {@link McpTool#execute}:
     * <ul>
     * <li>{@code McpCallResult.error} - server responded normally but the tool execution failed (retry is pointless)
     * <li>{@code McpTransportException} - communication failure (retryable after reconnection)
     * </ul>
     *
     * @param toolName
     *            the name of the tool to execute
     * @param arguments
     *            input parameters
     * @return execution result (protocol-level errors expressed via {@link McpCallResult#isError()})
     * @throws IllegalStateException
     *             if not initialized
     * @throws McpTransportException
     *             if transport-level communication fails
     */
    McpCallResult callTool(String toolName, Map<String, Object> arguments);

    /**
     * Returns the identifying name of the MCP server.
     *
     * @return server name (e.g., "github", "slack")
     */
    String getServerName();

    /**
     * Checks the server connection status.
     *
     * @return true if connected and initialization is complete
     */
    boolean isConnected();

}
