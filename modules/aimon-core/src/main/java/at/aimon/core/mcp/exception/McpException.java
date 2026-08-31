package at.aimon.core.mcp.exception;

/**
 * Base class for all MCP-related exceptions.
 *
 * <p>
 * Provides a common superclass for catching all MCP exceptions uniformly. This is an unchecked exception
 * ({@link RuntimeException}).
 *
 * <h2>Exception Hierarchy</h2>
 *
 * <pre>
 * RuntimeException
 * +-- McpException
 *     +-- McpTransportException    (transport layer failure)
 *     +-- McpInitializeException   (protocol initialization failure)
 * </pre>
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }

}
