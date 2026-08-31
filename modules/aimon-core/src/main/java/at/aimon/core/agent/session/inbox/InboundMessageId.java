package at.aimon.core.agent.session.inbox;

import java.util.Objects;

/**
 * Stable opaque identifier of an {@link InboundMessage} appended to a {@code SessionInbox}.
 *
 * <p>
 * The implementation backing the inbox issues the value at deliver time (UUID for in-memory, Redis Streams entry id
 * like {@code "1700000000000-0"} for Redis). Callers and the manager treat the value as opaque — only equality and
 * value extraction are meaningful.
 *
 * <p>
 * Immutable value object.
 */
public final class InboundMessageId {

    private final String value;

    private InboundMessageId(String value) {
        this.value = Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value must not be empty");
        }
    }

    /**
     * Wraps an opaque string into an {@code InboundMessageId}.
     *
     * @param value
     *            the opaque value (must not be null, must not be empty)
     * @return a new {@code InboundMessageId}
     */
    public static InboundMessageId of(String value) {
        return new InboundMessageId(value);
    }

    /**
     * Returns the underlying opaque value.
     *
     * @return the value (never null, never empty)
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return value.equals(((InboundMessageId) o).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
