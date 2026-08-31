package at.aimon.core.hook.rewake.impl;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.rewake.ExternalEvent;
import at.aimon.core.hook.rewake.ExternalEventResolver;
import at.aimon.core.hook.rewake.RewakeService;

/**
 * Default {@link ExternalEventResolver} implementation that delegates to a {@link RewakeService} and surfaces
 * structured logs for observability.
 *
 * <p>
 * Logged fields per resolve:
 * <ul>
 * <li>{@code eventType} / {@code eventKey} — match keys against pending
 * {@link at.aimon.core.hook.rewake.RewakeTriggerEvent
 * RewakeTriggerEvent} envelopes.
 * <li>{@code sourceTransport} — which adapter delivered the event (e.g. {@code "webhook"}, {@code "mcp"}).
 * <li>{@code idempotencyKey} — opaque transport key, surfaced for redelivery diagnosis.
 * <li>{@code receivedAt} — wall-clock arrival timestamp; lets operators compute dispatch latency.
 * <li>{@code matched} — number of envelopes fired.
 * </ul>
 *
 * <p>
 * Thread-safe: {@link RewakeService} implementations are required to be thread-safe and this class adds no mutable
 * state.
 */
public final class DefaultExternalEventResolver implements ExternalEventResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultExternalEventResolver.class);

    private final RewakeService rewakeService;

    /**
     * @param rewakeService
     *            the rewake service to delegate to (must not be null)
     */
    public DefaultExternalEventResolver(RewakeService rewakeService) {
        this.rewakeService = Objects.requireNonNull(rewakeService, "rewakeService cannot be null");
    }

    @Override
    public int resolve(ExternalEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        final int matched = rewakeService.resolve(event.getEventType(), event.getEventKey(), event.getPayload());
        log.debug(
                "rewake.external-event.resolved eventType={} eventKey={} sourceTransport={} idempotencyKey={}"
                        + " receivedAt={} matched={}",
                event.getEventType(), event.getEventKey(), event.getSourceTransport().orElse(null),
                event.getIdempotencyKey().orElse(null), event.getReceivedAt(), matched);
        return matched;
    }
}
