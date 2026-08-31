package at.aimon.core.mcp;

import java.util.List;

import at.aimon.core.base.ApplicationScoped;

/**
 * Provides a list of MCP server configurations.
 *
 * <p>
 * Abstracts the configuration source (YAML file, environment variables, database, management API, etc.) so that
 * {@code OrcaAgentRuntimeFactory} does not depend on a specific configuration source.
 *
 * <h2>Lifecycle</h2>
 * <p>
 * This interface is {@link ApplicationScoped}. A single instance is created at application startup and reused across
 * multiple {@code AgentRuntime} creations.
 *
 * <h2>Implementation Examples</h2>
 * <ul>
 * <li>Loading from YAML/JSON configuration files
 * <li>Parsing from environment variables
 * <li>Dynamic lookup from a management API
 * <li>Returning hardcoded configurations in tests
 * </ul>
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {@code
 * // Static configuration (immutable list)
 * McpServerConfigProvider staticProvider = () -> List.of(githubConfig, slackConfig);
 *
 * // File-based configuration
 * McpServerConfigProvider fileProvider = new YamlMcpServerConfigProvider("/etc/aimon/mcp.yml");
 * }
 * </pre>
 *
 * @see McpServerConfig
 * @see ApplicationScoped
 */
public interface McpServerConfigProvider extends ApplicationScoped {

    /**
     * Returns the list of MCP server configurations.
     *
     * <p>
     * Whether the returned list is an immutable snapshot or a fresh query each time depends on the implementation.
     * {@code OrcaAgentRuntimeFactory} calls this method once per Context creation.
     *
     * @return server configuration list (may be empty, never null)
     */
    List<McpServerConfig> getConfigs();

}
