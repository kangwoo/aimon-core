package at.aimon.core.mcp.orca;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.mcp.McpServerConfig;
import at.aimon.core.mcp.McpServerConfigProvider;

/**
 * {@link OrcaToolProvider} implementation that discovers and registers MCP server tools.
 *
 * <p>
 * Follows the existing OrcaToolProvider pattern to register MCP Tools. Uses the externally injected
 * {@link McpClientManager} to connect to MCP servers and register discovered tools in the ToolRegistry.
 *
 * <h2>McpClientManager Ownership</h2>
 * <p>
 * {@link McpClientManager} is not created by this class. It is created by
 * {@code OrcaAgentRuntimeFactory} and injected via the constructor. Lifecycle management (close) is handled by
 * {@link at.aimon.core.agent.impl.orca.OrcaAgentRuntime}.
 *
 * <h2>Server Name Uniqueness</h2>
 * <p>
 * During {@link #registerTools}, server name duplicates in the configuration list are validated upfront (fail-fast).
 * This check is redundant with {@link McpClientManager#createClient}'s registration-time check, but catches
 * configuration errors as early as possible.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {@code
 * // In OrcaAgentRuntimeFactory
 * McpClientManager mcpClientManager = new McpClientManager(mcpClientFactory);
 * OrcaMcpToolProvider provider = new OrcaMcpToolProvider(configProvider, mcpClientManager);
 *
 * List<OrcaToolProvider> providers = List.of(
 *     new OrcaFileToolProvider(),
 *     new OrcaBashToolProvider(),
 *     provider
 * );
 * }
 * </pre>
 */
public class OrcaMcpToolProvider implements OrcaToolProvider {

    private static final Logger log = LoggerFactory.getLogger(OrcaMcpToolProvider.class);

    private final McpServerConfigProvider configProvider;
    private final McpClientManager mcpClientManager;

    /**
     * Creates an OrcaMcpToolProvider.
     *
     * @param configProvider
     *            MCP server configuration provider (ApplicationScoped)
     * @param mcpClientManager
     *            externally created McpClientManager (not owned by this class)
     */
    public OrcaMcpToolProvider(McpServerConfigProvider configProvider, McpClientManager mcpClientManager) {
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider cannot be null");
        this.mcpClientManager = Objects.requireNonNull(mcpClientManager, "mcpClientManager cannot be null");
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        // Get server configurations from the provider
        List<McpServerConfig> serverConfigs = configProvider.getConfigs();

        if (serverConfigs.isEmpty()) {
            log.debug("No MCP server configs provided, skipping MCP tool registration");
            return;
        }

        // Validate server name uniqueness upfront (fail-fast)
        validateUniqueServerNames(serverConfigs);

        // 1. Connect and initialize all MCP servers in parallel
        // Individual server failures do not abort the entire process; failed server list is returned.
        List<String> failedServers = mcpClientManager.createClients(serverConfigs);

        // 2. Register tools from successfully connected servers to the ToolRegistry
        mcpClientManager.registerAllTools(registry);

        // 3. Log connection result summary
        List<String> connectedServers = mcpClientManager.getServerNames();
        if (failedServers.isEmpty()) {
            log.info("MCP tool registration complete: {}/{} servers connected {}", connectedServers.size(),
                    serverConfigs.size(), connectedServers);
        } else {
            log.warn("MCP tool registration complete: {}/{} servers connected. Connected: {}, Failed: {}",
                    connectedServers.size(), serverConfigs.size(), connectedServers, failedServers);
        }
    }

    /**
     * Validates server name uniqueness (fail-fast defensive check).
     *
     * <p>
     * {@link McpClientManager#createClient} also checks for duplicates at registration time, but this method catches
     * configuration-level duplicates early for faster failure.
     *
     * @throws IllegalArgumentException
     *             if duplicate server names exist
     */
    private static void validateUniqueServerNames(List<McpServerConfig> configs) {
        Set<String> names = new HashSet<>();
        for (McpServerConfig config : configs) {
            if (!names.add(config.getName())) {
                throw new IllegalArgumentException("Duplicate MCP server name: '" + config.getName()
                        + "'. Each MCP server must have a unique name.");
            }
        }
    }

}
