package at.aimon.core.skill.parser;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.skill.hook.SkillHookSet;
import at.aimon.core.skill.hook.action.DenyAction;
import at.aimon.core.skill.hook.action.HookAction;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.HttpMethod;
import at.aimon.core.skill.hook.action.McpToolAction;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.DeclarativeHookOptions;
import at.aimon.core.skill.hook.declarative.DeclarativePostToolHook;
import at.aimon.core.skill.hook.declarative.DeclarativePreToolHook;
import at.aimon.core.skill.hook.declarative.DeclarativeShellHookBinding;
import at.aimon.core.skill.hook.declarative.HttpActionExecutor;
import at.aimon.core.skill.hook.declarative.McpActionExecutor;
import at.aimon.core.skill.hook.declarative.NoOpShellActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellActionExecutor;
import at.aimon.core.skill.hook.declarative.ToolInputPredicate;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;
import at.aimon.core.skill.hook.declarative.predicate.PredicateParser;

/**
 * Parses the {@code hooks:} block of a SKILL.md frontmatter into a {@link SkillHookSet} (AIMON extension, SK-13).
 *
 * <p>
 * Frontmatter shape:
 *
 * <pre>
 * hooks:
 *   preTool:
 *     - matcher: "Bash"
 *       action: { type: deny, reason: "Bash not allowed in this skill" }
 *     - matcher: "Read"
 *       action: { type: shell, command: "echo reading >&amp;2", timeoutMs: 5000 }
 *     - matcher: "Edit"
 *       action: { type: http, url: "https://hooks.example/audit", method: POST,
 *                 body: '{"path":"${tool_input.file_path}"}',
 *                 allowedEnvVars: [ AUDIT_TOKEN ], timeoutMs: 5000 }
 *     - matcher: "Bash"
 *       action: { type: mcp, server: "github", tool: "report",
 *                 args: { title: "${tool_input.command}" } }
 *   postTool:
 *     - matcher: "*"
 *       action: { type: shell, command: "echo done" }
 *   onStart:
 *     - action: { type: shell, command: "echo started" }
 *   onStop:
 *     - action: { type: shell, command: "echo stopped" }
 *   subagentStop:
 *     - action: { type: shell, command: "echo subagent done" }
 *   permissionDenied:
 *     - action: { type: shell, command: "logger -t aimon denied" }
 * </pre>
 *
 * <p>
 * Accepted events are exactly {@link SkillHookSet#supportedEvents()}. {@code onSessionStart}, {@code onSessionEnd} and
 * {@code onConfigReload} are rejected here on purpose: they fire on the session / application lifecycle, outside any
 * skill invocation, so a per-skill registration for them could never fire. Declare those in {@code hooks.json}.
 *
 * <p>
 * Rules enforced at parse time:
 * <ul>
 * <li>{@code matcher} is optional for {@code preTool} / {@code postTool} (defaults to {@code "*"}); not allowed for any
 * other event.
 * <li>{@code action.type: deny} is only valid under {@code preTool} &mdash; every other event is non-blocking by
 * interface contract, or (for {@code preCompact} / {@code permissionRequest}) vetoes through the shell exit code
 * rather than through a static action.
 * <li>Every event except {@code preTool} / {@code postTool} accepts {@code action.type: shell} only &mdash; those two
 * are the only ones carrying a tool input for an HTTP / MCP payload to template against.
 * <li>{@code action.type: shell} requires the supplied {@link ShellActionExecutor} to report
 * {@link ShellActionExecutor#isShellSupported()} as {@code true}.
 * <li>{@code action.type: http} / {@code mcp} only parse when the matching executor was supplied; otherwise the
 * parser fails with a clear message so misconfigurations surface at skill-load time.
 * </ul>
 *
 * <p>
 * Thread-safe and stateless once constructed.
 */
public final class SkillHookSetParser {

    private static final Logger log = LoggerFactory.getLogger(SkillHookSetParser.class);

    /**
     * Event names accepted in frontmatter, in activation order.
     *
     * <p>
     * Derived from {@link SkillHookSet#supportedEvents()} rather than hard-coded so a new skill-scopable event becomes
     * declarable by extending that one list. Order is preserved so the "allowed:" text in error messages reads in a
     * stable, meaningful order.
     */
    private static final Set<String> KNOWN_EVENTS = knownEvents();

    private static Set<String> knownEvents() {
        final Set<String> names = new LinkedHashSet<>();
        for (HookEventType<?> type : SkillHookSet.supportedEvents()) {
            names.add(type.name());
        }
        return Collections.unmodifiableSet(names);
    }

