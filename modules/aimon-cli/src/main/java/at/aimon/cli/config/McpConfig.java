package at.aimon.cli.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import at.aimon.core.mcp.McpServerConfig;
import at.aimon.core.mcp.McpServerConfigProvider;

/**
 * YAML binding POJO for the {@code mcp} configuration section.
 *
 * <p>
 * Maps directly from the YAML structure:
 *
 * <pre>
 * mcp:
 *   servers:
 *     - name: "github"
 *       transportType: "STDIO"
 *       command: "npx"
 *       args: ["-y", "@modelcontextprotocol/server-github"]
 * </pre>
 */
public class McpConfig {

    private List<McpServerEntry> servers = new ArrayList<>();

    /** McpConfig를 생성한다. */
    public McpConfig() {
    }

    /**
     * Returns whether any MCP servers are configured.
     *
     * @return true if at least one server entry exists
     */
    public boolean hasServers() {
        return servers != null && !servers.isEmpty();
    }

    /**
     * Converts this configuration to a {@link McpServerConfigProvider}.
     *
     * @return a provider that returns the configured server list
     */
    public McpServerConfigProvider toConfigProvider() {
        List<McpServerConfig> configs = servers.stream().map(McpServerEntry::toMcpServerConfig)
                .collect(Collectors.toUnmodifiableList());
        return () -> configs;
    }

    public List<McpServerEntry> getServers() {
        return servers;
    }

    public void setServers(List<McpServerEntry> servers) {
        this.servers = servers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final McpConfig that = (McpConfig) o;
        return Objects.equals(servers, that.servers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(servers);
    }

    @Override
    public String toString() {
        return "McpConfig{" + "servers=" + servers + '}';
    }

}
