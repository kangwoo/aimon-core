package at.aimon.core.skill.hook.declarative;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Common template renderer for declarative hook actions.
 *
 * <p>
 * Resolves three kinds of placeholders inside a template string:
 * <ul>
 * <li>{@code ${tool_input.&lt;key&gt;}} &mdash; reads from the tool input map. Nested map access is supported with dot
 * notation (e.g. {@code ${tool_input.payload.id}}).
 * <li>{@code ${env.&lt;NAME&gt;}} &mdash; reads from the supplied environment whitelist. A key not present in the
 * whitelist is rendered to the empty string and never escapes — this is the security boundary that prevents
 * declarative hooks from exfiltrating arbitrary process env.
 * <li>{@code ${context.&lt;name&gt;}} &mdash; reads from the supplied context attribute map (e.g. {@code session_id},
 * {@code invoker_name}, {@code tool_name}, {@code iteration}).
 * </ul>
 *
 * <p>
 * Missing keys (other than env, which has whitelist semantics) render to the empty string. The placeholder syntax is
 * literal — no nested expressions, no escape sequences. A literal {@code ${...}} that does not match a known prefix is
 * left untouched so authors can include it verbatim in shell snippets.
 *
 * <p>
 * The renderer is also used to render JSON object templates (e.g. for HTTP body / MCP args). For map templates, every
 * string leaf is rendered recursively; map / list nodes are walked structurally. Non-string leaves are forwarded as-is.
 *
 * <p>
 * <b>Scope: HTTP and MCP actions only.</b> {@code HttpActionExecutor} renders headers / body and
 * {@code McpActionExecutor} renders the args template. A {@link ShellAction#getCommand() shell-action command} is
 * <b>never</b> passed through this renderer &mdash; it reaches the host shell verbatim, so a {@code ${tool_input.x}}
 * written into a command is not a placeholder at all but an ordinary (unset) shell variable. That is deliberate: it
 * keeps untrusted tool input out of the command line entirely. Shell hooks receive the same data as a JSON document on
 * standard input (see {@code ShellHookPayload}) plus the {@code AIMON_*} environment variables, neither of which the
 * shell re-interprets as syntax.
 *
 * <p>
 * <b>No escaping.</b> Rendered values are substituted literally, so the caller owns any quoting its target requires.
 * The whitelist on {@code ${env.&lt;NAME&gt;}} bounds which process-env values can be read at all; it does not sanitize
 * the values it lets through.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class TemplateRenderer {

    /**
     * Matches {@code ${prefix.path}} placeholders. {@code prefix} is one of {@code tool_input}, {@code env},
     * {@code context}.
     */
    private static final Pattern PLACEHOLDER = Pattern
            .compile("\\$\\{(tool_input|env|context)\\.([A-Za-z_][A-Za-z0-9_.-]*)}");

    private final ToolInput toolInput;
    private final Map<String, String> envWhitelist;
    private final Map<String, String> context;

    private TemplateRenderer(Builder builder) {
        this.toolInput = builder.toolInput != null ? builder.toolInput : ToolInput.of();
        this.envWhitelist = Map.copyOf(builder.envWhitelist);
        this.context = Map.copyOf(builder.context);
    }

    /**
     * Creates a new builder.
     *
     * @return a fresh builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Renders all placeholders in the supplied template.
     *
     * @param template
     *            template string (must not be null)
     * @return rendered string (never null)
     * @throws NullPointerException
     *             if template is null
     */
    public String render(String template) {
        Objects.requireNonNull(template, "Template cannot be null");
        final Matcher m = PLACEHOLDER.matcher(template);
        final StringBuilder out = new StringBuilder(template.length());
        while (m.find()) {
            final String prefix = m.group(1);
            final String path = m.group(2);
            m.appendReplacement(out, Matcher.quoteReplacement(resolve(prefix, path)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Recursively renders a JSON-shaped template.
     *
     * <p>
     * String leaves are rendered via {@link #render(String)}. Map / List nodes are walked structurally with copies.
     * Numbers, booleans and {@code null} are forwarded unchanged. Unknown leaf types are forwarded as-is so callers can
     * add their own value adapters (e.g. {@code Number} subtypes from Jackson).
     *
     * @param template
     *            template node (may be null)
     * @return rendered structure (never null reference for {@code Map}/{@code List} inputs; {@code null} for
     *         {@code null}
     *         input)
     */
    public Object renderObject(Object template) {
        if (template == null) {
            return null;
        }
        if (template instanceof String s) {
            return render(s);
        }
        if (template instanceof Map<?, ?> map) {
            final Map<String, Object> result = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), renderObject(e.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (template instanceof Iterable<?> list) {
            final java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            for (Object element : list) {
                result.add(renderObject(element));
            }
            return Collections.unmodifiableList(result);
        }
        return template;
    }

    /**
     * Returns the env whitelist used by this renderer.
     *
     * @return immutable view of the whitelist
     */
    public Map<String, String> getEnvWhitelist() {
        return envWhitelist;
    }

    /**
     * Returns the env keys that are exposed to {@code ${env.X}} placeholders.
     *
     * @return immutable set of env keys
     */
    public Set<String> getEnvWhitelistKeys() {
        return envWhitelist.keySet();
    }

    private String resolve(String prefix, String path) {
        return switch (prefix) {
            case "tool_input" -> stringify(lookupToolInput(path));
            case "env" -> envWhitelist.getOrDefault(path, "");
            case "context" -> context.getOrDefault(path, "");
            default -> "";
        };
    }

    private Object lookupToolInput(String path) {
        final String[] parts = path.split("\\.");
        Object current = toolInput.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    /** Builder for {@link TemplateRenderer}. */
    public static final class Builder {
        private ToolInput toolInput;
        private final Map<String, String> envWhitelist = new LinkedHashMap<>();
        private final Map<String, String> context = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the tool input source for {@code ${tool_input.X}} placeholders.
         *
         * @param toolInput
         *            tool input (may be null; null means an empty input)
         * @return this builder
         */
        public Builder toolInput(ToolInput toolInput) {
            this.toolInput = toolInput;
            return this;
        }

        /**
         * Sets the env whitelist used by {@code ${env.X}} placeholders.
         *
         * <p>
         * Keys that are absent from this map render to the empty string regardless of process env. The whitelist is the
         * sole source of truth — process env is never read here.
         *
         * @param envWhitelist
         *            whitelist (must not be null)
         * @return this builder
         */
        public Builder envWhitelist(Map<String, String> envWhitelist) {
            Objects.requireNonNull(envWhitelist, "envWhitelist cannot be null");
            this.envWhitelist.clear();
            this.envWhitelist.putAll(envWhitelist);
            return this;
        }

        /**
         * Adds a single context attribute used by {@code ${context.X}} placeholders.
         *
         * @param key
         *            attribute key (must not be null)
         * @param value
         *            attribute value (may be null; null is rendered as the empty string)
         * @return this builder
         */
        public Builder contextAttribute(String key, String value) {
            Objects.requireNonNull(key, "Key cannot be null");
            this.context.put(key, value == null ? "" : value);
            return this;
        }

        /**
         * Sets all context attributes at once, replacing any previously-added entries.
         *
         * @param context
         *            attribute map (must not be null)
         * @return this builder
         */
        public Builder context(Map<String, String> context) {
            Objects.requireNonNull(context, "context cannot be null");
            this.context.clear();
            this.context.putAll(context);
            return this;
        }

        /**
         * Builds the renderer.
         *
         * @return a new renderer (never null)
         */
        public TemplateRenderer build() {
            return new TemplateRenderer(this);
        }
    }
}