    private final ShellActionExecutor shellExecutor;
    private final HttpActionExecutor httpExecutor;
    private final McpActionExecutor mcpExecutor;
    private final Map<String, String> processEnv;

    /**
     * Creates a parser with shell / HTTP / MCP actions disabled.
     *
     * <p>
     * The env snapshot defaults to {@code Map.of()}; HTTP/MCP transports are absent so {@code ${env.X}}
     * substitution would not be reachable anyway. Tests rely on this defaulting to keep the parser hermetic.
     */
    public SkillHookSetParser() {
        this(NoOpShellActionExecutor.INSTANCE, null, null, Map.of());
    }

    /**
     * Creates a parser using the given executor for shell actions only (legacy SK-13 wiring).
     *
     * <p>
     * The env snapshot defaults to {@code Map.of()}. Callers that need {@code ${env.X}} substitution in HTTP/MCP
     * actions must use the 4-arg constructor and pass an explicit env snapshot (typically {@code System.getenv()} at
     * bootstrap time).
     *
     * @param shellExecutor
     *            shell executor (must not be null)
     */
    public SkillHookSetParser(ShellActionExecutor shellExecutor) {
        this(shellExecutor, null, null, Map.of());
    }

    /**
     * Creates a parser with full executor support.
     *
     * @param shellExecutor
     *            executor for shell actions (must not be null)
     * @param httpExecutor
     *            executor for HTTP actions (may be null; when null, {@code type: http} is rejected at parse time)
     * @param mcpExecutor
     *            executor for MCP actions (may be null; when null, {@code type: mcp} is rejected at parse time)
     * @param processEnv
     *            process environment snapshot (must not be null)
     */
    public SkillHookSetParser(ShellActionExecutor shellExecutor, HttpActionExecutor httpExecutor,
            McpActionExecutor mcpExecutor, Map<String, String> processEnv) {
        this.shellExecutor = Objects.requireNonNull(shellExecutor, "Shell executor cannot be null");
        this.httpExecutor = httpExecutor;
        this.mcpExecutor = mcpExecutor;
        this.processEnv = Map.copyOf(Objects.requireNonNull(processEnv, "processEnv cannot be null"));
    }

    /**
     * Parses the {@code hooks} subtree of a frontmatter map.
     *
     * @param skillName
     *            the owning skill name (must not be null)
     * @param hooksNode
     *            raw value of the {@code hooks} key. {@code null} yields {@link SkillHookSet#empty()}.
     * @return parsed hook set (never null)
     */
    public SkillHookSet parse(String skillName, Object hooksNode) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        if (hooksNode == null) {
            return SkillHookSet.empty();
        }
        if (!(hooksNode instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Field 'hooks' must be a mapping with event-name keys, got: "
                    + hooksNode.getClass().getSimpleName());
        }

