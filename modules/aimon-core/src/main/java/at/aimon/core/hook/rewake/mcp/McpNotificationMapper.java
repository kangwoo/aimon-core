package at.aimon.core.hook.rewake.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import at.aimon.core.hook.rewake.ExternalEvent;

/**
 * Strategy that translates an inbound MCP JSON-RPC notification into an {@link ExternalEvent} suitable for
 * dispatch through {@link at.aimon.core.hook.rewake.ExternalEventResolver ExternalEventResolver}.
 *
 * <p>
 * Returning {@code null} means "skip this notification" — useful when the notification is informational only (e.g.
 * {@code notifications/log}) and should not fire any rewake envelope. Implementations must be thread-safe.
 *
 * <p>
 * The default implementation, {@link DefaultMcpNotificationMapper}, covers the common MCP notifications. Custom
 * mappers let operators bind server-specific notification methods to their own {@code eventType} naming scheme.
 */
@FunctionalInterface
public interface McpNotificationMapper {

    /**
     * Translates an MCP notification frame into an {@link ExternalEvent}.
     *
     * @param method
     *            the JSON-RPC method (never null or blank, e.g. {@code "notifications/resources/updated"})
     * @param params
     *            the {@code params} member of the frame (never null; may be an empty/absent node)
     * @return the event to dispatch, or {@code null} if the notification should be skipped
     */
    ExternalEvent map(String method, JsonNode params);
}
