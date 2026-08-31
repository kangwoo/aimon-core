package at.aimon.core.mcp;

import at.aimon.core.base.ApplicationScoped;
import at.aimon.core.mcp.exception.McpInitializeException;

/**
 * Factory interface for creating McpClient instances.
 *
 * <p>
 * Receives an {@link McpServerConfig}, creates the appropriate Transport and McpClient, performs the MCP protocol
 * initialization (handshake), and returns a fully initialized client.
 *
 * <h2>Lifecycle</h2>
 * <p>
 * This interface is {@link ApplicationScoped}. A single instance is created at application startup and reused by
 * multiple {@link McpClientManager} instances (AgentScoped). Implementations are stateless, making concurrent usage
 * from multiple Contexts safe.
 *
 * <h2>Server Name Uniqueness</h2>
 * <p>
 * Checking for duplicate server names is the caller's ({@link McpClientManager}) responsibility.
 *
 * <h2>Resource Cleanup Responsibility</h2>
 * <p>
 * Implementations must clean up (close) already-created {@link at.aimon.core.mcp.transport.McpTransport} instances
 * if McpClient creation fails mid-way (e.g., initialize handshake failure). Leaving a Transport open when Client
 * initialization fails causes resource leaks.
 *
 * @see McpClientManager
 * @see ApplicationScoped
 */
public interface McpClientFactory extends ApplicationScoped {

    /**
     * Creates and initializes an McpClient based on the given configuration.
     *
     * <p>
     * Creation process:
     * <ol>
     * <li>Create the appropriate McpTransport based on the config's transportType
     * <li>Create an McpClient using the Transport
     * <li>Perform MCP protocol initialization (initialize handshake)
     * <li>Return the fully initialized McpClient
     * </ol>
     *
     * <p>
     * If steps 2-3 fail, the Transport created in step 1 is closed before propagating the exception.
     *
     * @param config
     *            MCP server connection configuration
     * @return a fully initialized McpClient
     * @throws McpInitializeException
     *             if connection or initialization fails (Transport is cleaned up)
     */
    McpClient create(McpServerConfig config);

}
