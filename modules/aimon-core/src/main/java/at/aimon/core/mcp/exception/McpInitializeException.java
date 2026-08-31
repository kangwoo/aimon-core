package at.aimon.core.mcp.exception;

/**
 * Thrown when MCP protocol initialization (handshake) fails.
 *
 * <p>
 * This exception is raised in the following situations:
 * <ul>
 * <li>Server returns an error response to the initialize request
 * <li>Protocol version mismatch
 * <li>Transport layer failure during initialization ({@link McpTransportException} as cause)
 * </ul>
 *
 * <p>
 * When thrown from {@link at.aimon.core.mcp.McpClientFactory#create}, the already-created
 * {@link at.aimon.core.mcp.transport.McpTransport} is cleaned up by the factory.
 *
 * @see at.aimon.core.mcp.McpClient#initialize()
 * @see at.aimon.core.mcp.McpClientFactory#create(at.aimon.core.mcp.McpServerConfig)
 */
public class McpInitializeException extends McpException {

    public McpInitializeException(String message) {
        super(message);
    }

    public McpInitializeException(String message, Throwable cause) {
        super(message, cause);
    }

}
