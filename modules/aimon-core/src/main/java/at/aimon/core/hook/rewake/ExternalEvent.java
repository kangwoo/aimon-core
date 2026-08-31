package at.aimon.core.hook.rewake;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable value object describing an inbound external event delivered to the rewake pipeline.
 *
 * <p>
 * The event is the input to {@link ExternalEventResolver#resolve(ExternalEvent)} and the per-transport stand-in for
 * the bare {@code (eventType, eventKey, payload)} triple consumed by
 * {@link RewakeService#resolve(String, String, java.util.Map)}. Carrying the data on a typed object — rather than
 * spreading three positional parameters across every transport call site — lets the resolver layer attach optional
 * context (idempotency key, source-transport tag, receive timestamp) for observability and dedup without changing
 * the signature.
 *
 * <p>
 * Required fields:
 * <ul>
 * <li>{@link #getEventType() eventType} — non-blank discriminator that must match
 * {@link RewakeTriggerEvent#getEventType()} on a pending envelope.
 * <li>{@link #getEventKey() eventKey} — non-blank tenant-supplied key matched against
 * {@link RewakeTriggerEvent#getEventKey()}.
 * </ul>
 *
 * <p>
 * Optional fields:
 * <ul>
 * <li>{@link #getPayload() payload} — additional entries merged into matched envelopes' payloads at fire time
 * (defaults to empty).
 * <li>{@link #getIdempotencyKey() idempotencyKey} — transport-supplied key used by transports (notably the webhook
 * adapter, see {@code aimon-rewake-webhook}) to dedup redelivered events. The resolver layer treats this as opaque;
 * dedup itself is the transport's responsibility — the SPI only carries the key for visibility.
 * <li>{@link #getSourceTransport() sourceTransport} — short identifier for the transport that delivered the event
 * (e.g. {@code "webhook"}, {@code "mcp"}). Surfaced in resolver logs so operators can tell which adapter produced
 * a given fire.
 * <li>{@link #getReceivedAt() receivedAt} — wall-clock instant at which the transport accepted the event (defaults
 * to {@link Instant#now()} at build time when omitted). Lets observability surface the dispatch latency from
 * arrival to fire.
 * </ul>
 *
 * <p>
 * Immutable; safe to share across threads. Build via {@link #builder()}.
 */
public final class ExternalEvent {

    private final String eventType;
    private final String eventKey;
    private final Map<String, String> payload;
    private final String idempotencyKey;
    private final String sourceTransport;
    private final Instant receivedAt;

    private ExternalEvent(Builder builder) {
        this.eventType = Objects.requireNonNull(builder.eventType, "eventType must be set");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType cannot be blank");
        }
        this.eventKey = Objects.requireNonNull(builder.eventKey, "eventKey must be set");
        if (eventKey.isBlank()) {
            throw new IllegalArgumentException("eventKey cannot be blank");
        }
        this.payload = Map.copyOf(builder.payload);
        this.idempotencyKey = builder.idempotencyKey;
        this.sourceTransport = builder.sourceTransport;
        this.receivedAt = builder.receivedAt != null ? builder.receivedAt : Instant.now();
    }

    /**
     * @return new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return event-type discriminator (never null or blank)
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * @return event key (never null or blank)
     */
    public String getEventKey() {
        return eventKey;
    }

    /**
     * @return payload entries (never null; possibly empty; immutable)
     */
    public Map<String, String> getPayload() {
        return payload;
    }

    /**
     * @return optional transport-supplied idempotency key
     */
    public Optional<String> getIdempotencyKey() {
        return Optional.ofNullable(idempotencyKey);
    }

    /**
     * @return optional transport tag (e.g. {@code "webhook"}, {@code "mcp"})
     */
    public Optional<String> getSourceTransport() {
        return Optional.ofNullable(sourceTransport);
    }

    /**
     * @return wall-clock instant at which the transport accepted this event (never null)
     */
    public Instant getReceivedAt() {
        return receivedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExternalEvent that)) {
            return false;
        }
        return eventType.equals(that.eventType) && eventKey.equals(that.eventKey) && payload.equals(that.payload)
                && Objects.equals(idempotencyKey, that.idempotencyKey)
                && Objects.equals(sourceTransport, that.sourceTransport) && receivedAt.equals(that.receivedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventType, eventKey, payload, idempotencyKey, sourceTransport, receivedAt);
    }

    @Override
    public String toString() {
        return "ExternalEvent{eventType='" + eventType + "', eventKey='" + eventKey + "', payload=" + payload
                + ", idempotencyKey=" + idempotencyKey + ", sourceTransport=" + sourceTransport + ", receivedAt="
                + receivedAt + '}';
    }

    /** Builder for {@link ExternalEvent}. */
    public static final class Builder {
        private String eventType;
        private String eventKey;
        private final Map<String, String> payload = new LinkedHashMap<>();
        private String idempotencyKey;
        private String sourceTransport;
        private Instant receivedAt;

        private Builder() {
        }

        /**
         * Sets the event-type discriminator (required).
         *
         * @param eventType
         *            non-blank discriminator
         * @return this builder
         */
        public Builder eventType(String eventType) {
            this.eventType = Objects.requireNonNull(eventType, "eventType cannot be null");
            return this;
        }

        /**
         * Sets the event key (required).
         *
         * @param eventKey
         *            non-blank tenant key
         * @return this builder
         */
        public Builder eventKey(String eventKey) {
            this.eventKey = Objects.requireNonNull(eventKey, "eventKey cannot be null");
            return this;
        }

        /**
         * Adds a single payload entry (existing keys are overwritten).
         *
         * @param key
         *            payload key (must not be null)
         * @param value
         *            payload value (must not be null)
         * @return this builder
         */
        public Builder payload(String key, String value) {
            Objects.requireNonNull(key, "payload key cannot be null");
            Objects.requireNonNull(value, "payload value cannot be null");
            this.payload.put(key, value);
            return this;
        }

        /**
         * Adds every entry from {@code entries}.
         *
         * @param entries
         *            entries (must not be null; values must not be null)
         * @return this builder
         */
        public Builder payload(Map<String, String> entries) {
            Objects.requireNonNull(entries, "entries cannot be null");
            entries.forEach(this::payload);
            return this;
        }

        /**
         * Sets the optional transport-supplied idempotency key.
         *
         * @param idempotencyKey
         *            opaque key (may be null)
         * @return this builder
         */
        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        /**
         * Sets the optional source-transport tag.
         *
         * @param sourceTransport
         *            short transport identifier (may be null)
         * @return this builder
         */
        public Builder sourceTransport(String sourceTransport) {
            this.sourceTransport = sourceTransport;
            return this;
        }

        /**
         * Sets the wall-clock instant at which the transport accepted this event. Defaults to
         * {@link Instant#now()} at build time when omitted.
         *
         * @param receivedAt
         *            instant (may be null)
         * @return this builder
         */
        public Builder receivedAt(Instant receivedAt) {
            this.receivedAt = receivedAt;
            return this;
        }

        /**
         * Builds the event.
         *
         * @return new {@link ExternalEvent} (never null)
         * @throws NullPointerException
         *             if a required field was not set
         * @throws IllegalArgumentException
         *             if {@code eventType} or {@code eventKey} is blank
         */
        public ExternalEvent build() {
            return new ExternalEvent(this);
        }
    }
}
