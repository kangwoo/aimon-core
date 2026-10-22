package at.aimon.core.tools.monitor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.CustomToolPermissionAware;
import at.aimon.core.agent.tool.permission.CustomToolPermissionRule;
import at.aimon.core.agent.tool.permission.PermissionDelegation;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPermissionDelegationAware;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
import at.aimon.core.tools.BackgroundTaskOwner;
import at.aimon.core.tools.bash.BashTool;
import at.aimon.core.tools.monitor.exception.MonitorRequestException;

/**
 * Starts a background monitor: a source whose events arrive as messages while the agent keeps working.
 *
 * <p>
 * The source is named by exactly one of three fields — {@code command} (the {@code shell} kind), {@code ws} (the
 * {@code websocket} kind) or {@code source} with {@code params} (any kind the host registered). All three are always
 * in the schema: a definition takes no context, so it cannot know whether this execution's shell streams or which
 * kinds exist. A form that cannot be served is refused at run time with the reason, and the registered kinds are
 * listed in the description, which is fixed when the tool is built (design §4.3).
 *
 * <p>
 * <b>Permissions.</b> The {@code command} form is judged like {@code Bash}: its subject is the command
 * ({@link PermissionSubject#command(String)}), so {@code Monitor(tail -f:*)} reads as it would for {@code Bash}. The
 * other forms have no subject the framework knows — there is no URL kind — so this tool's own rule judges them,
 * matching each configured pattern against {@code ws:<url>} or {@code source:<kind>}; a trailing {@code *} is the only
 * wildcard (§4.4). End a URL pattern with {@code /*}: {@code ws:wss://api.example.com*} also matches
 * {@code wss://api.example.com.evil.net}.
 *
 * <p>
 * A {@code command} must also be admitted by the {@code Bash} entries ({@link ToolPermissionDelegationAware}): a list
 * of {@code Bash(git:*)} and a bare {@code Monitor} does not let a monitor run what {@code Bash} may not, and a list
 * without {@code Bash} runs no command monitor at all.
 *
 * <p>
 * Stateless: every monitor lives in the {@link MonitorManager}.
 */
