package at.aimon.core.mcp;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.mcp.McpServerConfig.McpTransportType;
import at.aimon.core.mcp.exception.McpInitializeException;
import at.aimon.core.mcp.transport.McpTransport;
import at.aimon.core.mcp.transport.StdioMcpTransport;

/**
 * Default implementation of {@link McpClientFactory}.
 *
 * <p>
 * Creates the appropriate {@link McpTransport} based on the transport type specified in {@link McpServerConfig},
 * creates
 * a {@link DefaultMcpClient}, and performs the MCP protocol initialization handshake.
 *
 * <h2>Resource Cleanup</h2>
 * <p>
 * If McpClient creation or initialization fails after the Transport has been created, the Transport is closed before
 * the
 * exception is propagated to prevent resource leaks.
 *
 * @see McpClientFactory
 */
public class DefaultMcpClientFactory implements McpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultMcpClientFactory.class);

    @Override
    public McpClient create(McpServerConfig config) {
        Objects.requireNonNull(config, "config cannot be null");

        McpTransport transport = createTransport(config);

        try {
            DefaultMcpClient client = new DefaultMcpClient(transport, config.getName());
            client.initialize();
            return client;
        } catch (Exception e) {
            // Clean up Transport if Client creation or initialization fails
            closeTransportQuietly(transport, config.getName());

            if (e instanceof McpInitializeException) {
                throw (McpInitializeException) e;
            }
            throw new McpInitializeException(
                    "Failed to create MCP client for server '" + config.getName() + "': " + e.getMessage(), e);
        }
    }

    private McpTransport createTransport(McpServerConfig config) {
        McpTransportType transportType = config.getTransportType();

        switch (transportType) {
            case STDIO :
                return new StdioMcpTransport(config.getCommand(), config.getArgs(), config.getEnv(),
                        config.getRequestTimeout());
            case SSE :
            case STREAMABLE_HTTP :
                throw new UnsupportedOperationException(
                        "MCP transport type " + transportType + " is not yet implemented; use STDIO");
            default :
                throw new IllegalArgumentException("Unsupported transport type: " + transportType);
        }
    }

    private void closeTransportQuietly(McpTransport transport, String serverName) {
        try {
            transport.close();
        } catch (Exception closeEx) {
            log.warn("Failed to close transport for server '{}' during cleanup: {}", serverName, closeEx.getMessage(),
                    closeEx);
        }
    }

}
