package at.aimon.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Listener invoked when an MCP server pushes a JSON-RPC notification (a frame with a {@code method} but no
 * {@code id}, e.g. {@code "notifications/resources/updated"}).
 *
 * <p>
 * The listener is meant to be the seam between {@link at.aimon.core.mcp.transport.McpTransport McpTransport} (which
 * receives the raw JSON frame) and downstream consumers — most importantly the rewake bridge that translates
 * notifications into {@link at.aimon.core.hook.rewake.ExternalEvent ExternalEvent} dispatches.
 *
 * <p>
 * <strong>That seam is not connected yet.</strong> No transport holds a listener and no client exposes a way to
 * register one: {@code StdioMcpTransport} matches each inbound frame against the id of the request it is waiting on
 * and drops everything else, notifications included. Until that read path fans out, the only implementation
 * ({@code McpNotificationToRewakeBridge}) has to be driven by the embedding application. See
 * {@code docs/design/hook/async-rewake.md} §8.
 *
 * <p>
 * <strong>Why a typed listener instead of routing directly to {@code ExternalEventResolver}?</strong> The MCP
 * notification surface is broader than rewake — log forwarding, capability change announcements, and tool-list
 * invalidation all share the same JSON-RPC notification frame shape but flow to different consumers. A neutral
 * listener lets multiple subscribers coexist (the bridge for rewake, a future capability tracker, ...) without
 * coupling the transport to any one of them.
 *
 * <p>
 * Implementations must be thread-safe and must not throw. A dispatcher is expected to log and swallow an escaping
 * exception rather than propagate it, so that one misbehaving subscriber cannot stall the incoming-message loop — but
 * an implementation must not lean on that net; it catches inside {@link #onNotification} itself, as
 * {@code McpNotificationToRewakeBridge} does.
 *
 * <p>
 * Dispatch is expected to happen on the dispatcher's read path, so handlers should be fast or hand work to a queue.
 */
@FunctionalInterface
public interface McpNotificationListener {

    /**
     * No-op listener — useful as a default before any subscriber is wired up.
     */
    McpNotificationListener NOOP = (method, params) -> {
    };

    /**
     * Called with a parsed incoming JSON-RPC notification frame.
     *
     * @param method
     *            the JSON-RPC method (never null or blank, e.g. {@code "notifications/resources/updated"})
     * @param params
     *            the {@code params} member of the notification frame (never null; possibly an empty object node if
     *            the server omitted params)
     */
    void onNotification(String method, JsonNode params);
}