public class MonitorTool extends AbstractTool
        implements
            ToolPermissionSubjectAware,
            CustomToolPermissionAware,
            ToolPermissionDelegationAware {

    /** The tool name. */
    public static final String TOOL_NAME = "Monitor";

    /** Kind started by the {@code ws} field. */
    public static final String WEBSOCKET_KIND = WebSocketMonitorSourceFactory.KIND;

    private static final Logger log = LoggerFactory.getLogger(MonitorTool.class);

    private static final int MAX_DESCRIPTION_LENGTH = 200;

    private final MonitorManager manager;
    private final CustomToolPermissionRule permissionRule = new Rule();

    /**
     * Creates the tool over a manager.
     *
     * @param manager
     *            the application's monitor manager (must not be null)
     */
    public MonitorTool(MonitorManager manager) {
        super(TOOL_NAME, describe(Objects.requireNonNull(manager, "manager must not be null")),
                ToolCategories.EXECUTION, createInputSchema(manager.getMaxTimeout()));
        this.manager = manager;
    }

    private static String describe(MonitorManager manager) {
        final StringBuilder text = new StringBuilder(
                "Starts a background monitor whose events arrive as messages while you keep working — use it to watch a"
                        + " log, a stream or an event source, not to wait for one result. Give exactly one of: "
                        + "`command` (each stdout line is an event: filter it, e.g. `tail -f app.log | grep"
                        + " --line-buffered -E 'ERROR|FATAL'`, and include failure signatures, not just success), "
                        + "`ws` (each WebSocket text frame is an event), or `source` with `params` for a source"
                        + " registered by the application. Events are batched; a source that emits too many is stopped."
                        + " The monitor ends when its source ends, at `timeout_ms` (default ");
        text.append(MonitorManager.describe(manager.getDefaultTimeout())).append(", max ")
                .append(MonitorManager.describe(manager.getMaxTimeout()))
                .append("), or with MonitorStop; you are told when it ends. Start it again to keep watching.");
        final List<MonitorSourceFactory> kinds = manager.sources();
        if (!kinds.isEmpty()) {
            text.append(" Available sources:");
            for (MonitorSourceFactory kind : kinds) {
                text.append("\n- ").append(kind.kind()).append(": ").append(kind.describe());
            }
        }
        return text.toString();
    }

    private static Map<String, Object> createInputSchema(Duration maxTimeout) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of("type", "string", "description",
                "What is watched, in a few words (shown with every event), e.g. \"errors in deploy.log\""));
        properties.put("timeout_ms",
                Map.of("type", "integer", "description",
                        "How long the monitor may run, in milliseconds (min " + MonitorManager.MIN_TIMEOUT.toMillis()
                                + ", max " + maxTimeout.toMillis() + ")",
                        "minimum", MonitorManager.MIN_TIMEOUT.toMillis(), "maximum", maxTimeout.toMillis()));
        properties.put("command",
                Map.of("type", "string", "description", "Shell command; each line it prints to stdout is one event"));
        properties.put("ws",
                Map.of("type", "object", "description", "WebSocket to open; each text frame is one event", "properties",
                        Map.of("url", Map.of("type", "string", "description", "ws:// or wss:// URL"), "protocols",
                                Map.of("type", "array", "items", Map.of("type", "string"), "description",
                                        "Subprotocols to offer")),
                        "required", List.of("url"), "additionalProperties", false));
        properties.put("source",
                Map.of("type", "string", "description", "A source kind registered by the application"));
        properties.put("params", Map.of("type", "object", "description", "Parameters of `source`"));
        return Map.of("type", "object", "additionalProperties", false, "properties", properties, "required",
                List.of("description"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        try {
            final String description = input.getRequiredString("description").strip();
            if (description.isEmpty()) {
                return ToolResult.error("Invalid parameter: description cannot be empty");
            }
            if (description.length() > MAX_DESCRIPTION_LENGTH) {
                return ToolResult.error("Invalid parameter: description exceeds " + MAX_DESCRIPTION_LENGTH
                        + " characters — say what is watched in a few words");
            }
            final MonitorRequest.Builder request = MonitorRequest.builder().owner(BackgroundTaskOwner.of(context))
                    .description(description).context(context);
            final Optional<String> formError = describeForm(input, request);
            if (formError.isPresent()) {
                return ToolResult.error(formError.get());
            }
            if (input.get("timeout_ms") != null) {
                final long timeoutMs = input.getInteger("timeout_ms", 0);
                request.timeout(Duration.ofMillis(Math.max(timeoutMs, MonitorManager.MIN_TIMEOUT.toMillis())));
            }

            final MonitorRecord record = manager.start(request.build());
            final Duration runsFor = Duration.between(record.getStartedAt(), record.getDeadline());
            return ToolResult.success("Monitor " + record.getMonitorId() + " started (" + record.getKind() + "): "
                    + record.getDescription() + "\nEvents arrive as messages while you keep working. It ends after "
                    + MonitorManager.describe(runsFor) + " at the latest; stop it earlier with "
                    + MonitorStopTool.TOOL_NAME + "(monitorId=\"" + record.getMonitorId() + "\").");
        } catch (MonitorRequestException e) {
            log.warn("Monitor not started: {}", e.getMessage());
            return ToolResult.error(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error starting a monitor", e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Reads which of the three forms the call uses into {@code request}, or says what is wrong with the call.
     */
    private static Optional<String> describeForm(ToolInput input, MonitorRequest.Builder request) {
        final Object command = input.get("command");
        final Object ws = input.get("ws");
        final Object source = input.get("source");
        final Object params = input.get("params");
        final List<String> given = new ArrayList<>();
        if (command != null) {
            given.add("command");
        }
        if (ws != null) {
            given.add("ws");
        }
        if (source != null) {
            given.add("source");
        }
        if (given.size() != 1) {
            return Optional.of("Give exactly one of command, ws or source"
                    + (given.isEmpty() ? "." : " — this call gives " + String.join(", ", given) + "."));
        }
        if (params != null && source == null) {
            return Optional.of("params belong to source; the command and ws forms take none.");
        }
        if (command != null) {
            if (!(command instanceof String text)) {
                return Optional.of("Invalid parameter: command must be a string");
            }
            request.kind(ShellMonitorSourceFactory.KIND).param(ShellMonitorSourceFactory.COMMAND_PARAM, text);
        } else if (ws != null) {
            if (!(ws instanceof Map<?, ?> socket)) {
                return Optional.of("Invalid parameter: ws must be an object with a url");
            }
            request.kind(WEBSOCKET_KIND).param("url", socket.get("url")).param("protocols", socket.get("protocols"));
        } else {
            if (!(source instanceof String kind) || kind.isBlank()) {
                return Optional.of("Invalid parameter: source must name a source kind");
            }
            request.kind(kind.strip());
            if (params != null) {
                if (!(params instanceof Map<?, ?> values)) {
                    return Optional.of("Invalid parameter: params must be an object");
                }
                values.forEach((key, value) -> request.param(String.valueOf(key), value));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        // Read raw: this runs before execute(), where nothing would catch a type mismatch.
        if (input.get("command") instanceof String command && !command.isBlank()) {
            return Optional.of(PermissionSubject.command(command));
        }
        return Optional.empty();
    }

    @Override
    public Optional<PermissionDelegation> permissionDelegation(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        if (input.get("command") instanceof String command && !command.isBlank()) {
            return Optional.of(PermissionDelegation.to(BashTool.TOOL_NAME, PermissionSubject.command(command)));
        }
        return Optional.empty();
    }

    @Override
    public CustomToolPermissionRule getCustomPermissionRule() {
        return permissionRule;
    }

    /**
     * Judges the {@code ws} and {@code source} forms, which have no framework subject. Stateless.
     */
    private static final class Rule implements CustomToolPermissionRule {

        @Override
        public boolean isAllowed(ToolInput input, ToolContext context, List<AllowedTool> allowedTools) {
            final Optional<String> target = target(input);
            if (target.isEmpty()) {
                return false;
            }
            boolean anyPattern = false;
            for (AllowedTool allowed : allowedTools) {
                if (allowed.getPattern().isEmpty()) {
                    continue;
                }
                anyPattern = true;
                if (matches(allowed.getPattern().get().getPattern(), target.get())) {
                    return true;
                }
            }
            return !anyPattern && !allowedTools.isEmpty();
        }

        @Override
        public String buildErrorDetail(ToolInput input, ToolContext context) {
            return target(input).map(t -> " (" + t + ")").orElse("");
        }

        private static Optional<String> target(ToolInput input) {
            if (input.get("ws") instanceof Map<?, ?> socket && socket.get("url") instanceof String url) {
                return Optional.of("ws:" + url);
            }
            if (input.get("source") instanceof String kind && !kind.isBlank()) {
                return Optional.of("source:" + kind.strip());
            }
            return Optional.empty();
        }

        private static boolean matches(String pattern, String target) {
            if (pattern.endsWith("*")) {
                return target.startsWith(pattern.substring(0, pattern.length() - 1));
            }
            return pattern.equals(target);
        }
    }
}
