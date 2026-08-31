package at.aimon.core.mcp;

import java.util.Objects;

/**
 * MCP server capability information.
 *
 * <p>
 * An immutable object containing server-supported features extracted from the {@code initialize} handshake response.
 *
 * <p>
 * {@code serverVersion} is nullable. The MCP protocol spec treats server version as an optional field, and some server
 * implementations may not provide version information.
 */
public final class McpServerCapabilities {

    private final boolean supportsTools;
    private final boolean supportsResources;
    private final boolean supportsPrompts;
    private final String serverName;
    private final String serverVersion; // nullable - server may not provide version info

    private McpServerCapabilities(Builder builder) {
        this.supportsTools = builder.supportsTools;
        this.supportsResources = builder.supportsResources;
        this.supportsPrompts = builder.supportsPrompts;
        this.serverName = Objects.requireNonNull(builder.serverName, "serverName cannot be null");
        this.serverVersion = builder.serverVersion; // nullable
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    public boolean supportsTools() {
        return supportsTools;
    }

    public boolean supportsResources() {
        return supportsResources;
    }

    public boolean supportsPrompts() {
        return supportsPrompts;
    }

    public String getServerName() {
        return serverName;
    }

    /** Returns the server version, or null if the server did not provide version information. */
    public String getServerVersion() {
        return serverVersion;
    }

    public static class Builder {

        private boolean supportsTools;
        private boolean supportsResources;
        private boolean supportsPrompts;
        private String serverName;
        private String serverVersion;

        public Builder supportsTools(boolean supportsTools) {
            this.supportsTools = supportsTools;
            return this;
        }

        public Builder supportsResources(boolean supportsResources) {
            this.supportsResources = supportsResources;
            return this;
        }

        public Builder supportsPrompts(boolean supportsPrompts) {
            this.supportsPrompts = supportsPrompts;
            return this;
        }

        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        public Builder serverVersion(String serverVersion) {
            this.serverVersion = serverVersion;
            return this;
        }

        public McpServerCapabilities build() {
            return new McpServerCapabilities(this);
        }

    }

}
