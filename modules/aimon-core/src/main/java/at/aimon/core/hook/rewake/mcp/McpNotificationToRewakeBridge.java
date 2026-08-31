package at.aimon.core.hook.rewake.mcp;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import at.aimon.core.hook.rewake.ExternalEvent;
import at.aimon.core.hook.rewake.ExternalEventResolver;
import at.aimon.core.mcp.McpNotificationListener;

/**
 * Bridges the MCP notification surface to the rewake pipeline.
 *
 * <p>
 * Implements {@link McpNotificationListener} and forwards every notification (after passing it through a
 * {@link McpNotificationMapper}) to an {@link ExternalEventResolver}.
 *
 * <p>
 * <strong>Nothing in-tree calls this bridge yet.</strong> There is no registration point on {@code McpClient} or
 * {@code McpClientManager}, and {@code StdioMcpTransport} discards inbound notification frames rather than fanning
 * them out — so the mapper and resolver below only run if an assembly invokes {@link #onNotification} itself. Wiring
 * the transport read path to this listener is tracked in {@code docs/design/hook/async-rewake.md} §8.
 *
 * <p>
 * The mapper decides which notifications are bound to rewake events and how method/params translate into
 * {@link ExternalEvent} fields; see {@link DefaultMcpNotificationMapper} for the default rules.
 *
 * <p>
 * The bridge is fail-safe: if the mapper or resolver throws, the exception is logged at WARN and swallowed so a
 * single misbehaving notification cannot stall whatever thread delivered it. Notifications that the mapper returns
 * {@code null} for are silently skipped (no rewake match attempted).
 *
 * <p>
 * Thread-safe: holds only immutable references to the resolver and mapper.
 */
public final class McpNotificationToRewakeBridge implements McpNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(McpNotificationToRewakeBridge.class);

    private final ExternalEventResolver resolver;
    private final McpNotificationMapper mapper;

    /**
     * Builds a bridge using {@link DefaultMcpNotificationMapper} for translation.
     *
     * @param resolver
     *            the rewake resolver to forward mapped events to (must not be null)
     */
    public McpNotificationToRewakeBridge(ExternalEventResolver resolver) {
        this(resolver, new DefaultMcpNotificationMapper());
    }

    /**
     * Builds a bridge using the supplied mapper.
     *
     * @param resolver
     *            the rewake resolver to forward mapped events to (must not be null)
     * @param mapper
     *            the mapper to translate notifications (must not be null)
     */
    public McpNotificationToRewakeBridge(ExternalEventResolver resolver, McpNotificationMapper mapper) {
        this.resolver = Objects.requireNonNull(resolver, "resolver cannot be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
    }

    @Override
    public void onNotification(String method, JsonNode params) {
        Objects.requireNonNull(method, "method cannot be null");
        final JsonNode safeParams = params != null ? params : MissingNode.getInstance();
        try {
            final ExternalEvent event = mapper.map(method, safeParams);
            if (event == null) {
                log.debug("rewake.mcp.skipped method={}", method);
                return;
            }
            final int matched = resolver.resolve(event);
            log.debug("rewake.mcp.dispatched method={} eventType={} eventKey={} matched={}", method,
                    event.getEventType(), event.getEventKey(), matched);
        } catch (RuntimeException e) {
            log.warn("rewake.mcp.failed method={} reason={}", method, e.getMessage(), e);
        }
    }
}
