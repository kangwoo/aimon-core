package at.aimon.core.agent.session.store;

import java.util.Objects;
import java.util.UUID;

/**
 * The identity of one sealed segment of a session log.
 *
 * <p>
 * A fresh id is minted for every sealing ({@link #generate()}), never derived from the range it holds. Two nodes that
 * seal the same range — one of them having lost its lease without knowing it yet — therefore write two segments rather
 * than overwrite one, and the record's manifest decides which of them exists (session-log §5.2). A deterministic id
 * such as {@code (sessionId, fromSeq)} would let a widened re-seal or a second node put different content under the
 * same name.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class SegmentId {

    private final String value;

    private SegmentId(String value) {
        Objects.requireNonNull(value, "Segment id cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Segment id cannot be blank");
        }
        this.value = value;
    }

    /**
     * Wraps a stored id.
     *
     * @param value
     *            the id (must not be null or blank)
     * @return the id (never null)
     */
    public static SegmentId of(String value) {
        return new SegmentId(value);
    }

    /**
     * Mints a new random id — one per sealing.
     *
     * @return a fresh id (never null)
     */
    public static SegmentId generate() {
        return new SegmentId(UUID.randomUUID().toString());
    }

    /**
     * @return the id as stored (never null)
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof SegmentId other && value.equals(other.value);
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
