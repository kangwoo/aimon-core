package at.aimon.core.hook.rewake.mcp;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import at.aimon.core.hook.rewake.ExternalEvent;

/**
 * Default {@link McpNotificationMapper} that handles the common MCP notification methods and falls back to a
 * generic {@code "mcp.<tail>"} mapping for unknown notifications.
 *
 * <h2>Mapping rules</h2>
 * <ul>
 * <li>{@code notifications/resources/updated} → {@code eventType=mcp.resource_updated},
 * {@code eventKey=params.uri}.
 * <li>{@code notifications/resources/list_changed} → {@code eventType=mcp.resource_list_changed},
 * {@code eventKey=*} (wildcard sentinel — list-changed notifications carry no per-resource key).
 * <li>{@code notifications/tools/list_changed} → {@code eventType=mcp.tool_list_changed},
 * {@code eventKey=*}.
 * <li>{@code notifications/prompts/list_changed} → {@code eventType=mcp.prompt_list_changed},
 * {@code eventKey=*}.
 * <li>{@code notifications/log} → skipped (returns {@code null}); informational only, never bound to rewake.
 * <li>Any other {@code notifications/<tail>} → {@code eventType=mcp.<tail-with-slash-as-dot>}, with an eventKey
 * extracted from the first available text field among {@code uri}, {@code key}, {@code id}, {@code name}. If none
 * of those are present the notification is skipped (returns {@code null}) — without a key the envelope match would
 * be ambiguous.
 * </ul>
 *
 * <p>
 * The mapper additionally copies every text-valued top-level params field into {@link ExternalEvent}'s payload, so
 * downstream listeners observing the fired envelope see the full notification context.
 *
 * <p>
 * The {@code "*"} wildcard for list-changed notifications matches any envelope created with
 * {@code RewakeTriggerEvent("mcp.<x>_list_changed", "*")}. Hooks that genuinely want to wake on list-changed events
 * must register with that exact key — there is no fan-out to multiple keys at the SPI layer.
 */
public final class DefaultMcpNotificationMapper implements McpNotificationMapper {

    public static final String NOTIFICATION_PREFIX = "notifications/";
    public static final String EVENT_TYPE_PREFIX = "mcp.";
    public static final String LIST_CHANGED_KEY = "*";

    private static final Map<String, String> KNOWN_METHODS = Map.of("notifications/resources/updated",
            "mcp.resource_updated", "notifications/resources/list_changed", "mcp.resource_list_changed",
            "notifications/tools/list_changed", "mcp.tool_list_changed", "notifications/prompts/list_changed",
            "mcp.prompt_list_changed");

    private static final List<String> KEY_FIELDS = List.of("uri", "key", "id", "name");

    @Override
    public ExternalEvent map(String method, JsonNode params) {
        Objects.requireNonNull(method, "method cannot be null");
        Objects.requireNonNull(params, "params cannot be null");

        if ("notifications/log".equals(method)) {
            return null;
        }

        final String eventType = KNOWN_METHODS.getOrDefault(method, deriveGenericEventType(method));
        if (eventType == null) {
            return null;
        }

        final String eventKey = resolveEventKey(method, params);
        if (eventKey == null) {
            return null;
        }

        final ExternalEvent.Builder builder = ExternalEvent.builder().eventType(eventType).eventKey(eventKey)
                .sourceTransport("mcp");
        copyTextParamsToPayload(params, builder);
        return builder.build();
    }

    private static String deriveGenericEventType(String method) {
        if (!method.startsWith(NOTIFICATION_PREFIX)) {
            return null;
        }
        final String tail = method.substring(NOTIFICATION_PREFIX.length());
        if (tail.isBlank()) {
            return null;
        }
        return EVENT_TYPE_PREFIX + tail.replace('/', '.');
    }

    private static String resolveEventKey(String method, JsonNode params) {
        if (method.endsWith("/list_changed")) {
            return LIST_CHANGED_KEY;
        }
        for (String field : KEY_FIELDS) {
            final JsonNode candidate = params.get(field);
            if (candidate != null && candidate.isTextual() && !candidate.asText().isBlank()) {
                return candidate.asText();
            }
        }
        return null;
    }

    private static void copyTextParamsToPayload(JsonNode params, ExternalEvent.Builder builder) {
        if (params == null || !params.isObject()) {
            return;
        }
        final Map<String, String> entries = new LinkedHashMap<>();
        final Iterator<Map.Entry<String, JsonNode>> fields = params.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> entry = fields.next();
            final JsonNode value = entry.getValue();
            if (value != null && value.isTextual()) {
                entries.put(entry.getKey(), value.asText());
            }
        }
        if (!entries.isEmpty()) {
            builder.payload(entries);
        }
    }
}
