package at.aimon.cli.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.mcp.McpServerConfig;
import at.aimon.core.mcp.McpServerConfig.AnnotationTrust;
import at.aimon.core.mcp.McpServerConfig.McpTransportType;

/**
 * YAML binding POJO for a single MCP server configuration entry.
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
 *       env:
 *         GITHUB_TOKEN: "${GITHUB_TOKEN}"
 *       requestTimeout: 30
 *       annotationTrust: "IGNORE"
 * </pre>
 */
public class McpServerEntry {

    private String name;
    private String transportType;
    private String command;
    private List<String> args;
    private String url;
    private Map<String, String> env;
    private Integer requestTimeout;
    private String annotationTrust;

    /** McpServerEntry를 생성한다. */
    public McpServerEntry() {
    }

    /**
     * Converts this YAML entry to a core {@link McpServerConfig}.
     *
     * @return the converted McpServerConfig
     * @throws IllegalArgumentException
     *             if transportType is invalid or required fields are missing
     */
    public McpServerConfig toMcpServerConfig() {
        McpTransportType type;
        try {
            type = McpTransportType.valueOf(transportType);
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid transportType '" + transportType + "' for MCP server '" + name
                    + "'. Supported values: STDIO, SSE, STREAMABLE_HTTP", e);
        }

        McpServerConfig.Builder builder = McpServerConfig.builder().name(name).transportType(type).command(command)
                .args(args).url(url).env(env);

        if (requestTimeout != null) {
            builder.requestTimeout(Duration.ofSeconds(requestTimeout));
        }

        if (annotationTrust != null) {
            builder.annotationTrust(parseAnnotationTrust());
        }

        return builder.build();
    }

    /**
     * Parses {@code annotationTrust}, rejecting an unrecognised value rather than falling back to the default. A typo
     * here would silently mean "do not believe this server", which is the safe direction but not the configured one,
     * and a setting that quietly does nothing is worse than one that fails at startup.
     */
    private AnnotationTrust parseAnnotationTrust() {
        try {
            return AnnotationTrust.valueOf(annotationTrust.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid annotationTrust '" + annotationTrust + "' for MCP server '"
                    + name + "'. Supported values: IGNORE, TRUST", e);
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTransportType() {
        return transportType;
    }

    public void setTransportType(String transportType) {
        this.transportType = transportType;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public Integer getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Integer requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /**
     * Returns how far this server's tool annotations are believed, as written in YAML.
     *
     * @return {@code "IGNORE"} or {@code "TRUST"}, or null when unset (meaning {@code IGNORE})
     */
    public String getAnnotationTrust() {
        return annotationTrust;
    }

    /**
     * Sets how far this server's tool annotations are believed. Leave unset unless you operate the server: under
     * {@code TRUST}, a tool claiming to be read-only is handed to the LLM under a read-only ceiling and runs without
     * prompting under the approval gate.
     *
     * @param annotationTrust
     *            {@code "IGNORE"} (default) or {@code "TRUST"}, case-insensitive
     */
    public void setAnnotationTrust(String annotationTrust) {
        this.annotationTrust = annotationTrust;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final McpServerEntry that = (McpServerEntry) o;
        return Objects.equals(name, that.name) && Objects.equals(transportType, that.transportType)
                && Objects.equals(command, that.command) && Objects.equals(args, that.args)
                && Objects.equals(url, that.url) && Objects.equals(env, that.env)
                && Objects.equals(requestTimeout, that.requestTimeout)
                && Objects.equals(annotationTrust, that.annotationTrust);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, transportType, command, args, url, env, requestTimeout, annotationTrust);
    }

    @Override
    public String toString() {
        return "McpServerEntry{" + "name='" + name + '\'' + ", transportType='" + transportType + '\'' + ", command='"
                + command + '\'' + ", url='" + url + '\'' + '}';
    }

}
