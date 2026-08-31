package at.aimon.core.config.hook;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import at.aimon.core.config.hook.rewake.RewakeSpecConfig;

/**
 * A single hook handler entry inside the {@code hooks} array of a {@link HookEntry}.
 *
 * <p>
 * Mirrors the Claude Code {@code hooks.json} handler shape. The {@code type} field selects the AIMON action that
 * the loader will instantiate:
 * <ul>
 * <li>{@code command} → {@link at.aimon.core.skill.hook.action.ShellAction}
 * <li>{@code http} → {@link at.aimon.core.skill.hook.action.HttpAction}
 * <li>{@code mcp} → {@link at.aimon.core.skill.hook.action.McpToolAction}
 * <li>{@code deny} → {@link at.aimon.core.skill.hook.action.DenyAction} (preTool-only short-circuit)
 * </ul>
 *
 * <p>
 * Unknown JSON fields are tolerated &mdash; Jackson silently
 * skips them so newer config files do not break older binaries. The {@link HookRegistryApplier} is responsible for
 * surfacing fields it cannot honour as WARN logs.
 *
 * <p>
 * <b>Timeout units (breaking change).</b> The wire field {@code timeout} is expressed in <b>seconds</b>, matching
 * Claude Code's {@code hooks.json}. It used to be read as milliseconds, which silently gave a config copied from
 * Claude Code a budget 1000&times; too small &mdash; and because a hook timeout is fail-soft, the only symptom was a
 * hook that quietly never finished its work. Configurations that were written against the old reading must multiply
 * their value by 1000 or switch to the alias below.
 * <ul>
 * <li>{@code "timeout": 60} &rarr; 60 seconds (60000&nbsp;ms)
 * <li>{@code "timeoutMs": 1500} &rarr; 1500 milliseconds &mdash; the AIMON-native spelling, for sub-second precision
 * </ul>
 * When both appear, {@code timeoutMs} wins because it is the more precise of the two. Both must be positive; a zero
 * or negative value is rejected at parse time the same way an unknown {@code type} is, i.e. as an
 * {@link IllegalArgumentException} that the parser surfaces as a {@link HookConfigParseException}. Internally only
 * milliseconds exist: {@link #getTimeoutMs()} is the single accessor and the conversion happens at the JSON binding
 * boundary, so nothing downstream had to change.
 *
 * <p>
 * Immutable; thread-safe.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class HookHandlerSpec {

    private static final long MILLIS_PER_SECOND = 1000L;

    /** Discriminator for the handler kind. */
    public enum Type {

        /** Shell command — Claude Code {@code "command"}. */
        COMMAND,

        /** HTTP webhook — Claude Code {@code "http"}. */
        HTTP,

        /** MCP tool call — AIMON extension. */
        MCP,

        /** Deny shortcut for preTool — AIMON extension (Claude Code uses {@code permissionDecision: "deny"}). */
        DENY;

        /**
         * Resolves the JSON token (case-insensitive) to a {@link Type}.
         *
         * @param raw
         *            the raw JSON string (must not be null)
         * @return the matching enum
         * @throws IllegalArgumentException
         *             if {@code raw} is not a known type
         */
        public static Type fromJson(String raw) {
            Objects.requireNonNull(raw, "type cannot be null");
            return switch (raw.toLowerCase()) {
                case "command" -> COMMAND;
                case "http" -> HTTP;
                case "mcp", "mcp_tool", "mcptool" -> MCP;
                case "deny" -> DENY;
                default -> throw new IllegalArgumentException("Unknown hook handler type: '" + raw + "'");
            };
        }
    }

    private final Type type;

    // command
    private final String command;

    // http
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String bodyTemplate;
    private final Set<String> allowedEnvVars;

    // mcp
    private final String serverName;
    private final String toolName;
    private final Map<String, Object> args;

    // deny
    private final String reason;

    // shared
    private final Long timeoutMs;

    // phase 4 — async rewake (parsed lazily by RewakeSpecParser)
    private final RewakeSpecConfig asyncRewake;

    private HookHandlerSpec(Builder b) {
        this.type = Objects.requireNonNull(b.type, "type cannot be null");
        this.command = b.command;
        this.url = b.url;
        this.method = b.method;
        this.headers = b.headers == null ? Map.of() : Map.copyOf(b.headers);
        this.bodyTemplate = b.bodyTemplate;
        this.allowedEnvVars = b.allowedEnvVars == null ? Set.of() : Set.copyOf(b.allowedEnvVars);
        this.serverName = b.serverName;
        this.toolName = b.toolName;
        this.args = b.args == null ? Map.of() : Map.copyOf(b.args);
        this.reason = b.reason;
        this.timeoutMs = b.timeoutMs;
        this.asyncRewake = b.asyncRewake;
    }

    /** @return the handler type discriminator (never null) */
    @JsonProperty("type")
    public Type getType() {
        return type;
    }

    /** @return the shell command for {@link Type#COMMAND}, or {@code null} */
    @JsonProperty("command")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getCommand() {
        return command;
    }

    /** @return the URL for {@link Type#HTTP}, or {@code null} */
    @JsonProperty("url")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getUrl() {
        return url;
    }

    /** @return the HTTP method (defaults to POST when null), or {@code null} */
    @JsonProperty("method")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getMethod() {
        return method;
    }

    /** @return immutable header map (never null; possibly empty) */
    @JsonProperty("headers")
    public Map<String, String> getHeaders() {
        return headers;
    }

    /** @return the request body template (may contain {@code ${tool_input.X}} placeholders), or {@code null} */
    @JsonProperty("body")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getBodyTemplate() {
        return bodyTemplate;
    }

    /** @return immutable set of env var names that may be referenced via {@code ${env.X}} (never null) */
    @JsonProperty("allowedEnvVars")
    public Set<String> getAllowedEnvVars() {
        return allowedEnvVars;
    }

    /** @return the MCP server name for {@link Type#MCP}, or {@code null} */
    @JsonProperty("server")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getServerName() {
        return serverName;
    }

    /** @return the MCP tool name for {@link Type#MCP}, or {@code null} */
    @JsonProperty("tool")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getToolName() {
        return toolName;
    }

    /** @return immutable args template for {@link Type#MCP} (never null; possibly empty) */
    @JsonProperty("args")
    public Map<String, Object> getArgs() {
        return args;
    }

    /** @return reason for {@link Type#DENY}, or {@code null} */
    @JsonProperty("reason")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getReason() {
        return reason;
    }

    /**
     * Returns the per-handler timeout in milliseconds, whichever wire spelling produced it: {@code timeout} (seconds,
     * Claude Code parity) or {@code timeoutMs} (milliseconds, AIMON extension). Serialisation always writes the
     * {@code timeoutMs} form so a parse &rarr; write &rarr; parse round trip cannot lose sub-second precision.
     *
     * @return per-handler timeout in milliseconds (always positive), or {@code null} for the action default
     */
    @JsonProperty("timeoutMs")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * @return the wire-format {@code asyncRewake} block, or {@code null} when the handler does not
     *         opt into async rewake. Conversion to a runtime {@link at.aimon.core.hook.rewake.RewakeSpec} is performed
     *         by {@link at.aimon.core.config.hook.rewake.RewakeSpecParser}.
     */
    @JsonProperty("asyncRewake")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public RewakeSpecConfig getAsyncRewake() {
        return asyncRewake;
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Jackson constructor honoring the Claude Code field names.
     *
     * @param typeRaw
     *            the {@code type} discriminator string (must not be null)
     * @param command
     *            shell command (used when type=command)
     * @param url
     *            HTTP url (used when type=http)
     * @param method
     *            HTTP method (used when type=http)
     * @param headers
     *            HTTP header map (used when type=http)
     * @param body
     *            request body template (used when type=http)
     * @param allowedEnvVars
     *            allowed env var names (used when type=http)
     * @param serverName
     *            MCP server name (used when type=mcp)
     * @param toolName
     *            MCP tool name (used when type=mcp)
     * @param args
     *            MCP args template (used when type=mcp)
     * @param reason
     *            deny reason (used when type=deny)
     * @param timeoutSeconds
     *            handler timeout in <b>seconds</b> ({@code timeout}, Claude Code parity); must be positive
     * @param timeoutMs
     *            handler timeout in <b>milliseconds</b> ({@code timeoutMs}, AIMON extension); must be positive and
     *            takes precedence over {@code timeoutSeconds} when both are present
     * @param asyncRewake
     *            optional {@code asyncRewake} block describing how the framework should re-fire the hook
     * @return the spec (never null)
     * @throws IllegalArgumentException
     *             if {@code typeRaw} is unknown or either timeout is not positive
     */
    // Jackson @JsonCreator: each param binds a distinct wire field 1:1, so they cannot be grouped.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @JsonCreator
    public static HookHandlerSpec fromJson(@JsonProperty("type") String typeRaw,
            @JsonProperty("command") String command, @JsonProperty("url") String url,
            @JsonProperty("method") String method, @JsonProperty("headers") Map<String, String> headers,
            @JsonProperty("body") String body, @JsonProperty("allowedEnvVars") List<String> allowedEnvVars,
            @JsonProperty("server") String serverName, @JsonProperty("tool") String toolName,
            @JsonProperty("args") Map<String, Object> args, @JsonProperty("reason") String reason,
            @JsonProperty("timeout") Long timeoutSeconds, @JsonProperty("timeoutMs") Long timeoutMs,
            @JsonProperty("asyncRewake") RewakeSpecConfig asyncRewake) {
        return builder().type(Type.fromJson(typeRaw)).command(command).url(url).method(method).headers(headers)
                .bodyTemplate(body)
                .allowedEnvVars(allowedEnvVars == null ? Set.of() : new LinkedHashSet<>(allowedEnvVars))
                .serverName(serverName).toolName(toolName).args(args).reason(reason)
                .timeoutMs(resolveTimeoutMs(timeoutSeconds, timeoutMs)).asyncRewake(asyncRewake).build();
    }

    /**
     * Collapses the two wire spellings of the handler timeout into the single millisecond value the runtime uses.
     *
     * <p>
     * {@code timeoutMs} wins over {@code timeout} because it is the more precise of the two: a config that carries
     * both is most plausibly a Claude Code file someone refined with a sub-second AIMON value, and honouring the
     * coarser field would throw that refinement away.
     *
     * @param timeoutSeconds
     *            the {@code timeout} field in seconds, or null
     * @param timeoutMs
     *            the {@code timeoutMs} field in milliseconds, or null
     * @return the timeout in milliseconds, or null when neither field was present
     * @throws IllegalArgumentException
     *             if the chosen value is not positive, or is too large to express in milliseconds
     */
    private static Long resolveTimeoutMs(Long timeoutSeconds, Long timeoutMs) {
        if (timeoutMs != null) {
            return requirePositive(timeoutMs, "timeoutMs");
        }
        if (timeoutSeconds == null) {
            return null;
        }
        final long seconds = requirePositive(timeoutSeconds, "timeout");
        try {
            return Math.multiplyExact(seconds, MILLIS_PER_SECOND);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Hook handler 'timeout' is too large to express in milliseconds: " + seconds, e);
        }
    }

    private static long requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "Hook handler '" + field + "' must be a positive number, but was: " + value);
        }
        return value;
    }

    /** Builder for {@link HookHandlerSpec}. */
    public static final class Builder {
        private Type type;
        private String command;
        private String url;
        private String method;
        private Map<String, String> headers;
        private String bodyTemplate;
        private Set<String> allowedEnvVars;
        private String serverName;
        private String toolName;
        private Map<String, Object> args;
        private String reason;
        private Long timeoutMs;
        private RewakeSpecConfig asyncRewake;

        private Builder() {
        }

        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        public Builder command(String command) {
            this.command = command;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder bodyTemplate(String bodyTemplate) {
            this.bodyTemplate = bodyTemplate;
            return this;
        }

        public Builder allowedEnvVars(Set<String> allowedEnvVars) {
            this.allowedEnvVars = allowedEnvVars;
            return this;
        }

        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder args(Map<String, Object> args) {
            this.args = args;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * @param timeoutMs
         *            handler timeout in <b>milliseconds</b>; {@code null} leaves the action default in place. The
         *            builder is the millisecond-native entry point — the seconds-based {@code timeout} wire field is
         *            converted before it gets here, see {@link HookHandlerSpec#fromJson}.
         * @return this builder
         */
        public Builder timeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * @param asyncRewake
         *            wire-format async-rewake block; {@code null} when the handler does not opt
         *            into rewake
         * @return this builder
         */
        public Builder asyncRewake(RewakeSpecConfig asyncRewake) {
            this.asyncRewake = asyncRewake;
            return this;
        }

        public HookHandlerSpec build() {
            return new HookHandlerSpec(this);
        }
    }
}
