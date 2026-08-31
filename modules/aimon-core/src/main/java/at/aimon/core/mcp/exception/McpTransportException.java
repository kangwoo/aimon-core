package at.aimon.core.mcp.exception;

/**
 * Thrown when MCP transport layer communication fails.
 *
 * <p>
 * This exception is raised in the following situations:
 * <ul>
 * <li>Network connection failure or disconnection
 * <li>Request timeout ({@link at.aimon.core.mcp.McpServerConfig#getRequestTimeout()} exceeded)
 * <li>JSON-RPC response parsing failure
 * <li>Stdio process abnormal termination
 * </ul>
 *
 * @see at.aimon.core.mcp.transport.McpTransport#sendRequest(String, com.fasterxml.jackson.databind.JsonNode)
 */
public class McpTransportException extends McpException {

    public McpTransportException(String message) {
        super(message);
    }

    public McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }

}