        final SkillHookSet.Builder builder = SkillHookSet.builder();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            final String event = requireString("hooks", entry.getKey());
            if (!KNOWN_EVENTS.contains(event)) {
                throw new IllegalArgumentException("Field 'hooks' has unknown event: '" + event + "' (allowed: "
                        + KNOWN_EVENTS + "). Session- and config-lifecycle events fire outside any skill invocation,"
                        + " so they can only be declared in hooks.json.");
            }
            final List<?> defs = requireList("hooks." + event, entry.getValue());
            for (int i = 0; i < defs.size(); i++) {
                final Object def = defs.get(i);
                final String path = "hooks." + event + "[" + i + "]";
                addHook(builder, skillName, event, requireMap(path, def), path);
            }
        }
        return builder.build();
    }

    private void addHook(SkillHookSet.Builder builder, String skillName, String event, Map<?, ?> def, String path) {
        final ToolInputPredicate predicate = parseMatcher(event, def, path);
        final HookAction action = parseAction(event, def, path);
        // The frontmatter path (e.g. "hooks.preTool[1]") is both unique within the skill and stable across reloads
        // of an unchanged skill file, which is exactly what a hook id discriminator has to be: without it every
        // hook of a class would share one id, so rewake deliveries could not be routed and a reload could not tell
        // which pending rewakes belong to a hook that actually changed. See DeclarativeHookId.
        final DeclarativeHookOptions options = DeclarativeHookOptions.ofDiscriminator(path);
        switch (event) {
            case DeclarativePreToolHook.EVENT_NAME -> builder.addPreTool(new DeclarativePreToolHook(skillName,
                    predicate, action, shellExecutor, httpExecutor, mcpExecutor, processEnv, options));
            case DeclarativePostToolHook.EVENT_NAME ->
                builder.addPostTool(new DeclarativePostToolHook(skillName, predicate, requireNonBlocking(action, path),
                        shellExecutor, httpExecutor, mcpExecutor, processEnv, options));
            default -> {
                // Everything else is shell-only and shares one constructor shape, so it resolves through the same
                // binding table hooks.json uses — the two front-ends cannot drift as events are added.
                final DeclarativeShellHookBinding<?> binding = DeclarativeShellHookBinding.forEvent(event)
                        .orElseThrow(() -> new IllegalStateException("Unhandled event: " + event));
                addShellHook(builder, binding, skillName, requireShell(action, path), options);
            }
        }
    }

    /**
     * Adds one shell-only hook, recovering the binding's hook type through capture conversion so
     * {@link SkillHookSet.Builder#add} stays type-safe without a cast.
     */
    private <H extends ExecutionHook<?>> void addShellHook(SkillHookSet.Builder builder,
            DeclarativeShellHookBinding<H> binding, String skillName, ShellAction action,
            DeclarativeHookOptions options) {
        builder.add(binding.getEventType(), binding.create(skillName, action, shellExecutor, options));
    }

    private ToolInputPredicate parseMatcher(String event, Map<?, ?> def, String path) {
        final Object raw = def.get("matcher");
        final boolean isToolEvent = DeclarativePreToolHook.EVENT_NAME.equals(event)
                || DeclarativePostToolHook.EVENT_NAME.equals(event);
        if (!isToolEvent) {
            if (raw != null) {
                throw new IllegalArgumentException(
                        path + ": 'matcher' is only valid for preTool / postTool hooks, not " + event);
            }
            return NameOnlyPredicate.ANY;
        }
        if (raw == null) {
            return NameOnlyPredicate.ANY;
        }
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException(
                    path + ".matcher must be a string, got: " + raw.getClass().getSimpleName());
        }
        if (s.isBlank() || "*".equals(s.strip())) {
            return NameOnlyPredicate.ANY;
        }
        try {
            return PredicateParser.parse(s);
        } catch (IllegalArgumentException ex) {
            // Fail-fast on an unparseable matcher rather than silently degrading to NameOnlyPredicate.of(s): the raw
            // pattern string (e.g. "Fetch(secret/*)") can never equal a real tool name, so the predicate would match
            // nothing and a deny/preTool hook the author intended to block would become a silent fail-OPEN no-op.
            // Throwing here mirrors every other malformed-field path in this parser; MarkdownSkillParser wraps it into
            // a SkillParseException at load time so the misconfiguration surfaces instead of disabling a guard.
            throw new IllegalArgumentException(
                    path + ".matcher could not be parsed: '" + s + "' (" + ex.getMessage() + ")", ex);
        }
    }

    private HookAction parseAction(String event, Map<?, ?> def, String path) {
        final Object raw = def.get("action");
        if (raw == null) {
            throw new IllegalArgumentException(path + " is missing required field 'action'");
        }
        final Map<?, ?> actionMap = requireMap(path + ".action", raw);
        final String type = requireString(path + ".action.type", actionMap.get("type"));
        switch (type) {
            case "deny" -> {
                if (!DeclarativePreToolHook.EVENT_NAME.equals(event)) {
                    throw new IllegalArgumentException(
                            path + ".action: 'deny' is only valid for preTool hooks, not " + event);
                }
                return new DenyAction(requireString(path + ".action.reason", actionMap.get("reason")));
            }
            case "shell" -> {
                if (!shellExecutor.isShellSupported()) {
                    throw new IllegalArgumentException(path + ".action: shell hooks are not supported in this"
                            + " configuration. Wire SkillHookSetParser with a DefaultShellActionExecutor to enable"
                            + " them.");
                }
                final String command = requireString(path + ".action.command", actionMap.get("command"));
                final Duration timeout = parseTimeout(path + ".action.timeoutMs", actionMap.get("timeoutMs"));
                return new ShellAction(command, timeout);
            }
            case "http" -> {
                if (httpExecutor == null) {
                    throw new IllegalArgumentException(path + ".action: 'http' hooks are not supported in this"
                            + " configuration. Wire SkillHookSetParser with an HttpActionExecutor to enable them.");
                }
                return parseHttpAction(actionMap, path);
            }
            case "mcp" -> {
                if (mcpExecutor == null) {
                    throw new IllegalArgumentException(path + ".action: 'mcp' hooks are not supported in this"
                            + " configuration. Wire SkillHookSetParser with an McpActionExecutor to enable them.");
                }
                return parseMcpAction(actionMap, path);
            }
            default -> throw new IllegalArgumentException(
                    path + ".action.type unknown: '" + type + "' (allowed: deny, shell, http, mcp)");
        }
    }

    private static HttpAction parseHttpAction(Map<?, ?> actionMap, String path) {
        final String urlText = requireString(path + ".action.url", actionMap.get("url"));
        final URI url;
        try {
            url = new URI(urlText);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(path + ".action.url is not a valid URI: " + e.getMessage(), e);
        }
        final HttpAction.Builder b = HttpAction.builder().url(url);

        final Object methodNode = actionMap.get("method");
        if (methodNode != null) {
            if (!(methodNode instanceof String ms)) {
                throw new IllegalArgumentException(path + ".action.method must be a string");
            }
            try {
                b.method(HttpMethod.valueOf(ms.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(path + ".action.method unknown: '" + ms + "'", e);
            }
        }

        final Object headersNode = actionMap.get("headers");
        if (headersNode != null) {
            final Map<?, ?> hm = requireMap(path + ".action.headers", headersNode);
            for (Map.Entry<?, ?> h : hm.entrySet()) {
                b.addHeader(requireString(path + ".action.headers.key", h.getKey()),
                        requireString(path + ".action.headers." + h.getKey(), h.getValue()));
            }
        }

        final Object bodyNode = actionMap.get("body");
        if (bodyNode != null) {
            if (!(bodyNode instanceof String bs)) {
                throw new IllegalArgumentException(path + ".action.body must be a string");
            }
            b.bodyTemplate(bs);
        }

        final Object envNode = actionMap.get("allowedEnvVars");
        if (envNode != null) {
            final List<?> list = requireList(path + ".action.allowedEnvVars", envNode);
            final Set<String> envs = new LinkedHashSet<>();
            for (int i = 0; i < list.size(); i++) {
                envs.add(requireString(path + ".action.allowedEnvVars[" + i + "]", list.get(i)));
            }
            b.allowedEnvVars(envs);
        }

        final Duration timeout = parseTimeout(path + ".action.timeoutMs", actionMap.get("timeoutMs"));
        if (timeout != null) {
            b.timeout(timeout);
        }
        return b.build();
    }

    private static McpToolAction parseMcpAction(Map<?, ?> actionMap, String path) {
        final String server = requireString(path + ".action.server", actionMap.get("server"));
        final String tool = requireString(path + ".action.tool", actionMap.get("tool"));
        final McpToolAction.Builder b = McpToolAction.builder().serverName(server).toolName(tool);

        final Object argsNode = actionMap.get("args");
        if (argsNode != null) {
            final Map<?, ?> raw = requireMap(path + ".action.args", argsNode);
            final Map<String, Object> argTemplate = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                argTemplate.put(requireString(path + ".action.args.key", e.getKey()), e.getValue());
            }
            b.argsTemplate(argTemplate);
        }

        final Duration timeout = parseTimeout(path + ".action.timeoutMs", actionMap.get("timeoutMs"));
        if (timeout != null) {
            b.timeout(timeout);
        }
        return b.build();
    }

    private static ShellAction requireShell(HookAction action, String path) {
        if (action instanceof ShellAction shell) {
            return shell;
        }
        throw new IllegalArgumentException(
                path + ".action: only 'shell' is valid here, got: " + actionTypeName(action));
    }

    private static HookAction requireNonBlocking(HookAction action, String path) {
        if (action instanceof DenyAction) {
            throw new IllegalArgumentException(path + ".action: postTool hooks cannot use 'deny'");
        }
        return action;
    }

    private static String actionTypeName(HookAction action) {
        if (action instanceof DenyAction) {
            return "deny";
        }
        if (action instanceof ShellAction) {
            return "shell";
        }
        if (action instanceof HttpAction) {
            return "http";
        }
        if (action instanceof McpToolAction) {
            return "mcp";
        }
        return action == null ? "null" : action.getClass().getSimpleName();
    }

    private static Duration parseTimeout(String path, Object raw) {
        if (raw == null) {
            return null;
        }
        final long ms;
        if (raw instanceof Integer i) {
            ms = i.longValue();
        } else if (raw instanceof Long l) {
            ms = l;
        } else {
            throw new IllegalArgumentException(
                    path + " must be a positive integer (milliseconds), got: " + raw.getClass().getSimpleName());
        }
        if (ms <= 0) {
            throw new IllegalArgumentException(path + " must be positive, but was: " + ms);
        }
        return Duration.ofMillis(ms);
    }

    private static String requireString(String path, Object raw) {
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException(
                    path + " must be a string, got: " + (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        if (s.isBlank()) {
            throw new IllegalArgumentException(path + " cannot be blank");
        }
        return s;
    }

    private static List<?> requireList(String path, Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    path + " must be a list, got: " + (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        return list;
    }

    private static Map<?, ?> requireMap(String path, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    path + " must be a mapping, got: " + (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        return map;
    }
}
