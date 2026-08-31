package at.aimon.core.skill.hook.action;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Declarative hook action that invokes an MCP tool on a registered MCP server when its enclosing
 * hook fires.
 *
 * <p>
 * The {@link #getArgsTemplate() args template} is rendered by the shared {@code TemplateRenderer} before being passed
 * to {@code McpClient#callTool}. Server name and tool name are resolved at execution time against
 * {@code McpClientManager}; an unknown server / tool produces a non-blocking failure (log + success) by default to
 * keep declarative hooks fail-soft.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class McpToolAction implements HookAction {

    /** Default per-call timeout when omitted from the configuration. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final String serverName;
    private final String toolName;
    private final Map<String, Object> argsTemplate;
    private final Duration timeout;

    private McpToolAction(Builder builder) {
        this.serverName = requireNonBlank(builder.serverName, "serverName");
        this.toolName = requireNonBlank(builder.toolName, "toolName");
        this.argsTemplate = Map.copyOf(builder.argsTemplate);
        this.timeout = builder.timeout != null ? builder.timeout : DEFAULT_TIMEOUT;
        if (this.timeout.isZero() || this.timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive, but was: " + this.timeout);
        }
    }

    /**
     * Creates a new builder.
     *
     * @return a fresh builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getServerName() {
        return serverName;
    }

    public String getToolName() {
        return toolName;
    }

    /**
     * Returns the args template. Values are typically strings carrying placeholders like {@code "${tool_input.path}"};
     * structured (Map / List) values are walked and rendered recursively.
     *
     * @return immutable args map (never null)
     */
    public Map<String, Object> getArgsTemplate() {
        return argsTemplate;
    }

    public Duration getTimeout() {
        return timeout;
    }

    @Override
    public Optional<Duration> getExecutionBudget() {
        return Optional.of(timeout);
    }

    private static String requireNonBlank(String s, String field) {
        Objects.requireNonNull(s, field + " cannot be null");
        if (s.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof McpToolAction that)) {
            return false;
        }
        return serverName.equals(that.serverName) && toolName.equals(that.toolName)
                && argsTemplate.equals(that.argsTemplate) && timeout.equals(that.timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverName, toolName, argsTemplate, timeout);
    }

    @Override
    public String toString() {
        return "McpToolAction{server=" + serverName + ", tool=" + toolName + ", timeout=" + timeout + '}';
    }

    /** Builder for {@link McpToolAction}. */
    public static final class Builder {
        private String serverName;
        private String toolName;
        private final Map<String, Object> argsTemplate = new LinkedHashMap<>();
        private Duration timeout;

        private Builder() {
        }

        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder argsTemplate(Map<String, Object> args) {
            Objects.requireNonNull(args, "args cannot be null");
            this.argsTemplate.clear();
            this.argsTemplate.putAll(args);
            return this;
        }

        public Builder addArg(String key, Object value) {
            Objects.requireNonNull(key, "Key cannot be null");
            this.argsTemplate.put(key, value);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public McpToolAction build() {
            return new McpToolAction(this);
        }
    }
}
