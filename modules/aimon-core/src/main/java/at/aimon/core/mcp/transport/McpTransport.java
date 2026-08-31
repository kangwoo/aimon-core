package at.aimon.core.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import at.aimon.core.mcp.exception.McpTransportException;

/**
 * Transport layer interface for MCP server communication.
 *
 * <p>
 * Responsible for sending and receiving JSON-RPC messages. Protocol interpretation is performed by
 * {@link at.aimon.core.mcp.McpClient}.
 *
 * <p>
 * Built-in implementations:
 * <ul>
 * <li>{@link StdioMcpTransport} - local process stdin/stdout communication
 * </ul>
 *
 * <p>
 * The {@code McpTransportType.SSE} and {@code STREAMABLE_HTTP} enum values are reserved for future
 * remote-transport implementations; configuring them currently fails fast in
 * {@code DefaultMcpClientFactory}.
 *
 * <h2>Connection Establishment Timing</h2>
 * <p>
 * Transport connections (process start, HTTP connection, etc.) are established in the <b>constructor</b>. When the
 * constructor returns normally, {@link #isConnected()} must return {@code true} and {@link #sendRequest(String,
 * JsonNode)} must be immediately callable. If the connection fails, the constructor throws
 * {@link McpTransportException}.
 *
 * <p>
 * This rule guarantees that {@link at.aimon.core.mcp.McpClientFactory} implementations can perform the
 * {@link at.aimon.core.mcp.McpClient#initialize()} handshake immediately after Transport creation. Separating
 * creation from connection would introduce an intermediate "created but not connected" state that complicates error
 * handling.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * Implementations must be thread-safe. Multiple {@link at.aimon.core.mcp.McpTool} instances share the same
 * {@link at.aimon.core.mcp.McpClient}, and may invoke {@code sendRequest} concurrently from the Agent's ReAct loop.
 * Internal synchronization mechanisms must ensure safe concurrent request handling.
 *
 * <h2>Timeout</h2>
 * <p>
 * The timeout configured via {@link at.aimon.core.mcp.McpServerConfig#getRequestTimeout()} is injected by the
 * {@link at.aimon.core.mcp.McpClientFactory} implementation at Transport creation time. Transport implementations
 * must apply this timeout on {@code sendRequest()} calls to prevent indefinite blocking on unresponsive servers.
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Sends a JSON-RPC request to the MCP server and receives a response.
     *
     * <p>
     * Implementations must apply the timeout injected at construction time. If no response is received within the
     * specified duration, {@link McpTransportException} is thrown.
     *
     * @param method
     *            JSON-RPC method name (e.g., "tools/list", "tools/call")
     * @param params
     *            request parameters
     * @return response JSON
     * @throws McpTransportException
     *             if communication fails or times out
     */
    JsonNode sendRequest(String method, JsonNode params);

    /**
     * Sends a one-way JSON-RPC notification to the MCP server.
     *
     * <p>
     * Unlike {@link #sendRequest(String, JsonNode)}, a notification carries no {@code id} and expects no response:
     * implementations must write the message and return immediately without assigning a request id or waiting for
     * (correlating) any reply. This primitive is required for spec-compliant notifications such as
     * {@code notifications/initialized}, which a compliant server never answers — routing such a message through the
     * id-correlated {@link #sendRequest(String, JsonNode)} path would stall for the entire request timeout (or fail
     * with "method not found") and break the initialize handshake.
     *
     * @param method
     *            JSON-RPC notification method name (e.g., "notifications/initialized")
     * @param params
     *            notification parameters
     * @throws McpTransportException
     *             if the message cannot be written
     */
    void sendNotification(String method, JsonNode params);

    /**
     * Checks the connection status.
     *
     * @return true if connected
     */
    boolean isConnected();

}
