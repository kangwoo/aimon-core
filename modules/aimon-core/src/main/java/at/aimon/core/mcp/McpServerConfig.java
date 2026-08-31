package at.aimon.core.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * MCP server connection configuration.
 *
 * <h2>Server Name Rules</h2>
 * <p>
 * Server names are used as part of MCP Tool names ({@code mcp__<name>__<tool>}), so only lowercase letters, digits, and
 * hyphens are allowed, and the name must be unique.
 *
 * <p>
 * Usage examples:
 *
 * <pre>
 * {@code
 * // Stdio-based local MCP server
 * McpServerConfig config = McpServerConfig.builder()
 *     .name("github")
 *     .transportType(McpTransportType.STDIO)
 *     .command("npx")
 *     .args(List.of("-y", "@modelcontextprotocol/server-github"))
 *     .env(Map.of("GITHUB_TOKEN", token))
 *     .build();
 *
 * // SSE-based remote MCP server
 * McpServerConfig config = McpServerConfig.builder()
 *     .name("custom-server")
 *     .transportType(McpTransportType.SSE)
 *     .url("http://localhost:8080/mcp")
 *     .requestTimeout(Duration.ofSeconds(30))
 *     .build();
 *
 * // Streamable HTTP-based remote MCP server (MCP 2025-03-26 spec recommended)
 * McpServerConfig config = McpServerConfig.builder()
 *     .name("modern-server")
 *     .transportType(McpTransportType.STREAMABLE_HTTP)
 *     .url("http://localhost:8080/mcp")
 *     .requestTimeout(Duration.ofSeconds(30))
 *     .build();
 * }
 * </pre>
 */
public final class McpServerConfig {

    /** Allows only lowercase letters, digits, and hyphens. Cannot start or end with a hyphen. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$");

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String name;
    private final McpTransportType transportType;
    private final String command;
    private final List<String> args;
    private final String url;
    private final Map<String, String> env;
    private final Duration requestTimeout;
    private final AnnotationTrust annotationTrust;

    private McpServerConfig(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name cannot be null");
        this.transportType = Objects.requireNonNull(builder.transportType, "transportType cannot be null");
        this.command = builder.command;
        this.args = builder.args != null ? List.copyOf(builder.args) : List.of();
        this.url = builder.url;
        this.env = builder.env != null ? Map.copyOf(builder.env) : Map.of();
        this.requestTimeout = builder.requestTimeout != null ? builder.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
        this.annotationTrust = builder.annotationTrust != null ? builder.annotationTrust : AnnotationTrust.IGNORE;

        validate();
    }

    private void validate() {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Server name must match pattern '" + NAME_PATTERN.pattern() + "', got: '" + name + "'");
        }

        if (transportType == McpTransportType.STDIO && command == null) {
            throw new IllegalArgumentException("command is required for STDIO transport type");
        }

        if (transportType == McpTransportType.SSE && url == null) {
            throw new IllegalArgumentException("url is required for SSE transport type");
        }

        if (transportType == McpTransportType.STREAMABLE_HTTP && url == null) {
            throw new IllegalArgumentException("url is required for STREAMABLE_HTTP transport type");
        }
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public McpTransportType getTransportType() {
        return transportType;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * Returns how far this server's tool annotations are believed.
     *
     * @return the trust setting, {@link AnnotationTrust#IGNORE} unless configured otherwise (never null)
     */
    public AnnotationTrust getAnnotationTrust() {
        return annotationTrust;
    }

    public static class Builder {

        private String name;
        private McpTransportType transportType;
        private String command;
        private List<String> args;
        private String url;
        private Map<String, String> env;
        private Duration requestTimeout;
        private AnnotationTrust annotationTrust;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder transportType(McpTransportType transportType) {
            this.transportType = transportType;
            return this;
        }

        public Builder command(String command) {
            this.command = command;
            return this;
        }

        public Builder args(List<String> args) {
            this.args = args;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * Sets how far this server's tool annotations are believed.
         *
         * @param annotationTrust
         *            the trust setting, or null for the default {@link AnnotationTrust#IGNORE}
         * @return this builder
         */
        public Builder annotationTrust(AnnotationTrust annotationTrust) {
            this.annotationTrust = annotationTrust;
            return this;
        }

        public McpServerConfig build() {
            return new McpServerConfig(this);
        }

    }

    /**
     * MCP transport type.
     */
    public enum McpTransportType {

        /** Local process stdin/stdout-based communication. */
        STDIO,

        /** HTTP Server-Sent Events-based remote communication. Deprecated in MCP 2025-03-26 spec. */
        SSE,

        /** Streamable HTTP-based remote communication. Recommended transport in MCP 2025-03-26 spec. */
        STREAMABLE_HTTP

    }

    /**
     * How far this server's {@link McpToolAnnotations} are believed.
     *
     * <p>
     * The annotations are a tool's own account of itself, relayed by a server AIMON does not control, so believing them
     * is a decision about the <em>server</em> and belongs next to the rest of that server's configuration rather than
     * in a policy interface: there is one thing to decide and it is per-server. A tool wrongly claiming
     * {@code readOnlyHint} would be handed to the LLM under a read-only ceiling and would run without prompting under
     * the approval gate — so the setting is per-server precisely because trust is not transitive across servers.
     *
     * @see McpToolTraits#resolve(McpToolAnnotations, AnnotationTrust)
     */
    public enum AnnotationTrust {

        /**
         * The default. Annotations are parsed and carried but not acted on: every tool from this server declares
         * {@code MUTATING} + {@code DESTRUCTIVE}, which is where MCP tools sat before annotations were read at all.
         */
        IGNORE,

        /**
         * The server's claims become the tools' declarations. Appropriate for a server you operate or otherwise vouch
         * for; a third-party server is not made trustworthy by being useful.
         */
        TRUST

    }

}
