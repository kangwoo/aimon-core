package at.aimon.core.hook.rewake;

import java.util.Objects;

/**
 * External-event trigger — re-fire when a matching {@code (eventType, eventKey)} pair is delivered to
 * {@code RewakeService.resolve(...)}.
 *
 * <p>
 * {@code eventType} is a coarse channel discriminator (e.g. {@code "webhook"}, {@code "mcp.notification"}). The
 * {@code eventKey} is opaque to the framework — typically a tenant-defined identifier such as a ticket id or workflow
 * step.
 *
 * <p>
 * The pair is matched verbatim (case-sensitive). Wildcard / pattern matching is out of scope.
 *
 * <p>
 * Immutable; safe to share across threads.
 */
public final class RewakeTriggerEvent implements RewakeTrigger {

    private final String eventType;
    private final String eventKey;

    /**
     * Creates an event trigger.
     *
     * @param eventType
     *            channel discriminator (must not be null or blank)
     * @param eventKey
     *            opaque tenant-defined key (must not be null or blank)
     * @throws NullPointerException
     *             if either argument is null
     * @throws IllegalArgumentException
     *             if either argument is blank
     */
    public RewakeTriggerEvent(String eventType, String eventKey) {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        Objects.requireNonNull(eventKey, "eventKey cannot be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType cannot be blank");
        }
        if (eventKey.isBlank()) {
            throw new IllegalArgumentException("eventKey cannot be blank");
        }
        this.eventType = eventType;
        this.eventKey = eventKey;
    }

    /**
     * Returns the event channel discriminator.
     *
     * @return event type (never null or blank)
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Returns the opaque event key.
     *
     * @return event key (never null or blank)
     */
    public String getEventKey() {
        return eventKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RewakeTriggerEvent that)) {
            return false;
        }
        return eventType.equals(that.eventType) && eventKey.equals(that.eventKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventType, eventKey);
    }

    @Override
    public String toString() {
        return "RewakeTriggerEvent{eventType='" + eventType + "', eventKey='" + eventKey + "'}";
    }
}
