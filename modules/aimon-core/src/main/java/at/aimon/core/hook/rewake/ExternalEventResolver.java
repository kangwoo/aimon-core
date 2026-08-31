package at.aimon.core.hook.rewake;

/**
 * SPI consumed by external-trigger transports (webhook adapter, MCP notification glue, ...) to deliver an
 * {@link ExternalEvent} into the rewake pipeline.
 *
 * <p>
 * The resolver is the narrow seam between transport adapters and {@link RewakeService}. Transports build a typed
 * {@link ExternalEvent} (carrying optional metadata such as {@code idempotencyKey}, {@code sourceTransport},
 * {@code receivedAt}) and hand it to {@link #resolve(ExternalEvent)}; the resolver layer is responsible for
 * delegating to {@link RewakeService#resolve(String, String, java.util.Map)} and surfacing observability around
 * the dispatch.
 *
 * <p>
 * Why not call {@link RewakeService} directly?
 * <ul>
 * <li><strong>Interface segregation</strong> — transports only need a single method. They should not see the rest
 * of the {@link RewakeService} surface ({@code schedule}, {@code cancel}, {@code listPending}).
 * <li><strong>Observability seam</strong> — the resolver layer logs the typed metadata
 * ({@link ExternalEvent#getSourceTransport()}, {@link ExternalEvent#getIdempotencyKey()},
 * {@link ExternalEvent#getReceivedAt()}) so operators can diagnose redelivery, dispatch latency, and per-transport
 * fire rates without changing {@link RewakeService}.
 * </ul>
 *
 * <p>
 * Idempotency is the transport's responsibility — the resolver layer does not dedup by
 * {@link ExternalEvent#getIdempotencyKey()}. The webhook adapter (see {@code aimon-rewake-webhook}) maintains its
 * own dedup store; the resolver merely carries the key for visibility.
 *
 * <p>
 * Implementations must be thread-safe — transports may invoke {@code resolve} from multiple request-handler threads.
 */
public interface ExternalEventResolver {

    /**
     * Delivers an external event into the rewake pipeline.
     *
     * <p>
     * Equivalent in effect to {@link RewakeService#resolve(String, String, java.util.Map)} called with
     * {@link ExternalEvent#getEventType()}, {@link ExternalEvent#getEventKey()}, and
     * {@link ExternalEvent#getPayload()}. The optional metadata on the event is used for observability only.
     *
     * @param event
     *            inbound event (must not be null)
     * @return the number of envelopes fired (always {@code >= 0})
     * @throws NullPointerException
     *             if {@code event} is null
     */
    int resolve(ExternalEvent event);
}
